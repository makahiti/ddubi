# DT 담당 작업서 — 조건부 출발 조정

## 2026-09-17 BE 계약 정정

사용자가 지정한 [카카오맵 공식 도보 문서](https://developers.kakao.com/docs/ko/kakaomap/rest-api#walking)를 기준으로 `GET https://dapi.kakao.com/v2/routing/walk`를 사용한다. 이전 제휴 API·service·priority·default_speed 설명은 이 정정으로 대체한다. REST API 키와 `start_x/start_y/end_x/end_y`, WGS84, `route_mode=SHORTEST`를 사용하며 운영 기본값은 OFF다.

- `durationSource`: `KAKAO_WALKING_API`, `PUBLIC_TRANSIT_STEP`, `PROVIDER_TOTAL_FALLBACK`, `ML_PREVIEW`, `TIMETABLE`. 최종 계산에 사용된 출처를 표시한다.
- `endpointEvidence`: nullable 객체. `verified`, `source`, `masterVersion`, `mappingVersion`, `policyVersion`, `distanceMeters`, `coordinateType`, `judgment`, `reason`을 보존한다. HTTP 실패와 좌표 검증 실패는 구분한다.
- `legRole`: `ACCESS_WALK`, `TRANSFER_WALK`, `EGRESS_WALK`, `RIDE`. 승하차 없는 WALK는 표시상 `ACCESS_WALK`로 정규화하고 `WALK_NO_TRANSIT_ENDPOINT`로 별도 API 호출을 생략한다.
- provider 전체시간 fallback에서는 개별 `segments[].durationSeconds=null`이다. 전체 `durationSeconds`를 구간별로 중복 배분하지 않는다.
- `causes`: `eventId`, `source`, `segmentIds`, `effect`, `confirmed`를 최종 BE 계약으로 유지한다. `effect=BLOCKED|POTENTIAL_IMPACT`이며 `confirmed`는 실제 영향 검증 결과다. FE !147 최신 파서는 이 필드들의 호환 읽기를 지원한다. 확인되지 않은 설명·사건 revision은 만들어 넣지 않는다.

기계 계약과 비어 있지 않은 도보·사건 예제는 `docs/contracts/schedule-evaluation-v2.schema.json`, `docs/contracts/examples/conditional-departure/details/`를 따른다. 상세 검증·DT 소비 조건은 `docs/contracts/conditional-departure-be-runtime.md`에 기록한다.

## 1. 목표

조건부 출발 계산이 사용할 수 있도록 버스·지하철·도로·날씨·기준선 데이터를 원천 의미를 보존한 채 수집·정규화·버전화하고, Backend/ML이 freshness와 품질을 기계적으로 판정할 수 있는 Kafka/Redis/artifact 계약을 제공한다.

공통 계약은 `docs/MVP_plan/conditional-departure-integration.md`를 따른다.

## 2. 담당 범위

### 포함

- Collector source, 호출 범위, 예산, retry, poll 결과
- Kafka raw/normalized/status event 계약
- Spark batch/streaming 정규화와 Redis sink
- HDFS/Parquet 원본·Silver·manifest
- 버스/지하철/도로 canonical mapping과 crosswalk artifact
- 버스 정류장·지하철 역 좌표의 출처·버전·좌표 의미 artifact
- sourceGeneratedAt/collectedAt/freshness/quality/version
- 실제 구간 통과 데이터와 ML feature snapshot
- fixture, schema, replay, 소스별 성공/실패 통계

### 제외

- 일정 DB와 추천 decision
- Backend route 저장이나 outbox 구현
- Flutter 변경
- Redis 값이 없을 때 정상으로 판정
- 예측 ETA를 실제 구간 통과 정답으로 생성
- 이름만 일치하는 노선/역/도로를 EXACT로 승격
- 카카오모빌리티 제휴 도보 API 호출과 도보시간 계산
- 운영 키·쿼터 확인 없이 poll 주기를 임의 단축

BE/ML에게는 접근·이탈 도보 endpoint 검증에 필요한 좌표 필드와 허용 가능한 품질 수준을 먼저 확인한다.

## AI 실행 절차

### 0단계 — 시작 전 확인

1. DT 공식 작업 branch, HEAD, upstream과 `git status`를 확인한다. PM에서 직접 작업하거나 PM을 push하지 않는다.
2. `collector/`, `sparkjobs/`, `docs/contracts/`, 데이터 artifact의 기존 수정·미추적 파일을 구분한다.
3. source별 현재 호출 주기, 설정 우선순위, request budget, retry, raw topic, Spark job, Redis sink, archive 경로를 표로 만든다.
4. Bus Silver, subway policy, road impact, weather, segment status의 현재 schema와 테스트를 읽는다.
5. 기존 원본 fixture의 실제 encoding, delimiter, field, time 의미를 확인한다.
6. 관련 Collector/Spark 테스트를 실행해 baseline을 기록한다.

시작 산출물:

- source→Collector→Kafka→Spark→Redis/HDFS 흐름
- 현재/추가 필드 차이
- 기존에 확보된 원천과 아직 없는 원천
- BE/ML에 제공 가능한 fixture·master·좌표 목록
- 승인 쿼터를 확인해야 하는 source 목록

### 1단계 — source 계약과 fixture

1. 각 source마다 정상, 정상 빈 응답, 부분 응답, 오류, timeout/429, 시각 누락 fixture를 준비한다.
2. raw fixture에서 비밀키·개인정보를 제거하되 필드 구조와 의미는 바꾸지 않는다.
3. raw→normalized 필드, 단위, timezone, provider ID 의미를 표로 고정한다.
4. 공통 envelope와 `source.poll-status` schema를 작성한다.
5. fixture와 schema validation test를 먼저 추가한다.

1단계 통과 기준:

- 모든 fixture가 schema를 통과하거나 rejection reason으로 분류됨
- SUCCESS/PARTIAL/FAILED/RATE_LIMITED/SKIPPED가 구분됨
- sourceGeneratedAt, occurredAt, collectedAt을 서로 바꾸어 쓰지 않음
- 키와 사용자 정보가 fixture/log에 없음

### 2단계 — 마스터·crosswalk·좌표

1. BUS route/stop/order/occurrence와 SUBWAY line/station/direction/service pattern의 공식 원천을 확인한다.
2. raw master를 보존하고 normalized artifact를 별도로 만든다.
3. EXACT/PARTIAL/AMBIGUOUS/UNMAPPED 규칙과 근거 필드를 구현한다.
4. 정류장·역 좌표에 coordinateType, source, version, validity를 추가한다.
5. 같은 이름, 반대 방향, 순환 occurrence, 역 중심/출입구를 구분하는 fixture를 만든다.
6. artifact manifest에 원본 SHA, 생성 명령, row 수, accepted/rejected 수를 기록한다.

2단계 통과 기준:

- 같은 입력과 버전으로 같은 artifact hash 생성
- 이름만 같은 대상은 EXACT가 되지 않음
- 좌표 누락/불명확이 null/UNKNOWN으로 보존됨
- BE/ML이 별도 이름 추측 없이 ID와 좌표 의미를 읽을 수 있음

### 3단계 — Collector

1. 기존 source adapter의 응답 상위 오류와 page completeness 검증을 먼저 보강한다.
2. provider 발생시각을 파싱하고 timestampBasis를 지정한다.
3. source별 retry와 request budget을 공통 정책에 연결한다.
4. poll 결과를 `source.poll-status`로 발행한다.
5. 같은 사건/관측의 안정 identity와 content revision을 분리한다.
6. 늦은 옛 응답이 최신 상태를 덮지 않도록 downstream 비교 필드를 제공한다.

3단계 통과 기준:

- 빈 응답과 장애를 구분하는 테스트 통과
- 부분 page를 complete snapshot으로 표시하지 않음
- 호출 예산 초과 시 무한 대기나 폭주 재시도 없음
- 기존 source 기본 동작 회귀 없음

### 4단계 — 정규화·Spark·Redis

1. raw topic을 기존 Silver 공통 컬럼으로 정규화한다.
2. 없는 provider 필드는 null로 유지하고 비슷한 다른 필드로 채우지 않는다.
3. accepted/rejected/duplicate/conflict를 별도 집계한다.
4. event time과 processing time을 분리하고 watermark/late event 정책을 테스트한다.
5. Redis 값에 source time, collected time, validity, mapping/master version, quality를 포함한다.
6. Redis latest 순서 비교가 수집시각과 원천시각을 혼동하지 않게 한다.
7. 변경 이벤트는 Redis write 성공 후 발행한다.

4단계 통과 기준:

- 늦은 event가 최신값을 덮지 않음
- Redis key 부재/만료가 정상 상태로 변환되지 않음
- TTL과 소비 freshness가 별도 설정·필드로 검증됨
- 동일 입력 replay 결과가 결정적임

### 5단계 — source별 의미 검증

버스:

- mkTm+exps 절대 ETA 의미, vehicle ID, full/detour/terminal code와 occurrence를 보존한다.
- sparse 위치를 실제 통과 정답으로 만들지 않는다.

지하철:

- recptnDt+barvlDt, train/line/station ID, serviceDate, 방향·행선지를 보존한다.
- 정적 trainCode와 realtime trainId를 자동 결합하지 않는다.

도로:

- incident identity/revision, namespace, 방향, 적용시간, FULL/PARTIAL을 분리한다.
- 미등장이나 expected clear 경과만으로 종료 확정하지 않는다.

날씨·기준선:

- 결측 날씨를 DRY로 채우지 않는다.
- 같은 segment/dow/hour/weather/version에서만 baseline을 비교한다.
- p50<=p80<=p95와 sample quality를 검증한다.

실측/ML feature:

- ACTUAL_TRAVERSAL과 ETA/평균을 분리한다.
- `feature_available_at <= prediction_base_at`을 강제한다.

5단계 통과 기준:

- 버스·지하철 ETA 계산 fixture의 절대시각이 수집지연을 중복 반영하지 않음
- 도로 FULL/PARTIAL·방향·시간 범위가 서로 구분됨
- baseline 조건과 다른 날씨·시간대·version이 섞이지 않음
- ETA나 sparse 위치에서 ACTUAL_TRAVERSAL이 생성되지 않음
- 미래에 알게 된 feature가 학습·online snapshot에 들어가지 않음

### 6단계 — BE/ML 전달과 소비 테스트

1. source별 전달 패키지를 고정 경로에 만든다.
2. schema, raw fixture, normalized fixture, Redis 예제, artifact manifest를 한 version으로 묶는다.
3. BE/ML 담당에게 key, nullable, freshness, version, mapping status 의미를 설명한다.
4. BE/ML의 소비 DTO/schema test가 실제 DT fixture를 통과하는지 함께 확인한다.
5. 계약 차이는 DT가 필드를 임의 변경하지 말고 양쪽 문서/schema를 같은 변경에서 갱신한다.

6단계 통과 기준:

- BE/ML이 별도 parser나 이름 추측 없이 fixture 소비
- fixture→normalized→Redis 예제의 ID·시각·version 추적 가능
- 좌표 artifact가 path endpoint 검증에 사용 가능
- 미확보 데이터의 fallback이 문서화됨

### 7단계 — 운영 전 검증과 완료 보고

1. 실제 승인 쿼터, scope 수, page 수로 하루 호출량을 계산한다.
2. source age, 성공/부분/실패/429, budget 잔여량을 측정한다.
3. HDFS/Parquet의 실제 파일, row 수, partition, manifest, SHA를 확인한다.
4. replay 결과 hash와 rejected reason별 건수를 저장한다.
5. 운영에 아직 넣을 수 없는 source와 허용 fallback을 분리한다.

AI 완료 보고 형식:

| 항목 | 보고 내용 |
|---|---|
| 기준 | branch, HEAD, source/master/schema version |
| 변경 | Collector/Spark/schema/artifact 파일별 설명 |
| 원천 | URL·확인일·범위·시각·단위·쿼터 |
| 품질 | accepted/rejected/duplicate/conflict와 이유 |
| 저장 | Kafka key, Redis key/TTL, HDFS/Parquet row·partition·SHA |
| 검증 | pytest/Spark/replay/BE 소비 테스트 결과 |
| 남음 | 미확보 원천·매핑·좌표·실측과 fallback |

DT 범위를 넘어 Backend DB/API, Flutter, 외부 제휴 도보 client를 직접 수정하지 않는다. 필요한 변경은 schema 필드와 소비 목적을 handoff로 남긴다.

## 3. 공통 원본 envelope

모든 source는 기존 envelope를 유지하면서 아래 필드를 제공하거나 명시적으로 null/unknown 처리한다.

| 필드 | 규칙 |
|---|---|
| eventId | 발행 이벤트 식별. 영속 사건 identity와 구분 |
| eventType/source | 기존 계약 값 유지 |
| occurredAt | 제공자 발생/생성시각. 없으면 수신 기준임을 표시 |
| collectedAt | 실제 수집시각 |
| sourceGeneratedAt | 원천 생성시각, 없으면 null |
| timestampBasis | PROVIDER_GENERATED / PROVIDER_OCCURRED / RECEIVED_ONLY |
| providerNamespace/IDs | 문자열로 보존 |
| segmentIds | 검증된 canonical 대응만 기록 |
| mappingVersion/masterVersion | 대응에 사용한 버전 |
| contentHash | UUID·수집시각·키를 제외한 정규화 내용 hash |
| qualityFlags | 사용 제한 사유 |
| missingFields | 원천 누락 필드 |
| rejectionReasons | 거부된 이유 |

같은 사건 재수집은 `source + providerIncidentId` 또는 source별 안정 identity로 연결하고 내용 변경은 revision으로 관리한다. 늦게 도착한 오래된 revision이 최신 상태를 덮지 않게 한다.

## 4. poll 상태 계약

추가 `source.poll-status` event/schema를 제공한다.

필수 필드:

- `pollId`, `source`, `scopeKey`
- `startedAt`, `finishedAt`
- `status`: `SUCCESS`, `PARTIAL`, `FAILED`, `RATE_LIMITED`, `SKIPPED_NON_SERVICE`
- `completeSnapshot`
- `pageCount`, `rowCount`
- `errorCode`, `nextAllowedAt`

규칙:

- 모든 페이지와 전체 건수 검증에 성공한 경우만 `completeSnapshot=true`다.
- 정상 빈 전체 목록, 한 페이지 빈 응답, 부분 성공, 호출 실패를 구별한다.
- 원천이 활성 사건 전체 목록임을 보장하지 않으면 미등장을 사건 종료로 사용하지 않는다.
- 새 topic/schema는 `topics.yaml`, events schema, archiver 구독과 같은 변경 단위로 제공한다.

## 5. 버스

### 5.1 수집

대상:

- `collector/src/sources/bus_arrival.py`
- `collector/src/sources/bus_location.py`
- 지역별 adapter/config
- `collector/src/request_budget.py`

도착 원천에서 보존할 값:

- 호출 `busRouteId`
- `stId`, `staOrd`, `mkTm`
- `exps1/2`, `traTime1/2`
- `vehId1/2`, `sectOrd1/2`
- `isArrive1/2`, `full1/2`, `deTourAt`
- `arrmsg1/2`

위치 원천에서 보존할 값:

- route/vehicle ID
- `sectOrd`, `sectDist`, `fullSectDist`
- `congetion`, `stopFlag`, 방향/정류장 순서
- 원천 위치와 생성시각

주의:

- `exps`와 `traTime`의 단위를 섞지 않는다.
- `sectOrd`를 남은 정류장 수로 바꾸지 않는다.
- sparse 위치와 ETA로 실제 통과 시간을 생성하지 않는다.
- 순환 노선은 같은 정류장 재등장 occurrence를 구분한다.
- 만차·우회·종점 코드를 숫자 ETA와 별개로 보존한다.

### 5.2 Silver와 Redis

기존 `sparkjobs/common/bus_normalizer.py`의 COMMON/POSITION/ARRIVAL 구조를 기준으로 확장한다.

필수 공통 컬럼:

- silverVersion, eventId, eventType, source
- providerNamespace, providerRouteId, providerStopId, providerVehicleId
- observedAt, collectedAt, archiveDate, timestampBasis
- claimedSegmentIds, claimedMappingJson
- rawPayloadJson, missingFields, qualityFlags, rejectionReasons, status

Redis 예제와 schema를 함께 제공한다. `_latestOrderAt`은 수집 순서 비교용이며 관측시각으로 사용하지 않는다. TTL과 소비 freshness 120초는 별개의 값으로 문서화한다.

## 6. 지하철

### 6.1 정적 시간표 artifact

`backend/tools/transit-timing/build_timetable.py` 자체 구현은 BE 소유지만, DT는 원본·manifest·crosswalk 입력을 제공한다.

필수 manifest:

- 원본 SHA-256
- 공식 배포일, 적용일, validFrom/validUntil
- 운영자/노선 범위
- CP949 등 실제 encoding
- service calendar/dayType 근거
- limitations

정적 trip은 serviceDate, line, direction, train type, station sequence, arrival/departure를 보존하며 24~29시를 허용한다.

### 6.2 실시간

도착:

- line/station/train ID
- `recptnDt`, `barvlDt`, `arvlCd`
- `arvlMsg2/3`, 방향, 행선지, train type

위치:

- line/train ID, direction, destination
- station, train status, source time

`recptnDt + barvlDt`가 절대 ETA다. collectedAt/now를 다시 더하지 않는다.

### 6.3 trip crosswalk

필수 컬럼:

- serviceDate
- staticTripKey
- providerLineId/providerTrainId
- direction/destinationStationId/trainType
- validFrom/validUntil
- evidenceRef
- matchStatus: EXACT/AMBIGUOUS/UNMAPPED
- crosswalkVersion

날짜·방향·행선지·정차 순서가 유일하게 일치할 때만 EXACT다. 정적 trainCode를 실시간 trainId로 복사하지 않는다.

## 7. 도로 사건

대상:

- `collector/src/sources/traffic_incident.py`
- `sparkjobs/common/road_impact.py`
- `sparkjobs/streaming/road_incidents.py`

필수 출력:

- source/provider incident identity와 revision
- 발생/공지/적용 시작·종료·예상 해제 시각
- 통제 수준 FULL/PARTIAL/UNKNOWN
- 적용 수단과 방향
- provider road link namespace/ID
- canonical edge mapping과 occurrence
- evidence level, mapping version
- active/stale/ended 상태와 판정 근거

규칙:

- 숫자가 같은 link라도 namespace가 다르면 동일 link로 보지 않는다.
- 반대 방향과 시간 범위 밖 사건은 영향 edge로 확정하지 않는다.
- PARTIAL을 FULL로 승격하지 않는다.
- expectedClearAt 경과만으로 종료 확정하지 않는다.
- poll 실패로 목록이 비어도 정상/해제로 바꾸지 않는다.
- Redis write 성공 후 변경 event를 발행한다.

## 8. 승·하차 좌표 artifact

BE/ML이 접근·이탈 도보 endpoint를 검증할 수 있도록 BUS 정류장과 SUBWAY 역 좌표에 다음을 제공한다.

- providerNamespace, providerRouteId/lineId, providerStopId/stationId
- canonical node ID와 occurrence/방향
- longitude, latitude와 좌표계
- coordinateType: STOP_POINT/PLATFORM/ENTRANCE/STATION_CENTER/UNKNOWN
- 공식 출처와 원본 파일·행 참조
- masterVersion, validFrom/validUntil
- mappingStatus와 검토 근거

역 중심점만 있는 자료를 출입구 좌표라고 부르지 않는다. 같은 이름의 다른 역·정류장이나 반대 방향 좌표를 합치지 않는다. 순환 버스 노선의 동일 정류장 재등장과 여러 승강장을 occurrence/방향으로 구분한다. 좌표가 없거나 의미가 불명확하면 null/UNKNOWN으로 남긴다.

DT는 카카오 path endpoint와의 최종 거리 허용값을 임의 확정하지 않는다. 비교 가능한 공식 좌표와 corpus를 제공하고, BE/ML이 `walking-endpoint-policy`에서 임계값과 적용 결과를 버전화하도록 한다.

## 9. 날씨·기준선·segment status

### 날씨

- 원천 격자/관측시각/발표시각/수집시각을 보존한다.
- DRY/RAIN/SNOW bucket 변환 규칙을 버전화한다.
- 결측을 DRY로 채우지 않는다.

### 기준선

grouping:

`segmentId × dow × hourSlot × weatherBucket × dataVersion`

출력:

- p50/p80/p95 seconds
- sampleCount
- periodFrom/periodUntil
- computedAt
- dataVersion/masterVersion
- 품질/거부 사유

검증:

- p50 <= p80 <= p95
- dow 의미 명시
- 모든 chunk 업로드 성공 후에만 버전 활성화
- Spark가 서비스 DB에 직접 쓰지 않음

### segment status

- 실제 통과 표본의 최근 median과 같은 조건의 baseline을 비교한다.
- 출력은 segmentId, status, delaySeconds, confidence, computedAt, validUntil, source, causes다.
- stale/결측은 UNKNOWN이다.
- Backend가 이미 ML/실시간 이동시간을 썼다면 delay를 다시 더하지 않도록 source/semantics를 명시한다.

## 10. 실제 구간 통과와 ML feature

실측은 기존 `docs/contracts/segment-observation.schema.json`을 확장 또는 버전 변경해 사용한다.

ACTUAL_TRAVERSAL 조건:

- 동일 차량
- 동일 운행
- 검증된 인접 canonical segment
- 올바른 순서
- 시작/종료 원천시각
- 양의 duration
- prediction base 시점에 사용 가능한 feature만 결합

ETA, 시간대 평균, sparse 위치만으로 ACTUAL_TRAVERSAL을 생성하지 않는다.

ML 전달 manifest:

- dataset/schema/master/crosswalk version
- source 기간과 partition
- row 수, accepted/rejected 수
- feature별 availableAt 의미
- label 생성 규칙
- 중복/충돌 처리
- train/validation/test 시간 분리
- 날씨/사건 결합의 시간 겹침 규칙
- 파일 SHA-256과 생성 명령

BE/ML이 사용할 online feature snapshot은 `featureSnapshotVersion`, computedAt, validUntil, missing/quality를 포함한다.

## 11. 호출 예산과 스케줄

현재 기본 주기가 실시간 요구를 충족한다고 가정하지 않는다. 각 source에 대해 다음을 실측·제출한다.

- 승인된 일/초 호출 한도
- scope 수와 페이지 수
- 정상 poll 간격
- retry를 포함한 하루 예상 호출 수
- 429/timeout/부분 성공 비율
- source age p50/p95/max
- 키 그룹별 예산 잔여량

Backend가 제공하는 활성 scope demand가 생기면 개인정보를 제외한 `scopeKey, source, providerNamespace, providerId, neededFrom, neededUntil, priority, expiresAt`만 사용한다.

환경변수만 줄여도 코드의 `max(기본, 설정)` 때문에 주기가 내려가지 않는 경로를 확인한다. TTL을 늘려 freshness 문제를 숨기지 않는다. 승인 쿼터를 확인하지 않고 RequestBudget 기본값을 올리지 않는다.

## 12. BE/ML 전달 패키지

source별로 다음을 한 묶음으로 전달한다.

1. 비밀값을 제거한 정상/빈/오류/누락/순서역전 raw fixture
2. raw→normalized 컬럼 표와 단위·코드표
3. JSON/Avro 등 기계 schema와 호환성 정책
4. Kafka topic/key 예제
5. Redis key/value/TTL 예제
6. mapping/master/crosswalk artifact와 manifest/SHA
7. 정류장·역 좌표 artifact와 coordinateType·출처·버전·누락 통계
8. complete/partial/failed poll 예제
9. source freshness와 호출 예산 보고
10. accepted/rejected/reason별 건수
11. replay 실행법과 동일 입력 결과 hash

BE/ML 소비 테스트가 사용할 fixture 경로를 고정하고 파일 이름을 임의로 바꾸지 않는다.

## 13. 테스트

### Collector

- 정상, 빈 응답, 상위 오류, 부분 page, timeout, 429
- key masking
- 원천시각 파싱과 timezone
- bus 첫째/둘째 차량 필드
- subway INFO-200과 장애 구분
- 새벽 skip과 특별 날짜 override
- incident revision/dedup
- request budget/retry

### Spark/Redis

- bus Silver accepted/rejected/duplicate/conflict
- 늦은 event가 최신값을 덮지 않음
- Redis TTL과 validUntil 분리
- crosswalk EXACT/AMBIGUOUS/UNMAPPED
- 정류장·역 좌표의 방향/occurrence/coordinateType과 누락 처리
- road 반대 방향/시간 밖/PARTIAL/FULL
- weather 결측과 bucket
- baseline percentile 순서와 sample quality
- segment status stale/unknown

### Replay

- 같은 원본+버전으로 동일 normalized 결과
- 같은 source time의 내용 충돌 보존
- poll 실패 후 사건 자동 종료 금지
- feature_available_at가 prediction_base_at 이후면 학습/online feature에서 제외

실행은 변경 파트에 맞춰 수행한다.

```text
python -m pytest -q
```

Spark 통합 테스트는 고정 입력, checkpoint, output 위치와 실행 환경을 함께 기록한다.

## 14. 완료 산출물

- 변경 코드 및 source별 테스트
- topics/schema/Redis 계약
- source별 fixture 패키지
- master/crosswalk/baseline/feature manifest
- 승·하차 좌표 artifact와 출처·버전·품질 보고
- 호출 성공·부분·실패·429·예산 통계
- HDFS/Parquet 저장 경로와 row/partition/SHA 검증
- replay 결과와 동일성 hash
- 아직 확보되지 않은 데이터와 그때의 허용 fallback 목록

## 15. DT 완료 조건

- BE/ML이 이름 추측이나 별도 parser 없이 fixture를 소비할 수 있다.
- sourceGeneratedAt과 collectedAt이 분리된다.
- 정상 빈 응답, 실패, 부분 성공, 비운행 생략이 구분된다.
- canonical EXACT와 미매핑 이유가 버전과 함께 제공된다.
- BE/ML이 path endpoint를 검증할 수 있는 공식 좌표와 좌표 의미를 받는다.
- 실제 통과, 예측 ETA, 기준선이 서로 다른 데이터로 유지된다.
- stale/unknown을 정상으로 만들지 않는다.
- 운영 키와 개인정보가 fixture/log/artifact에 없다.
- 지원하지 못하는 지역·시간·노선 범위가 manifest에 명시된다.
