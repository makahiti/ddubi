# 추천 데이터 파이프라인 준비 상태

기준 문서는 `docs/MVP_plan/recommendation-correction-work-plan.md`의 DT 항목 17~23이다.
이 문서는 현재 코드에서 실제로 연결된 범위와 운영 전에 더 필요한 자료를 구분한다.

## 지금 연결한 것

### 버스 canonical mapping

Collector와 Spark realtime은 같은 검증 artifact를 사용한다.

- 파일: `backend/transit-master/bus/metropolitan-bus-v6.crosswalk.json.gz`
- 정책: `canonical-adjacent-v2`
- master version: `CSV-V5-34dc30aa763b-KAKAO-REVIEW-V6`
- edge 수: 105,070
- SHA-256: `e7b8db4f3330d7275aa56b4674032d71aa6e34f99bec0c29cee9f7ce002676bd`

Spark는 SHA, 노선/정류장 ID, 인접 순번, canonical segment ID를 검사하고 집계 인덱스를
한 번 cache/count한 뒤 사용한다. 후보가 하나인 인접 edge만 `segmentIds`로 전달한다. 여러
진입 edge, 미매핑, Collector와 Spark의 버전 불일치는 성공으로 바꾸지 않는다.

이 artifact는 정적 인접 edge identity를 확인한다. 추천 경로의 최종 `EXACT` 판정은 Backend가
운행일, 노선, 승하차 정류장, 순서와 방향을 다시 확인한 결과를 사용한다.

### 실제 구간 통과와 상태

`recommendation-data` Compose 프로필에 두 잡을 연결했다.

1. `spark-streaming-segment-observations`는 Kafka `segment.observation`을 원문, partition,
   offset, Kafka 수신시각과 함께 `SEGMENT_FACTS_PATH` Parquet에 저장한다.
2. `spark-streaming-segment-status`는 검증된 registry/baseline과 최근 실측을 매분 평가한다.
   결과 준비가 끝나면 Redis `segment:status:{segmentId}`에 최신값을 쓰고 Kafka
   `segment.status`에 판정 이력을 발행한다.
3. `segment.status`는 Archiver 기본 대상이며 HDFS에도 이력을 남긴다.

실측 계약에는 `vehicleId`, `observedAt`, `enteredAt`, `exitedAt`, `mappingVersion`,
`quality=VERIFIED`, `validUntil`이 필수다. 다음 조건은 격리하고 기준선이나 상태 계산에서 제외한다.

- ETA, 평균, 추정값처럼 `ACTUAL_TRAVERSAL`이 아닌 값
- 검증되지 않은 quality나 빈 차량/매핑 버전
- 등록되지 않은 segment/master 조합
- entered/exited 순서 오류 또는 소요시간과 두 시각의 차이가 1초 넘게 다른 값
- 미래 수집시각, 만료시각 오류, 결측 날씨

현재 버스 위치는 8시간 주기이고 도착값은 예측값이다. 두 값을 실제 통과시간으로 변환하는
생산자는 만들지 않았다. 실제 통과 이벤트 원천이 생기기 전에는 이 프로필을 운영 활성화하지 않는다.

### 모니터링

Grafana의 Kafka 유입/누적 패널과 일일 브리핑에 `segment.observation`을 추가했다. 최근 30분에
실측은 들어오지만 `segment.status`가 생성되지 않으면 경고한다. 이 조건은 실측 생산자가 없는
현재 환경에서는 울리지 않는다. 일일 브리핑에는 `segment:status:*` Redis 키 수와 TTL 오류 수도
표시한다.

현재 exporter에는 mapping EXACT 비율, ML 적용률, 실시간 적용률, fallback/stale 비율을 내보내는
지표가 없다. 존재하지 않는 값을 정상처럼 표시하지 않았으며, 해당 지표는 Backend/Spark가 결과별
counter를 노출한 뒤 대시보드에 연결해야 한다.

## 운영 전에 필요한 자료

### 지하철 시간표

Backend 컨테이너에 아래 환경변수 전달과 read-only transit-master 마운트는 준비했다.

- `DDUBI_SUBWAY_TIMING_TIMETABLE_PATH`
- `DDUBI_SUBWAY_TIMING_DECOMPOSITION_VERIFIED=false`
- `DDUBI_BUS_WAITING_DECOMPOSITION_VERIFIED=false`

저장소에는 평가 계약에 맞게 검증된 공식 JSON 시간표가 아직 없다. 공식 원본으로 운행일, 방향,
열차, 역 순서, provider ID 대응, version/publishedAt/validFrom/validUntil을 검증한 artifact와 SHA가
필요하다. 준비 전에는 경로를 비워 두고 decomposition 플래그를 false로 유지한다.

### 실측, registry, baseline

다음 세 가지가 필요하다.

1. 차량별 인접 정류장 진입/이탈 시각을 신뢰할 수 있게 만드는 실제 관측 생산자
2. 그 생산자와 같은 master version의 segment registry
3. 최소 표본과 관측일 조건을 통과한 요일 × KST 시간대 × 날씨별 baseline

`SEGMENT_REGISTRY_PATH`와 `SEGMENT_BASELINE_PATH`가 비어 있으면 상태 잡은 기동을 거부한다.
기준선은 p50/p80/p95, 표본 수, 관측 날짜 범위와 artifact version을 보존한다. ML 입력도
`feature_available_at <= prediction_base_at`을 만족하는 snapshot만 사용해야 한다.

### 버스 수집 주기

서울 버스 수집기는 최대 100개 대상을 8시간마다 조회한다. 수집기 하나당 최대 300회/일이고,
도착과 위치가 같은 API 키를 쓰면 최대 600회/일이다. 내부 안전 한도 900회에는 재시도 여유가
300회 남지만, 100개 대상을 2분마다 조회하면 수집기 하나만 72,000회/일이어서 현재 한도로는
불가능하다. 실제 통과 관측을 위해 주기를 줄이려면 활성 일정/최근 경로 대상 선정과 공식 쿼터를
먼저 확정해야 한다.

현재 `source.poll-status`에는 상태, rowCount, completeSnapshot, 완료시각과 nextAllowedAt이 남고,
공용 요청 예산은 최근 24시간 호출을 계산한다. 429/인증/timeout은 무한 재시도하지 않는다.

### 도로와 도보

공식 도로 링크와 canonical bus segment 사이의 승인된 crosswalk는 아직 없다. 검토되지 않은
후보를 FULL/EXACT 차단 근거로 올리지 않는다. 도보의 접근/환승/이탈 분해와 provider fallback은
Backend 평가 영역이며, 데이터팀은 공식 정류장/역 endpoint 좌표 artifact가 확보되면 버전과
매핑 품질을 함께 제공한다.

## 실행 순서

1. 실제 통과 생산자를 fixture와 Replay로 검증한다.
2. 같은 버전의 registry를 만들고 baseline을 검토한다.
3. 서버 `.env`에 `SEGMENT_REGISTRY_PATH`, `SEGMENT_BASELINE_PATH`를 설정한다.
4. `spark`, `hdfs`, `recommendation-data`, `archive` 프로필을 함께 기동한다.
5. Kafka offset 증가, facts Parquet, Redis key/TTL, `segment.status`와 HDFS 아카이브 행을 대조한다.
6. Backend 평가 snapshot의 segment/master/baseline/policy version이 발행값과 같은지 확인한다.

체크포인트가 있으면 마지막 처리 offset부터 계속 읽는다. 체크포인트가 처음 만들어질 때만
`SPARK_STARTING_OFFSETS`가 적용된다. 운영 과거 데이터를 다시 처리할 때는 운영 체크포인트를
삭제하지 않고 별도 Replay 토픽과 별도 출력/체크포인트를 사용한다.
