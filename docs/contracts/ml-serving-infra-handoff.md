# 버스 예측 서비스 인프라 전달사항

- 작성 기준: 2026-09-10
- Jira: [S15P21E202-46](https://ssafy.atlassian.net/browse/S15P21E202-46)
- 코드 MR: [!78](https://lab.ssafy.com/s15-bigdata-dist-sub1/S15P21E202/-/merge_requests/78), 대상 `dt/dev`
- 브랜치: `feature/dt/S15P21E202-46-ml-serving-api`
- 목적: GPU에서 학습한 v12 버스 모델을 CPU 추론 HTTP 서비스로 배포하여 Spring에서 접근할 수 있게 한다.
- 현재 상태: 로컬 실모델 검증 완료. 서버 배포·Spring HTTP 호출부 연결은 미완료다.

## 1. 인프라 담당자에게 요청하는 작업

서버 2의 Spring과 같은 내부 Docker 네트워크에 **별도 `ml-serving` 서비스**를 추가하는 구성을
제안한다. 서버 2의 여유 CPU·메모리를 확인한 뒤 배치 위치를 확정해 달라는 요청이며,
현재 Compose에 이미 등록되어 있다는 뜻은 아니다.

1. Python 3.12 기반 실행 이미지와 서비스 설정을 준비한다.
2. 아래 모델 묶음을 별도 전달받아 서버에 설치하고 컨테이너에 읽기 전용으로 연결한다.
3. 내부에서만 접근하는 8000 포트로 서비스 하나를 실행한다.
4. Spring 컨테이너에서 readiness 및 실제 배치 요청을 확인한다.
5. 확정한 내부 주소·파일 전달 위치·배포 절차를 ML/Backend 담당자에게 공유한다.

Dockerfile, Compose, CI/CD 변경은 인프라 담당자 작업이다. 이 문서는 필요한 인터페이스와
검증 절차를 전달하며, 해당 배포 설정을 구현하거나 기존 운영 설정을 변경하지 않는다.

## 2. 서비스 연결 구조와 역할

```text
사용자 앱 → Spring Backend → ml-serving:8000 → 모델 추론
사용자 앱 ← 최종 추천 응답 ← Spring Backend ← 구간별 예측 결과
```

| 담당 | 범위 |
|---|---|
| ML | 서비스 코드·고정 의존성·모델/참조 묶음·검증 표본 전달, 참조 갱신 및 모델 검증 |
| 인프라 | 이미지 빌드·내부 네트워크·모델 볼륨·상태 점검·자원 제한·로그·재시작/배포 관리 |
| Backend | HTTP 클라이언트·시간대/ID 매핑·timeout/fallback·최종 추천 연결 |

앱이 Python을 직접 호출하지 않는다. 배포 성공만으로 일정 등록부터 추천까지 완성되지는 않는다.
현재 결과는 `PREVIEW`이며, 실제 추천 반영에는 별도 모델/ID 검증과 Backend 계약 연결이 필요하다.

## 3. 전달할 코드와 모델 파일

### Git으로 전달

- `ml-serving/`: HTTP 서비스, requirements, 설치/검증 도구, 실제 요청·응답 예시
- [API 상세 명세](ml-serving-http.md)
- [OpenAPI JSON](ml-serving.openapi.json)
- [설치·검증 README](../../ml-serving/README.md)

배포할 코드는 승인된 커밋으로 고정하고, 이미지 태그도 프로젝트 인프라 원칙에 맞춰 고정한다.
현재 로컬 모델은 `20260908-v12`이며, 다른 모델 파일만 바꿔 끼우는 방식으로 배포하지 않는다.

### Git 외부로 별도 전달

다음 두 방식 중 하나를 사용한다. 실제 전송 위치와 수단은 인프라 담당자가 지정한다.

| 방식 | 전달 대상 | 처리 |
|---|---|---|
| 설치된 묶음 전체 | `artifacts/ml-serving/20260908-v12/` 전체 | 구조 그대로 복사 후 SHA/추론 검증 |
| 원본 전달 파일 | `selected_model.joblib` + `serving-reference-20260910.zip` | 설치 도구로 새 디렉터리에 설치 |

현재 로컬 원본 파일 위치는 프로젝트 `data/ml_staging/`이다. 모델은 약 119MiB,
참조 ZIP은 약 231MiB다. 이는 전달 파일 크기이며 컨테이너 메모리나 압축 해제 후 디스크 요구량이 아니다.
전송물·설치본·이미지·이전 버전 보존 공간을 함께 고려해 디스크를 확인한다.

설치된 묶음은 아래 요소가 모두 필요하다.

```text
20260908-v12/
  bundle.json
  selected_model.joblib
  input_spec.json
  serving_manifest.json
  portable_features.py
  references/                  # 과거 운행·수요·좌표 등 참조 Parquet
  golden.json                  # ZIP에 포함된 초기 비교 표본
```

`selected_model.joblib`만 복사하거나 참조 자료를 생략하면 정상 서비스가 준비되지 않는다.
`serving-inspection.zip`은 조사용 소스로 배포 필수 파일이 아니며 참조 ZIP을 대신하지 못한다.
모델/참조 자료와 로컬 `.venv`는 Git에 넣지 않는다. Windows `.venv`를 Linux 서버로 복사하지 않고
서버 이미지 안에서 의존성을 새로 설치한다.

## 4. 실행 인터페이스

| 항목 | 요구사항 / 예시 |
|---|---|
| Python | 3.12 기준 |
| 패키지 | `ml-serving/requirements.txt`의 정확한 버전 사용 |
| GPU | 현재 LightGBM CPU 추론에는 필수 아님 |
| 환경변수 | `ML_BUNDLE_DIR`: 컨테이너 안에서 읽을 모델 묶음의 **절대경로** |
| 모델 마운트 예시 | 서버의 버전별 디렉터리 → `/models/20260908-v12`, 읽기 전용 |
| 실행 디렉터리 예시 | `/app/ml-serving` |
| 프로세스 | Uvicorn worker 1개로 시작 |
| 리슨 주소 | 컨테이너 내부 `0.0.0.0:8000` |
| Spring 호출 주소 예시 | `http://ml-serving:8000` |
| 외부 공개 | 별도 공개 포트/공개 Nginx 경로 불필요 |

실행 예시 — 위 디렉터리와 모델 마운트를 구성한 컨테이너 내부 기준:

```bash
export ML_BUNDLE_DIR=/models/20260908-v12
python -m uvicorn app:app --host 0.0.0.0 --port 8000 --workers 1
```

`ML_BUNDLE_DIR`은 실제 환경변수로 주입한다. 현재 서비스는 `.env.example`을 자동 로드하지 않는다.
명시한 `/app`, `/models` 및 서비스명은 배포 설계 예시이며 실제 확정 경로가 아니다.

코드의 패키지 버전 검사는 불일치 시 readiness를 실패시킨다. 이미지에서 LightGBM과
Parquet 라이브러리의 import 및 네이티브 라이브러리 로딩까지 검증한다.
운영 이미지에는 `requirements.txt`, 별도 검증 환경에는 `requirements-dev.txt`를 사용한다.

현재 애플리케이션 자체 API key/JWT 인증은 없다. Spring과 같은 내부 네트워크에서 접근시키며
호스트의 8000 포트를 인터넷에 공개하지 않는다. 다른 서버에 배치한다면 사설 통신·접근 제어를
인프라 정책에 따라 별도로 확정한다. 카카오 API 키나 사용자 JWT는 Python 서비스에 필요하지 않다.

## 5. 모델 설치와 무결성 검증

아래는 코드가 체크아웃된 **프로젝트 루트**, Python 3.12와 의존성이 준비된 Linux 환경의 예시다.
파일을 `data/ml_staging/`에 전달받았다는 가정이며 실제 전달 위치에 맞게 변경한다.

```bash
python ml-serving/tools/install_bundle.py \
  --archive data/ml_staging/serving-reference-20260910.zip \
  --model data/ml_staging/selected_model.joblib \
  --destination artifacts/ml-serving/20260908-v12
```

설치 도구는 기존 디렉터리를 덮어쓰지 않는다. 목적지가 이미 있으면 새 버전 디렉터리를 지정한다.
압축 경로와 크기를 검사하고 모델·규격·참조의 SHA256을 대조한다. 자체 전달 산출물만 사용하며,
SHA 일치는 파일 일관성 검사이지 출처 불명의 joblib을 신뢰해도 된다는 의미가 아니다.

원본 묶음에는 처음에 미등록 노선 표본이 포함되었으므로, 서버 원본과 수치를 비교할 때는
저장소의 검증 파일을 `--golden`으로 **명시**한다. 검증 환경에는 `requirements-dev.txt`가 필요하다.

```bash
python ml-serving/tools/verify_bundle.py \
  --bundle artifacts/ml-serving/20260908-v12 \
  --golden ml-serving/examples/v12-server-golden.json \
  --output data/ml-serving-verification
```

기대 결과는 비교 표본 10개, 최대 차이 0초(도구 허용 오차 `1e-5`초 이내)다.
검증 보고서는 `data/ml-serving-verification/verification.json`에 생성된다.
이 검증은 서버와 로컬 추론의 동등성 확인이며 전체 노선 정확도 평가가 아니다.

## 6. 배포 후 상태·통신 확인

| 요청 | 정상 응답 | 의미 |
|---|---|---|
| `GET /health/live` | 200, `{"status":"UP"}` | 프로세스 생존 |
| `GET /health/ready` | 200, `status=READY_FOR_PREVIEW` 및 모델/지원 기간 | 모델 객체 로드 완료 |
| `POST /v1/bus/predictions:batch` | 200, 구간별 PREVIEW/UNAVAILABLE | 요청 처리 완료. 운영 사용 승인 아님 |

모델 로드가 실패해도 프로세스는 살아 있을 수 있다. 이때 live는 200, ready는
503 `{"detail":"MODEL_NOT_READY"}`이므로 **live만으로 배포 성공을 판정하지 않는다.**
readiness가 성공한 후 실제 추론을 한 번 수행한다. readiness는 매번 파일을 재해시하거나
추론을 실행하지 않으므로 이후 참조 손상·I/O 실패까지 보장하지 않는다.

Spring과 같은 네트워크의 점검 환경에서, 저장소 예시 파일을 사용할 수 있을 때 실행한다.

```bash
curl --connect-timeout 2 --max-time 10 'http://ml-serving:8000/health/ready'
curl --connect-timeout 2 --max-time 10 \
  -H 'Content-Type: application/json' \
  --data-binary @ml-serving/examples/bus-batch-request.json \
  'http://ml-serving:8000/v1/bus/predictions:batch'
```

정상 예시 2개의 평균 초는 약 `145.1247447449537`, `340.31162658466326`이다.
`production_ready=false`, 각 결과의 `status=PREVIEW`도 함께 확인한다.
이 두 표본은 독립 검증 edge이며 하나의 연속된 사용자 경로를 뜻하지 않는다.
curl 예시의 연결 2초·전체 10초는 수동 점검 예산이며 운영 SLA가 아니다.

## 7. 자원·장애·로그 운영

- 로컬에서 10개 일괄 요청은 약 2초였으며 1초 전체 timeout은 부족했다. 서버 실측 후
  Backend의 전체 timeout과 호출 빈도를 합의한다. 초기 연동 확인에는 전체 10초를 제안한다.
- 프로세스당 동시에 한 배치만 추론하고 기본 4건을 FIFO로 최대 5초 대기시킨다.
  `ML_MAX_WAITING_REQUESTS`(0~32), `ML_QUEUE_WAIT_SECONDS`(0~30초)로 조정한다.
  큐 초과/대기 만료는 `MODEL_BUSY` 503이며 0 설정은 기존 즉시 거절 방식이다.
  worker를 늘리면 모델·참조 처리 메모리도 늘 수 있으므로 초기에는 1개를 유지한다.
- 추론 CPU 스레드는 2개로 제한하지만 컨테이너 자원 예약/상한을 뜻하지 않는다.
  모델 로딩·정상 배치·큰 배치의 메모리와 CPU를 측정해 `mem_limit` 등 기존 인프라 규칙에 맞춘다.
- `ML_REFERENCE_CACHE_MIB`는 기본 256(0~4096)이며 최초 읽은 참조 Table을 LRU로 보관한다.
  0이면 캐시 비활성화, 예산보다 큰 단일 파일은 보관하지 않는다. 전체 선적재 방식이 아니다.
  ZIP 크기·디코딩 캐시 상한·프로세스 최대 메모리는 서로 다르다. 모델과 pandas 변환 버퍼를
  포함한 메모리는 [검증 기록](ml-serving-capacity-validation.md)을 참고하고 서버에서 다시 측정한다.
- 새 이미지에는 기존 파일 외에 `reference_cache.py`, `serving_runtime.py`도 포함해야 한다.
  기존 모델/참조 묶음은 그대로 호환된다. Docker/Compose/CI는 이 변경에 포함하지 않는다.
- 전체 HTTP timeout에 대기와 계산 시간을 함께 반영한다. 현재 기본 대기 5초를 쓰면서
  Backend 전체 timeout도 5초로 두지 않는다. 초기 전체 10초 제안은 서버 검증 전 임시 예산이다.
- 서버 강제 요청 timeout은 별도 구현되어 있지 않다. 클라이언트가 timeout되어도 추론이 계속될 수 있다.
  즉시 반복 호출로 `MODEL_BUSY`를 늘리지 않도록 Backend와 재시도 정책을 맞춘다.
- 로그는 표준 출력/오류로 수집하고 기존 인프라의 로그 로테이션을 적용한다.
  로드/추론 오류 로그는 예외 종류만 기록하며 키·원본 경로 요청·모델 파일 내용을 추가 출력하지 않는다.
- 프로세스 종료 시 재시작 정책을 적용하되, live 상태의 모델 미준비는 별도 점검/알림이 필요하다.
  SHA 불일치·파일 누락은 재시작만 반복해서 해결되는 장애가 아니다.
- 추론 서비스는 MySQL/Redis/Kafka/HDFS 접속을 요구하지 않는다. 해당 시스템의 자격증명을 전달하지 않는다.

## 8. 업데이트·복구와 현재 제한

모델/인코더/참조를 실행 중인 디렉터리에서 개별 덮어쓰기 하지 않는다.
새 묶음을 새 디렉터리에 설치하고, 같은 실행 이미지로 검증한 뒤 마운트/환경변수를 변경해 재시작한다.
문제 발생 시 검증된 이전 **코드 이미지와 모델 묶음의 조합**으로 복구할 수 있도록 함께 보존한다.
이전 묶음도 요청 날짜를 지원하는지는 별도로 확인해야 한다. 실패하면 Backend가 fallback한다.

현재 지원 날짜는 **2026-09-10~17**이다. 서버에 배포해도 이 기간이 자동 확장되지 않는다.
모델을 재학습하지 않는 경우에도 이후 요청 날짜용 참조 묶음 생성·전달·검증이 필요하다.
이는 ML 담당자의 후속 작업이며 인프라에서 메타데이터 날짜만 변경하면 안 된다.

현재 `PREVIEW`/`production_ready=false`는 의도된 모델 상태다. 인프라 설정으로 true로 바꾸거나
readiness 성공을 운영 승인으로 취급하지 않는다. 기존 Backend 포트의 ID 조합·유효시각·승인 조건은
[상세 API 명세](ml-serving-http.md)의 연결 범위를 따른다.

## 9. 인프라 확인 후 회신받을 항목

- 배포 서버 및 서비스명, Spring에서 호출할 최종 내부 Base URL
- 모델 파일을 전달할 위치/수단과 서버의 버전별 보관 경로
- 컨테이너 내부 모델 경로, 읽기 전용 마운트 및 실행 사용자 읽기 권한
- 이미지 태그/배포 커밋, 자원 제한·재시작·상태 점검·로그 정책
- 서버의 실제 배치 응답시간·메모리 및 readiness/예시 요청 검증 결과
- 다음 참조 묶음 교체 시 배포·복구 절차

현재 문서 작성으로 파일 전송·서비스 배포·방화벽 변경이 실행된 것은 아니다.
