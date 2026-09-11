# ML 요청 대기·참조 캐시 검증 (2026-09-11)

Jira: S15P21E202-46. 모델 `20260908-v12`, HTTP 결과는 계속 PREVIEW다.
이 변경은 CPU 서빙의 혼잡 처리와 반복 파일 읽기를 개선한다. 재학습·운영 승인·Docker 배포는 수행하지 않는다.

## 변경 동작

- HTTP 입장 순서대로 실행 1건 + 대기 최대 4건. 대기는 기본 5초이며 초과/만료는 기존 `MODEL_BUSY` 503이다.
- 스레드풀 앞에서 대기를 제한한다. health는 이벤트 루프에서 응답한다.
- 대기 중 취소/만료는 실행하지 않는다. 실행 중 클라이언트 취소는 계산 완료까지 슬롯을 유지한다.
- 참조는 처음 필요할 때 읽어 Arrow Table로 캐시한다. 캐시 보관 상한은 기본 256 MiB이며 LRU로 퇴출한다.
- 상한보다 큰 단일 Table은 보관하지 않아 이후에도 읽는다. 캐시 0은 비활성화다.
- SHA 검증된 portable 함수의 pandas/pyarrow 읽기만 객체별로 치환한다. 전역 라이브러리·모델·묶음 파일·특성식은 변경하지 않는다.
- 같은 SHA 묶음은 실행 중 불변이어야 한다. 새 버전은 새 디렉터리 설치·검증·재시작으로 반영한다.

## 실제 모델 측정

Windows 11, Python 3.12.14, requirements 고정 버전, CPU 모델 스레드 2개.
각 조건을 새 프로세스에서 순차 실행했다. 기존 방식도 같은 도구에서 원본 portable reader로 복원해 비교했다.
HTTP와 별개인 직접 Predictor 호출 4회이며, 첫 요청은 새 프로세스의 빈 캐시지만 OS 파일 캐시 초기화는 하지 않았다.
원본 수치는 [측정 JSON](../../ml-serving/examples/capacity-benchmark-20260911.json)에 보존한다.

| 조건 | 첫 요청 | 반복 3회 | 최대 프로세스 메모리 |
|---|---:|---:|---:|
| 기존 파일 읽기, 10개 구간 | 2.072초 | 1.856 / 1.931 / 1.815초 | 1370.1 MiB |
| 256 MiB 캐시, 10개 구간 | 1.833초 | 1.614 / 1.629 / 1.649초 | 1542.9 MiB |
| 256 MiB 캐시, 1000개 구간 | 3.534초 | 3.382 / 3.502 / 3.429초 | 1537.4 MiB |

- 10개 구간 반복 평균은 1.868 → 1.631초(약 13% 감소). 계산/변환 비용은 남아 있으므로 파일 캐시만으로 지연이 없어지지 않는다.
- 15개 Table, 192,193,315 bytes(약 183.3 MiB)를 보관했고 4회 호출 동안 hit 45 / miss 19였다.
  예산보다 큰 Table은 매번 읽으므로 전체 읽기가 0이 되는 구현은 아니다.
- 1000개는 검증된 10개 입력을 반복하고 상관키를 다르게 만든 크기 점검이다. 서로 다른 1000개 구간·여러 날짜의 실사용 분포 검증이 아니다.
- 모든 실행에서 서버 golden 예측 오차 0초, 결측 개수 일치. 기존/캐시 10개 결과 SHA256도 동일하다.
  단위 테스트는 전체 results(예측·표본 수·결측·순서·상태)도 기존 reader와 대조한다.

추가로 실제 모델에 10개 배치 2건을 동시에 ASGI 요청했다. 둘 다 200이며 약 1.840초와
3.708초에 완료되어 두 번째 요청이 대기 후 성공했다. 이 별도 실행의 최대 메모리는 1570.5 MiB였다.
ASGI 검증은 실제 앱·대기열·모델을 통과하지만 TCP·Nginx·운영 서버 지연은 포함하지 않는다.

메모리는 Windows의 최대 process working set이며 컨테이너 RSS와 같은 환경의 값이 아니다.
ZIP 231MB, Table 캐시 256 MiB, 프로세스 약 1.5 GiB는 서로 다른 값이다. 모델 로딩·pandas 변환·
임시 버퍼·요청 메모리를 포함해 서버 2에서 다시 측정한 뒤 컨테이너 상한을 정한다.

## 재현

최종 테스트는 실제 모델 포함 36개 통과, 모델 없는 CI 조건은 35개 통과·1개 skip이었다.
FIFO·큐 초과·대기 만료·대기/실행 취소·슬롯 양도 직후 취소·종료·실패 후 복구·health 응답,
캐시 LRU/상한/읽기 실패/미검증 경로·공개 OpenAPI 일치를 검증했다.
테스트 라이브러리의 기존 deprecation 경고 2개가 있으며 실패는 없다.

프로젝트 루트, Python 3.12와 `ml-serving/requirements-dev.txt` 설치 후 실행한다.
`--bundle`은 전달받아 검증한 전체 모델 디렉터리로 바꾼다. 측정끼리 동시에 실행하지 않는다.

```bash
python ml-serving/tools/benchmark_serving.py --bundle /models/20260908-v12 --cache-mib 0 --original-reader --output data/original.json
python ml-serving/tools/benchmark_serving.py --bundle /models/20260908-v12 --cache-mib 256 --output data/cached.json
python ml-serving/tools/benchmark_serving.py --bundle /models/20260908-v12 --batch-size 1000 --output data/cached-1000.json
python ml-serving/tools/benchmark_serving.py --bundle /models/20260908-v12 --http-concurrency 2 --iterations 2 --output data/http.json
python -m pytest ml-serving/tests -q
```

실제 모델 테스트는 기본 모델 디렉터리가 없으면 skip한다. `ML_TEST_BUNDLE_DIR`를 명시하면
해당 경로의 누락/손상은 실패로 처리한다. CI에는 모델 묶음을 주입하지 않고 단위 테스트를 실행한다.
Windows에서 pytest 임시 디렉터리에 접근하지 못하면 새로운 작업 폴더 아래 `--basetemp`를 지정한다.

## 인프라·Backend 전달

1. 이미지에 새 `reference_cache.py`, `serving_runtime.py`를 함께 포함한다. 기존 묶음 재생성은 필요 없다.
2. `.env.example`의 `ML_MAX_WAITING_REQUESTS`, `ML_QUEUE_WAIT_SECONDS`, `ML_REFERENCE_CACHE_MIB`를 필요 시 실제 환경변수로 전달한다. 미지정 시 기본값을 쓴다.
3. worker 1개로 시작한다. 추가 worker는 별도 모델·캐시 메모리가 필요하다.
4. Backend 전체 timeout은 대기 + 계산 + 통신 예산이다. 기본 대기 5초와 전체 timeout 5초는 맞지 않는다.
   전체 10초는 초기 검증용 제안이며, 큰 배치·부하·서버 실측에 맞춰 재합의한다. 즉시 자동 재시도는 사용하지 않는다.
5. live/ready와 실제 batch를 함께 점검한다. 배포 후 실패율·대기 만료·메모리를 확인한다.
6. 새 날짜 참조 묶음 생성, 실제 운영 승인, 서버 배포·파일 전달은 별도 작업이다.
