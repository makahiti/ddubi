# Spark realtime 버스 대응표 캐시 수정 검증

2026-09-11. 운영 서버·인프라 설정은 변경하지 않았다.

## 변경

- `common/bus_crosswalk.py`: 큰 JSON 리터럴 대신 `spark.createDataFrame(rows, SCHEMA)` 사용.
- 후보 집계 뒤 `cache()` + `count()`로 스트리밍 시작 전에 인덱스를 물리화.
- 기본 MEMORY_AND_DISK 캐시를 사용한다. 21만 행을 무조건 broadcast하지 않는다.
- 기동 시 캐시 생성 실패와 전체 query 종료 시에만 `unpersist()`한다.
- SHA, masterVersion, canonical ID 검증과 중복 후보의 AMBIGUOUS 처리는 유지한다.
- 배치마다 만든 normalized/latest/rows 캐시와 정적 인덱스 캐시 수명은 다르다.

## 실제 크기 검증

입력: v6 인접 구간 105,070개 → 조회 행 210,140개 → 집계 인덱스 208,329개.
SHA: e7b8db4f3330d7275aa56b4674032d71aa6e34f99bec0c29cee9f7ce002676bd

Windows local[1], Python 3.11.9, Spark 3.5.3, Java 17에서:

- 대응표 테스트 5개 통과. ID 보존, 버전 불일치, 실제 크기 캐시와 반복 실행 계획 검사 포함.
- realtime/Redis/도로 영향 관련 테스트 31개 통과.
- 기동 물리화 약 6.02초.
- 100행 probe × 12회 조인: 매회 100개 후보 유지, 약 0.14~0.59초/action.
- 실행 계획에 `InMemoryTableScan` 확인. 거대 `from_json` 리터럴 없음.
- 반복 조인 동안 관측한 JVM heap 약 218~420 MiB. GC 이후 감소하며 계속 증가하지 않음.

Kafka 읽기·Redis 쓰기·운영 컨테이너를 포함한 성능이나 장시간 메모리 안정성의 증거는 아니다.
Windows Python 3.12 로컬 worker가 종료되어 별도 Python 3.11 테스트 환경으로 검증했다.
운영 Spark/Python 버전이나 시스템 Python 설치는 변경하지 않았다.

## 인프라 인계

인프라의 Spark 대응표 임시 OFF 조치는 이 변경에서 자동 해제하지 않는다.
수정 배포 후 동일 v6 파일·SHA로 Spark만 재활성화할 때 기동 캐시 생성 완료,
후속 배치 처리시간, driver heap, 컨테이너 restart count, 미매핑 사유를 확인한다.
Collector 쪽 기존 대응표와 버전은 그대로 유지한다.

## 2026-09-13 실제 Kafka/Redis를 포함한 격리 검증

`tools/verify_realtime_pipeline.py`는 Collector의 Publisher/BusCrosswalk와 운영 `process_batch`를
사용한다. 위치 관측은 명시적 검증 fixture이며 실제 운행 표본이 아니다. `process_batch`를
모듈 함수로 분리해 main과 이 도구가 동일한 배치 수명/Redis 쓰기 로직을 실행한다.

- Linux Docker, Spark 3.5.3, Python 3.8, local[2], driver heap 2 GiB, 컨테이너 한도 4 GiB.
- v6 전체 인덱스 208,329행. 정상 30배치 + 늦은 데이터 + 버전 불일치 = 32배치/3,200메시지 ACK.
- 첫 배치 13.51초, 이후 1.91~3.40초. 배치 종료 시 측정 힙 364,315,800~762,938,496 bytes.
- InMemoryTableScan 유지, Redis 최신값/동일 SHA 유지, 역순 이벤트 덮어쓰기 방지,
  query 정상 종료 후 같은 체크포인트로 재개, 버전 불일치 시 canonical ID 거절 통과.
- 해당 컨테이너 exit=0, OOMKilled=false, RestartCount=0.
- 재실행 시 토픽/차량/eventId를 출력 디렉터리별로 분리한 최종 도구도 500메시지로 재확인했다.

측정 힙은 전체 실행 중 peak 또는 컨테이너 RSS가 아니다. 이 시험은 운영 부하·장시간 누수·강제 종료
이 기록 당시에는 운영 Spark의 임시 crosswalk OFF 설정을 해제하지 않았다. 이후
`recommendation-data-readiness.md` 작업에서 검증된 동일 artifact/SHA를 Compose 기본값에 연결했다.
운영 OOM/lag 확인은 여전히 별도 배포 검증 항목이다.
재실행 방법은 [검증 도구 설명](../../sparkjobs/tools/README.md), 범위별 결과는
[데이터 담당 보강 결과](data-role-hardening-20260913.md)를 따른다.
