# 수도권 데이터·서비스 연결 보강 (2026-09-11)

현재 운영 수집 대상·주기와 infra/compose/CI 파일은 변경하지 않는다. 새 수집기와 Spark 잡은
명시적으로 실행할 때만 동작한다. 검증되지 않은 파일을 운영 대응표로 승격하지 않는다.

## 구현 경계

| 경계 | 이번 변경 | 실제 운영 적용 조건 |
|---|---|---|
| 경기·인천 Collector | 도착/위치 4개 진입점, 요청 예산·명시 대상·페이지·원본 ID 보존 | 기관별 키와 검증 대상 별도 지정. 기존 운영 대상은 유지 |
| 최신 관측 | 지역별 초/분 변환, source namespace 분리, 인천 차량별 키 | 인천 CSV↔API 대응표 검증 전 canonical 미매핑 |
| 실측 상태 | Kafka 실측 사실 보존 → 구간/버전/중복/시각/날씨 검증 → 기준선·상태 → Redis ACK → Kafka | 실제 통과시간 원천 및 검증된 기준선/정책 필요 |
| 도로 | 검토 대응표 생성 CLI → city.raw 사건의 링크별 Redis → road.impact → 일정 재평가 | 실제 운행 방향·링크 namespace·유효기간 검토된 artifact 필요 |
| Backend | 구간 통과 예상 구간과 사건 기간 비교, 근거 부족 이유, 대체 후보 비교 | 미매핑은 무사고로 취급하지 않음. 자동 경로 변경 없음 |
| 지하철 | 기존 작업의 수도권 리소스 마스터/Matcher/별칭, 인증된 gzip 다운로드 | 동일 파일 버전/SHA로 Spark 배포, 실제 운행 시간표는 별도 |
| 동시성 | schedule → evaluation 순서의 일정별 짧은 트랜잭션 | MySQL 다중 스레드 통합 테스트 포함 |
| 프런트 | 가상 지연 안내를 실제 일정 목록·평가 조회로 교체, 도로 영향/부족 표시 | 일정 API 및 인증 필요 |
| ML | 동일 환경 point-in-time 전처리, 날짜 7:3 분리, 별도 GPU 0 학습 도구와 추론 API | 검증된 과거 가용 이력/라벨과 모델 bundle 필요. v12를 덮어쓰지 않음 |

## Collector

`COLLECTOR_SOURCE=gbis_arrival|gbis_location|incheon_arrival|incheon_location`을 각각 선택한다.
`REGIONAL_BUS_TARGET_IDS`는 도착이면 정류소 ID, 위치면 노선 ID의 쉼표 목록이다.
키는 `GBIS_API_KEY`, `INCHEON_API_KEY`; `API_BUDGET_PATH`, `API_DAILY_LIMIT`은 기존 공통 정책을 쓴다.
기본 28,800초는 운영 변경이 아니라 기존 선택 실행 기본값 보존이다. 대상 미설정은 오류다.
응답에 제공자 생성시각이 없으면 `timestampBasis=received`로 표시하고 실제 발생시각이라 부르지 않는다.
GBIS `predictTimeSec`는 초, `predictTime`는 분×60, 인천 `ARRIVALESTIMATETIME`는 초다.
인천 namespace는 `INCHEON_API`이며 `INCHEON_PUBLIC_CSV`로 자동 치환하지 않는다.
인천 도착 Redis 키는 `transit:latest:bus-arrival:INCHEON_API:{route}:{stop}:{vehicle}`이다.
실시간 원문은 transport.raw/Archiver에 남으며 TTL 만료된 Redis가 원문 삭제를 의미하지 않는다.

공식 출처: [경기도 도착 명세](https://www.gbis.go.kr/gbis2014/publicService.action?cmd=mBusArrivalStation),
[경기도 위치 명세](https://www.gbis.go.kr/gbis2014/publicService.action?cmd=mBusLocation),
[인천 도착정보](https://www.data.go.kr/data/15059084/openapi.do),
[인천 위치정보](https://www.data.go.kr/data/15059206/openapi.do). 인천광역시 제공 자료의 출처를 보존한다.

## Spark 실행과 복구

1. `python -m streaming.segment_observations`: `segment.observation`의 평문 FACT_SCHEMA JSON을
   `SEGMENT_FACTS_PATH` Parquet에 원문·partition·offset·수신시각과 보존한다. Kafka 데이터 유실은 실패시킨다.
   **현재 8시간 위치 간 차이나 도착예측을 ACTUAL_TRAVERSAL로 발행하는 생산자는 추가하지 않았다.**
2. `python -m batch.export_segment_registry --help`, `python -m batch.segment_baseline --help`로
   같은 마스터의 구간 목록과 실측 P50/P80/P95 기준선을 준비한다. 업로드는 Spring 내부 API만 사용한다.
3. `python -m streaming.segment_status_live --input <facts> --registry <registry.json> --baseline <baseline>
   --output <run-root> --policy-version <reviewed-policy> --slow-seconds <s> --delayed-seconds <s>
   --min-samples <n> --max-age-seconds <s>`가 매분 입력 유무와 관계없이 stale을 재평가한다.
   최초 facts 파일 sink가 commit되어 있어야 한다. 기본은 파일 준비만; `--publish`가 있어야 Redis/Kafka에 쓴다.
4. 실행별 output/checkpoint를 한 쌍으로 고정한다. 다른 정책·기준선·마스터로 재사용하지 않는다.
   레지스트리/기준선은 한 번 cache/count한다. 배치별 manifest 완성 후만 발행하며 같은 배치는 같은 eventId다.
   Redis ACK 후 Kafka 실패는 재시도로 복구한다. 두 저장소에 분산 원자성을 주장하지 않는다.
   이전 상태는 직전 성공 batch 결과만 사용하고 늦은 Redis 값은 CAS로 거절한다.
5. 기존 `streaming.segment_status`의 고정시각 Replay/준비 후 별도 발행 경로도 유지한다.

`segment.observation`은 기존 원본 envelope와 다른 계약이므로 Archiver 구독을 임의로 추가하지 않는다.
실측 ingest 잡 자체가 원문을 보존한다. 정상 데이터가 없는 상태는 `unknown`이며 0초·정상으로 바꾸지 않는다.

## 도로 대응표와 사건

`backend/tools/road-impact/promote.py --bus <crosswalk.gz> --bus-sha <sha> --reviewed <reviewed.jsonl>
--reviewed-sha <sha> --version <version> --output <road.json>`으로 검토 결과를 확정한다.
검토 행은 기존 road-impact.md의 전체 edge/occurrence, COMPLETE 경로, 방향 검증, evidenceRef,
도로 namespace·linkId, 적용 시작/종료를 갖춰야 한다. 입력 SHA/버스 일치·기간 중복 검증 후만 파일을 쓴다.
기존 24,052개 후보는 자동 승인되지 않는다. 현재 공식 자료만으로 확정하지 못한 적용 기간과
BIS/TOPIS/AccInfo namespace 연결을 임의 추정하지 않는다.

`python -m streaming.road_incidents`는 BUS_CROSSWALK_PATH/SHA256, ROAD_CROSSWALK_PATH/SHA256,
ROAD_REJECTED_PATH와 공통 Kafka/Redis/checkpoint 설정을 요구한다. ROAD_MAX_AGE_SECONDS는 기본600,
허용1~3600이다. 기존 최신 관측 잡과 별도 checkpoint를 쓴다.

Redis `road:incidents:{namespace}:{linkId}`는 사건별 hash다. `road:incident-index:{source/incident SHA}`는
사건의 링크 이동과 순서 역전을 방어한다. TTL은 collectedAt+허용나이까지이며 hash는 사건1,000개 상한,
갱신 시 만료 행 제거, 역순 수집 거절, 동시각 상충 revision은 conflicted로 표시한다.
원문/형식 오류는 Kafka/ROAD_REJECTED_PATH에 남고 `road.impact`는 Redis ACK 후 발행한다.
Redis Cluster는 서로 다른 링크 키를 한 Lua로 옮길 수 없으므로 현재 standalone Redis 계약이다.

Backend 설정: `ddubi.road-impact.path`, `ddubi.road-impact.sha256`,
`ddubi.road-impact.max-age-seconds`(기본600). 설정한 파일 오류는 기동 실패다.
이벤트 Kafka 소비는 기존 `ddubi.recommendation.kafka-enabled=true`에서 두 토픽을 구독한다.
새 토픽 생성·마운트/submit는 인프라 전달 항목이며 이번 작업에서 운영 배포하지 않는다.

카카오 기존 canonical ID는 유지한다. 선택된 mapping의 masterVersion/fromOrder/방향/양 끝점을 확인한다.
현재 사건과 미래 통과 예상 기간이 겹치면 `ROAD_POTENTIAL_IMPACT`, 예상 해제만 지났으면
`ROAD_EXPECTED_CLEAR_UNCONFIRMED`, 링크/피드 부족은 UNKNOWN이다. 차로 전체 통제나 지연초를 생성하지 않는다.
비교의 `fastest`는 최소 ETA 의미를 유지한다. `roadAlternative`는 fastest에 잠재 영향이 있을 때
도로 정보 부족/잠재 영향이 없는 계산 가능한 다른 후보를 별도로 제시한다. 무사고·안전 보장은 아니다.

## 서버에서 확인한 제한

사용자가 열어둔 Chrome JupyterLab에서 2026-09-11 읽기 전용 확인했다.
`/home/j-j15e202/j-moo/experiments/runs/20260908-v12/source_usage.json`에는 날씨 excluded,
사건 reference_only, 대기/실제 이용 정답 no_verified_label로 기록되어 있다.
`/home/j-j15e202/data/extracted/DDUBI_ALL_LINK_DATA_20260903/OA-21285`는 안내 PDF/XLSX/ZIP 3개이며
시점별 실시간 관측 이력이 아니다. TOPIS 과거 속도/승하차 자료는 존재하나 사건 발표·수집시각 및
검증된 영향 구간 연결을 대신하지 못한다. 현재 자료로 새 환경 모델을 실제 학습했다고 표시하지 않는다.

Spark 운영 컨테이너의 임시 crosswalk OFF 해제와 OOM/lag 검증은 아직 하지 않았다.
기존 cache/count 수정은 유지하며 운영 Spark 호스트/배포 경로는 열린 ML Jupyter 서버와 구별해야 한다.
기존 문서 `bus-crosswalk-cache-validation.md`의 로컬 결과를 운영 성능으로 보고하지 않는다.

## 2026-09-13 후속 검증

파일 지하철 마스터의 검증된 버전/SHA를 STAGING 부모 행으로 등록한 뒤 매핑 캐시를 같은
트랜잭션에서 저장한다. 기존 DB 활성 포인터와 수동 상태를 보존하고 동일 버전의 다른 원본은
거절한다. 파일 버전 길이는 기존 DB의 50자 한도에 맞춘다. 실제 MySQL 동시 저장·충돌·EXACT 재조회와
저장된 카카오 응답 20개 재생에서 FK 오류 없이 캐시 저장을 확인했다.

실제 Kafka/Redis와 fixture로 반복 배치, 기준선 계산, Spring 내부 API 업로드/MySQL 활성화까지
확인했다. 도로 자료 재다운로드 감사와 ML 서버 데이터 재확인 결과는
[데이터 담당 보강 결과](data-role-hardening-20260913.md)에 구분했다.
현재는 환경 모델 재학습/운영 도로 매핑/운영 Spark 부하 검증까지 완료한 상태가 아니다.
