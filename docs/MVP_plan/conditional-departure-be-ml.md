# BE/ML 담당 작업서 — 조건부 출발 조정

## 2026-09-17 BE 계약 정정

사용자가 지정한 [카카오맵 공식 도보 문서](https://developers.kakao.com/docs/ko/kakaomap/rest-api#walking)를 기준으로 `GET https://dapi.kakao.com/v2/routing/walk`를 사용한다. 이전 제휴 API·service·priority·default_speed 설명은 이 정정으로 대체한다. REST API 키와 `start_x/start_y/end_x/end_y`, WGS84, `route_mode=SHORTEST`를 사용하며 운영 기본값은 OFF다.

- `durationSource`: `KAKAO_WALKING_API`, `PUBLIC_TRANSIT_STEP`, `PROVIDER_TOTAL_FALLBACK`, `ML_PREVIEW`, `TIMETABLE`. 최종 계산에 사용된 출처를 표시한다.
- `endpointEvidence`: nullable 객체. `verified`, `source`, `masterVersion`, `mappingVersion`, `policyVersion`, `distanceMeters`, `coordinateType`, `judgment`, `reason`을 보존한다. HTTP 실패와 좌표 검증 실패는 구분한다.
- `legRole`: `ACCESS_WALK`, `TRANSFER_WALK`, `EGRESS_WALK`, `RIDE`. 승하차 없는 WALK는 표시상 `ACCESS_WALK`로 정규화하고 `WALK_NO_TRANSIT_ENDPOINT`로 별도 API 호출을 생략한다.
- provider 전체시간 fallback에서는 개별 `segments[].durationSeconds=null`이다. 전체 `durationSeconds`를 구간별로 중복 배분하지 않는다.
- `causes`: `eventId`, `source`, `segmentIds`, `effect`, `confirmed`를 최종 BE 계약으로 유지한다. `effect=BLOCKED|POTENTIAL_IMPACT`이며 `confirmed`는 실제 영향 검증 결과다. FE !147 최신 파서는 이 필드들의 호환 읽기를 지원한다. 확인되지 않은 설명·사건 revision은 만들어 넣지 않는다.

기계 계약과 비어 있지 않은 도보·사건 예제는 `docs/contracts/schedule-evaluation-v2.schema.json`, `docs/contracts/examples/conditional-departure/details/`를 따른다. 상세 검증·DT 소비 조건은 `docs/contracts/conditional-departure-be-runtime.md`에 기록한다.

## 1. 목표

사용자 출발 계획 P를 먼저 평가하고, `ETA >= D`일 때만 `T=D-10분`을 목표로 실행 가능한 출발과 경로를 탐색한다. 계산, 저장, 수락, 재평가, 모델 fallback과 알림 전송을 버전·최신성·품질 근거와 함께 제공한다.

공통 계약은 `docs/MVP_plan/conditional-departure-integration.md`를 따른다.

## 2. 현재 코드에서 반드시 보존할 것

- `plannedDepartAt`, `arriveBy`와 `DEPARTURE_REQUIRED`
- 후보 TTL과 선택 경로 signature 기반 재연결
- `input_updated_at`, lease token/until, event revision의 stale 결과 차단
- route/route_segment/route_segment_edge 저장 구조
- PREVIEW 결과 저장 허용과 수락 차단
- Candidate dedup, ML/API/subway 출처·fallback 이유
- 기존 대중교통 WALK와 provider 전체시간. 별도 도보 보강 전에도 사용할 수 있어야 한다.
- 기존 notification planner/dispatcher의 동작과 기본 자동알림 OFF
- 기존 Flyway migration. 수정하지 말고 새 migration을 추가한다.

현재 Workflow의 `suggestion.proposed == comparison.fastest`는 임시 호환 동작이다. 새 정책 구현 후 제거하거나 v1 adapter 내부로 제한한다.

## AI 실행 절차

### 0단계 — 시작 전 확인

1. BE/ML 공식 작업 branch, HEAD, upstream과 `git status`를 확인한다. PM에서 직접 작업하거나 PM을 push하지 않는다.
2. `backend/`, `ml-serving/`, migration과 transit resource의 기존 수정·미추적 파일을 구분한다.
3. 아래 실제 호출 흐름을 코드에서 끝까지 추적한다.
   - 일정 생성·수정 DTO → Schedule entity/service
   - recommendation controller → `ScheduleEvaluationWorkflow`
   - route search → mapper → saved route/segment/edge
   - `RouteEvidenceAssembler` → `MlRoutePreview` → `RouteTimelineEvaluator`
   - `ScheduleEvaluationStore` claim/complete/latest/accept/stop
   - notification planner → dispatcher → notification/FCM
   - Backend ML client → `ml-serving` request/response/manifest
4. 기존 `DepartureSolver`, `DepartureSuggestionService`, `RecommendationService`의 실제 호출자를 확인한다. 이름만 보고 삭제하지 않는다.
5. 현재 관련 Backend와 ML 테스트를 실행해 baseline을 기록한다.

시작 산출물:

- 현재 endpoint→workflow→DB/외부 호출 흐름
- 재사용/교체/신규 파일 목록
- 필요한 migration 후보 번호
- FE에 먼저 줄 fixture 목록
- DT에 요청할 Redis/Kafka/master/좌표 필드 목록

### 1단계 — 공통 계약을 코드보다 먼저 고정

1. `schedule-evaluation-v2` schema와 enum을 만든다.
2. T01~T08과 도보 T27~T34에 해당하는 고정 clock JSON fixture를 만든다.
3. v1 result JSON을 읽는 호환 fixture를 보존한다.
4. FE가 fixture만으로 개발할 수 있게 request, success, error, notification payload를 전달한다.
5. DT fixture를 읽는 최소 DTO/schema validation test를 만든다.

1단계 통과 기준:

- JSON schema validation 통과
- FE와 필드명·nullable·enum 합의
- DT와 key/schema/version/freshness 의미 합의
- 제품 계산 코드를 바꾸지 않고도 fixture contract test 통과

### 2단계 — 외부 I/O 없는 순수 정책

1. `ConditionalDeparturePolicy`를 외부 API·DB·clock 직접 참조 없이 구현한다.
2. `ETA < D`, `ETA == D`, `ETA <= T`, 과거 P, 정보 부족, 탐색 미완료를 테스트로 먼저 고정한다.
3. 기존 evaluator의 `ON_TIME_ESTIMATE <= D` 의미는 바꾸지 않는다.
4. policy는 P 결과를 받아 KEEP 또는 T 탐색 요구를 반환하고, 탐색 자체는 수행하지 않는다.
5. `bestKnownDecision`, `departureMissed`, buffer와 target을 명시적으로 반환한다.

2단계 통과 기준:

- 외부 Spring context 없이 순수 단위 테스트 통과
- D-1초/D/T 경계 테스트 통과
- 10분 이중 가산 없음

### 3단계 — 여정 계산과 제한 탐색

1. 기존 `MlRoutePreview`의 실제 계산 부분을 `JourneyEvaluationService`로 추출한다.
2. snapshot과 평가 session을 한 번 만들고 후보·출발시각 평가가 이를 공유하게 한다.
3. 출발시각마다 차량·대기·hourSlot·환승을 다시 선택한다.
4. `DepartureSuggestionService`가 P 평가와 T 후보 탐색을 분리해서 공통 evaluator를 호출하게 한다.
5. 시간표 경계 후보와 제한 격자를 합치고 평가 수·시간 예산을 적용한다.
6. 예산 종료 시 성공처럼 반환하지 않고 SEARCH_INCOMPLETE로 남긴다.

3단계 통과 기준:

- 동일 fixture에서 직접 정방향 평가와 탐색 재평가 결과 일치
- 출발시각 변화 시 버스/열차/환승 조합 변화 테스트 통과
- 같은 snapshot/model version 유지
- 탐색 budget과 lease 시간 안에서 종료

### 4단계 — 접근·이탈 도보 보강

1. 기존 대중교통 fixture로 WALK 위치와 transit step 전후 관계를 확인한다.
2. `RouteLegRoleClassifier`의 역할 분류 테스트를 먼저 작성한다.
3. DT 좌표 artifact fixture를 읽고 `WalkingEndpointResolver`의 EXACT/불일치/중심점 테스트를 작성한다.
4. `WalkingRouteClient` port와 fake client를 먼저 구현한다.
5. 실제 공식 제휴 응답 fixture를 확보한 뒤에만 `KakaoWalkingResponse`와 adapter를 구현한다.
6. enabled=false, 403, 429, timeout, 빈/깨진 응답을 후보별 fallback으로 연결한다.
7. 원본 WALK 교체와 provider total 비용범위 판정을 `WalkingEvidence`에 기록한다.
8. 선택/상위 후보 우선 호출 budget과 cache를 session에 연결한다.

4단계 통과 기준:

- ACCESS와 EGRESS 요청 방향 테스트 통과
- TRANSFER WALK는 별도 도보 client를 호출하지 않음
- 원본 WALK와 별도 WALK 중복 합산 없음
- unassigned 비용이 불명확하면 별도 WALK를 total에 추가하지 않음
- 제휴 기능 OFF/장애에서도 기존 경로 검색 성공

실제 제휴 권한이나 응답 fixture가 없으면 adapter의 운영 활성화를 멈추고 port, fake, fallback, 설정까지만 구현한다. 응답 필드를 추측하지 않는다.

### 5단계 — Workflow 연결

1. claim 이후 후보와 immutable snapshot을 확보한다.
2. effective P에서 모든 후보를 평가한다.
3. 순수 policy가 요구한 경우에만 T 탐색을 실행한다.
4. 기존 선택 경로와 proposed 경로를 구분한다.
5. 결과에 quality, causes, search, versions, validity, walking coverage를 붙인다.
6. complete 직전에 lease, input revision, event revision, journey state를 재검증한다.
7. 기존 v1 응답은 adapter에서만 유지하고 신규 내부 모델을 v1 Map에 맞추지 않는다.

5단계 통과 기준:

- 현재 Workflow의 `proposed == fastest` 임시 경로 제거 또는 v1 한정
- T01~T08 통합 테스트 통과
- stale 계산 결과 저장 거부
- PREVIEW 결과 저장과 수락 차단 모두 유지

### 6단계 — DB와 API

1. 현재 최신 Flyway 번호를 확인한 뒤 forward-only migration을 추가한다.
2. migration과 entity/DTO/repository 변경을 한 단계에서 맞춘다.
3. 조회 전용 `/routes/evaluate`를 공통 계산기에 연결하되 DB를 변경하지 않는지 테스트한다.
4. 일정 재평가/latest/accept/departure를 v2 결과와 revision에 연결한다.
5. 409/422/429/503 오류 code를 fixture와 일치시킨다.
6. 구버전 JSON과 Client를 위한 adapter 테스트를 유지한다.

6단계 통과 기준:

- migration을 빈 DB와 기존 schema upgrade 양쪽에서 검증
- API contract test 통과
- accept 멱등·최신성·production eligibility 검증
- 조회 전용 API가 일정/추천 row를 생성하지 않음

### 7단계 — Scheduler·사건·알림

1. 입력 수정, 사건 revision, 등록 직후 wake와 DB due 복구를 연결한다.
2. bounded worker, claim batch, lease 만료와 늦은 완료를 테스트한다.
3. 기존 inline 알림과 새 outbox의 이행 방식을 정하고 이중 발송을 막는다.
4. 평가 저장과 outbox insert를 한 트랜잭션으로 묶는다.
5. FCM은 트랜잭션 밖에서 호출하고 성공 후에만 SENT 처리한다.
6. 출발·취소·도착·stale이면 대기 알림을 취소한다.

7단계 통과 기준:

- T16~T24 동시성·알림 fixture 통과
- 재시작 후 due/outbox 복구
- 실패를 전송 성공으로 기록하지 않음
- 같은 의미의 알림 중복 억제

### 8단계 — 실제 연동과 완료 보고

1. DT fixture와 실제 Redis/Kafka sample의 schema 차이를 비교한다.
2. ML service의 manifest, 지원일, model version, production flag를 실제 응답과 대조한다.
3. FE fixture와 실제 Backend 응답을 diff한다.
4. PM에서는 각 파트 변경을 가져온 뒤 고정 clock E2E와 실제 화면을 검증한다.

AI 완료 보고 형식:

| 항목 | 보고 내용 |
|---|---|
| 기준 | branch, HEAD, migration baseline, schemaVersion |
| 변경 | 파일별 역할과 기존 코드 재사용 여부 |
| 계약 | FE fixture, DT 입력, ML/도보 외부 계약 version |
| 검증 | Maven/pytest/contract/integration 테스트 결과와 개수 |
| 운영 | feature flag, 기본값, 권한·쿼터·cache·timeout |
| 호환 | v1 JSON, 기존 WALK, provider total, PREVIEW 처리 |
| 남음 | 실제 권한/데이터/성능/수동 검증과 담당자 |

BE/ML 범위를 넘어 Collector/Spark/Flutter를 직접 수정하지 않는다. 필요한 변경은 정확한 필드와 소비 이유를 handoff로 남긴다.

## 3. 1단계: 계약과 fixture 선제 제공

FE와 DT가 기다리지 않도록 코드 변경 전에 다음을 커밋 가능한 fixture/schema로 제공한다.

- `schedule-evaluation-v2` JSON Schema 또는 동등한 기계 검증 계약
- KEEP/ADVANCE/MARGIN/LATE/DATA/SEARCH 성공 fixture
- PENDING/RUNNING/FAILED/STOPPED/DEPARTURE_REQUIRED fixture
- 409/422/503 오류 fixture
- ML PREVIEW/UNAVAILABLE/production eligible fixture
- 알림 payload fixture
- ACCESS/TRANSFER/EGRESS WALK, endpoint 검증, 제휴 도보 성공·fallback·비용범위 fixture
- Backend가 소비하는 Redis/Kafka fixture 검증 DTO

JSON 필드명과 enum은 공통 통합 문서와 일치해야 한다. fixture는 고정 clock과 합성 데이터임을 표시한다.

## 4. DB와 도메인

### 4.1 새 migration

기존 V1~V5를 수정하지 않고 다음 번호의 migration을 추가한다. 실제 기존 migration 번호를 확인하고 충돌 없는 번호를 선택한다.

일정에 필요한 필드:

- `user_input_depart_at DATETIME(6) NULL`
- `plan_source VARCHAR(32)` — `USER_INPUT`, `ACCEPTED_RECOMMENDATION`
- `actual_depart_at DATETIME(6) NULL`
- `journey_state VARCHAR(32)` — `NOT_STARTED`, `IN_PROGRESS`, `ARRIVED`, `CANCELED`
- `input_revision BIGINT NOT NULL DEFAULT 0`

평가 저장 확장:

- 결과 schema/version 검증
- `valid_until`
- 선택 route와 proposed route 구분
- 모델/마스터/시간표/crosswalk/baseline version 보존
- 구간별 legRole, endpoint evidence, duration source와 walking/cost coverage 보존
- input revision과 event revision을 모두 저장·검증

알림 outbox:

- 별도 `evaluation_notification_outbox`
- `schedule_id`, `evaluation_id`, `input_revision`, unique `dedup_key`
- `payload_json`, `status`, `attempts`, `next_attempt_at`
- `lease_token`, `lease_until`, `last_error_code`, `provider_message_id`
- `created_at`, `sent_at`
- due/lease 인덱스와 schedule/input revision 인덱스

현재 `schedule_evaluation.notification_json/key/notified_key`는 이행 기간 호환용으로 읽을 수 있지만 새 결과를 두 경로에 이중 발송하지 않는다.

### 4.2 일정 저장 규칙

- 신규 일정은 P/D 모두 필수, `P < D`, 미래 일정, 좌표/지역/소유권을 검증한다.
- 기존 P null 일정은 자동 역산하지 않고 DEPARTURE_REQUIRED로 유지한다.
- 직접 수정 시 `userInputDepartAt`과 `plannedDepartAt`을 같이 갱신하고 source를 USER_INPUT으로 둔다.
- 추천 수락 시 `plannedDepartAt`만 제안값으로 갱신하고 원래 `userInputDepartAt`을 보존한다.
- 입력 변경마다 `inputRevision`을 증가시키고 이전 pending 알림을 취소한다.
- 같은 DB 시각을 revision 대신 사용하지 않는다.

## 5. 공통 정방향 계산기

### 5.1 구조

권장 책임 분리:

- `JourneyEvaluationService`: candidate + departure + immutable snapshot + session → ETA/quality/causes
- `ConditionalDeparturePolicy`: P 결과를 보고 유지 또는 T 탐색 여부 결정
- `DepartureSuggestionService`: 지정된 후보 시각 집합을 전체 여정으로 재평가
- `EvaluationSnapshot`: asOf, source times, validity, versions, coverage를 고정
- `RecommendationChangeAnalyzer`: 이전/현재 결과 차이와 근거 분석

`ScheduleEvaluationWorkflow`는 orchestration만 담당한다.

```text
claim
-> route candidates/cache
-> immutable snapshot
-> evaluate all at effective P
-> ConditionalDeparturePolicy
-> when required, search toward T with same snapshot
-> analyze change
-> complete if input/event/lease still current
```

### 5.2 정책 구현

1. `effectiveP=max(P,asOf)`에서 모든 후보를 평가한다.
2. usable 후보 중 ETA가 `isBefore(D)`이면 P 유지 결론을 낸다.
3. ETA가 D와 같거나 이후면 T를 `D.minusSeconds(600)`으로 한 번 계산한다.
4. `[asOf,min(P,T)]` 안의 검증 가능한 후보 시각을 늦은 순으로 평가한다.
5. T 성공 조건은 `!ETA.isAfter(T)`다.
6. T 실패지만 D 전 도착 가능하면 MARGIN_UNAVAILABLE이다.
7. 현재 가능한 최선도 D 이후면 LATE_EXPECTED다.
8. 증거 부족은 DATA_INSUFFICIENT, 경로 없음은 ROUTE_UNAVAILABLE이다.
9. 예산 종료는 SEARCH_INCOMPLETE이며 bestKnownDecision을 함께 반환한다.

`RouteTimelineEvaluator.Assessment.ON_TIME_ESTIMATE`의 기존 `ETA <= D` 의미는 다른 호출자를 위해 유지한다. `< D` 경계는 `ConditionalDeparturePolicy`에서 직접 판정한다.

### 5.3 탐색

- 출발시각마다 WALK/WAIT/RIDE/TRANSFER와 차량 선택을 전부 다시 계산한다.
- P에서 계산한 duration을 빼는 역산을 금지한다.
- 시간표 운행 경계, asOf, T, 원천/통제/시간대 경계를 후보에 포함한다.
- 버스 미래 운행 근거가 없으면 제한 격자와 fallback 한계를 표시한다.
- 초기 설정값은 `maxEvaluations=256`, `fallbackGridSeconds=60`, `maxComputeMillis=5000`으로 둘 수 있으나 설정화하고 제품 보장값으로 표현하지 않는다.
- 탐색 전체가 하나의 모델 호출 budget/session을 공유한다.

### 5.4 접근·이탈 도보 보강

후보 준비 순서:

1. 카카오 대중교통 후보를 BUS/SUBWAY/WALK로 파싱하고 기존 WALK를 보존한다.
2. BUS/SUBWAY를 기존 마스터로 매핑한다.
3. 첫 transit step의 path 시작점과 마지막 transit step의 path 끝점을 좌표 후보로 추출한다.
4. DT가 제공한 EXACT 마스터 정류장·역 좌표와 거리·순서·방향을 검증한다.
5. `RouteLegRoleClassifier`가 WALK를 ACCESS/TRANSFER/EGRESS로 분류한다.
6. 검증된 첫 승차·마지막 하차 endpoint에 대해서만 제휴 도보 API를 호출한다.
7. 동일 역할의 기존 WALK는 별도 도보 결과로 교체하되 두 시간을 더하지 않는다.
8. 비용 범위를 완전히 확인한 후보만 componentized ETA에 사용한다.

추가 책임:

- `RouteLegRoleClassifier`: 앞뒤 BUS/SUBWAY step으로 WALK 역할 판정
- `WalkingEndpointResolver`: path endpoint와 EXACT 마스터 좌표 검증
- `WalkingEvidence`: 별도 도보 결과, endpoint 근거, 비용 범위, fallback 보존
- `WalkingRouteClient`: 접근·이탈 도보 조회 port
- `KakaoWalkingRouteClient`: 제휴 API adapter
- `KakaoWalkingProperties`: base URL, path, service, enabled, timeout
- `KakaoWalkingResponse`: 실제 제휴 API fixture로 확인한 DTO

호출 규격:

- `GET https://dapi.kakao.com/v2/routing/walk`
- `start_x/end_x`는 경도, `start_y/end_y`는 위도이며 입력·출력 좌표계는 WGS84다.
- Header는 `Authorization: KakaoAK ${KAKAO_REST_API_KEY}`를 사용한다.
- 요청 옵션은 `route_mode=SHORTEST`다.
- `status=OK`의 `route.properties.totalTime`(초), `totalDistance`(미터)를 검증한다. service·summary·default_speed는 보내지 않는다.

카카오 stop DTO에는 현재 이름만 있으므로 존재하지 않는 x/y를 가정하지 않는다. path endpoint는 후보일 뿐이며 마스터 좌표와 유일하게 검증돼야 한다. 지하철 역 중심점만 확보된 경우 출입구 좌표로 표현하지 않고 `STATION_CENTER_ONLY` 품질을 남긴다. 허용 거리 임계값은 corpus로 결정하고 설정·정책 버전으로 관리한다.

후보마다 접근·이탈 최대 두 호출이 가능하지만 모든 후보를 무조건 호출하지 않는다. 선택 경로와 상위 평가 후보를 우선하고 평가 session 전체 호출 budget과 cache를 둔다. 현재 cache는 평가 session 안에서 정확한 좌표, routeMode, endpoint policy를 키로 사용한다. API 버전은 adapter로 고정하고 결과 유효기간은 cache-seconds 설정으로 제한한다. 좌표 반올림과 평가 간 공유 cache는 실측 전 적용하지 않는다.

fallback 순서:

1. 검증된 제휴 도보 결과
2. 같은 역할의 카카오 대중교통 WALK
3. provider 전체시간 + DATA_INSUFFICIENT/명시적 fallback

원본 ACCESS/EGRESS WALK가 없고 `unassignedDurationSeconds`의 비용 의미도 불명확하면 별도 도보시간을 provider total에 추가하지 않는다. 직선거리/고정속도로 확정시간을 만들거나 실패를 0초로 두지 않는다.

제휴 403은 설정·권한 문제로 지표와 health에 표시하되 해당 후보는 기존 WALK로 fallback한다. 429는 Retry-After와 제휴 정책을 따르고 같은 평가에서 폭주 재시도하지 않는다. timeout·5xx도 전체 대중교통 검색을 503으로 바꾸지 않고 후보별로 격리한다. 좌표 충돌은 API 장애가 아니라 `WALK_ENDPOINT_UNVERIFIED`다.

## 6. 교통수단과 ML

### 6.1 버스 대기

- `mkTm + exps`를 절대 ETA로 사용하고 collectedAt을 다시 더하지 않는다.
- 플랫폼 도착 이후의 차량만 선택한다.
- 차량 ID, 만차, 우회, 종점 상태, freshness 검증을 유지한다.
- 첫 차량이 불가하면 둘째를 검토하되 관측되지 않은 셋째를 생성하지 않는다.
- 검증된 시간대/요일별 배차 H가 없으면 `0.6H`를 사용하지 않는다.

### 6.2 지하철

- 정적 trip과 실시간 train ID는 EXACT crosswalk에서만 결합한다.
- 서비스 날짜, 방향, 행선지, 급행/일반, 역 순서를 확인한다.
- 24~29시 시각, 막차, 무정차, 취소를 처리한다.
- 하차역 미래 예측이 없으면 정적 이동 기반 ESTIMATED와 한계를 표시한다.
- 지연으로 환승을 놓치면 이후 열차를 다시 선택한다.

### 6.3 도로 사건

- namespace, 방향 link, occurrence, 적용 시간의 EXACT가 필요하다.
- FULL 통제와 예상 진입시간 겹침만 BLOCKED로 판정한다.
- PARTIAL 또는 매핑 불확실 사건은 임의 지연을 더하지 않고 cause/quality로 남긴다.
- 동일 사건이 여러 edge에 걸려도 지연을 중복 가산하지 않는다.

### 6.4 ML 호출과 승인

- ML 요청은 필요한 bus edge를 batch로 전송한다.
- cache key는 modelVersion, namespace, route/stop pair/occurrence, serviceDate, hourSlot, featureSnapshotVersion을 포함한다.
- 같은 평가 session에서 동일 입력을 중복 호출하지 않는다.
- 응답 모델 버전이 섞이면 결과를 혼합하지 않는다.
- 음수, NaN, 무한대, missing/unseen 기준 초과 예측은 거부한다.
- BUS step의 필요한 edge가 모두 같은 정책으로 검증되지 않으면 일부 edge만 ML로 교체하지 않는다.
- `production_ready=false`, 지원 날짜 밖, bundle 불일치, 데이터 부족이면 PREVIEW/UNAVAILABLE과 API fallback을 반환한다.
- 모델 승인 전 PREVIEW 수락 차단을 제거하지 않는다.

### 6.5 응답과 저장

평가 quality 또는 경로 상세에 다음 값을 제공한다.

- `walkingApiApplied`
- `walkingApiCoverage`: NONE/ACCESS_ONLY/EGRESS_ONLY/BOTH
- `costCoverage`: COMPLETE/PARTIAL/UNKNOWN
- `segments[].legRole`
- `segments[].durationSource`
- `segments[].endpointEvidence`
- `fallbackReasons`

`walkingApiCoverage`는 호출 성공이 아니라 최종 ETA에 실제 적용된 범위다. 기존 route_segment mode는 WALK를 유지하고 역할은 새 컬럼 또는 versioned metadata로 이행한다. ACCESS/EGRESS를 BUS/SUBWAY segmentId로 만들지 않는다.

## 7. API

### 7.1 조회 전용 평가

`POST /api/v1/routes/evaluate`

- 인증/지역/좌표/P/D를 검증한다.
- 공통 계산기를 호출한다.
- 일정, route, recommendation을 저장하지 않는다.
- provider 요청 비용 제한과 429를 적용한다.
- 응답은 `schedule-evaluation-v2`에서 scheduleId/inputVersion이 nullable한 별도 schema를 쓰거나 명시된 조회 variant를 사용한다. FE와 schema를 먼저 확정한다.

### 7.2 일정 API

- 재평가 POST는 동일 input revision에 이미 실행 중이면 중복 작업을 만들지 않는다.
- latest GET은 stale 여부와 validUntil을 보존한다.
- accept는 evaluationId만 받고 서버 저장 결과를 사용한다.
- accept는 소유권, input revision, event revision, validUntil, production eligibility, 미래 실행 가능성을 검증한다.
- 중복 accept는 멱등, 오래된 결과는 409다.
- departure는 최초 actualDepartAt을 보존하고 journeyState를 IN_PROGRESS로 바꾼다.

## 8. Scheduler와 동시성

- schedule 등록 commit 후 wake와 DB due 복구를 모두 둔다.
- 한 일정은 한 lease만 가진다.
- 계산 중 input revision 변경 시 이전 완료를 거부한다.
- 계산 중 event revision 변경 시 현재 저장 후 즉시 최신 재평가하거나 이전 저장을 제한하는 일관된 정책을 적용한다.
- 후보 TTL과 원천 freshness를 분리한다.
- 외부 timeout/429/5xx는 bounded backoff, 인증/입력 4xx는 동일 요청 무한 재시도 금지다.
- D 경과 또는 IN_PROGRESS는 출발 전 tick을 중단한다.

## 9. 알림

현재 planner/dispatcher를 기반으로 다음을 구현한다.

1. 평가 저장과 outbox insert를 한 DB 트랜잭션에서 수행한다.
2. 외부 FCM 호출은 DB 트랜잭션 밖에서 수행한다.
3. outbox claim에 lease를 사용한다.
4. 전송 직전 input revision, journey state, validUntil을 다시 확인한다.
5. 성공 후에만 SENT로 처리한다.
6. retryable 실패는 최대 횟수와 backoff로 재예약한다.
7. stale·출발·취소·도착 결과는 CANCELED 처리한다.
8. dedup key는 schedule+input revision+유형+사건 revision+결과 fingerprint 기반이다.
9. exactly-once를 주장하지 않는다. notificationId를 Client에 전달한다.

자동 운영 알림은 shadow/검증 모드에서 시작하고 기본 OFF를 유지한다.

## 10. DT 담당에게 요구할 계약

BE/ML은 필요한 입력을 문서와 schema로 먼저 요청한다.

- Redis key와 JSON schema, TTL/freshness 의미
- Kafka topic/event schema와 key
- sourceGeneratedAt/collectedAt/timestampBasis
- master/crosswalk/model feature snapshot version
- EXACT/PARTIAL/AMBIGUOUS/UNMAPPED 의미
- 정상 빈 응답/부분 실패/실패/비운행 생략 구분
- 버스/지하철/도로/날씨 fixture
- 실제 통과 관측과 예측 ETA의 구분
- 공식 정류장·역 좌표와 출처·버전·중심점/출입구 의미

미제공 필드는 추측하지 않고 missingInputs/fallbackReasons로 반환한다.

## 11. 테스트

### 정책 단위 테스트

- ETA D-1초는 KEEP
- ETA D는 T 탐색
- ETA T는 ADVANCE 성공
- T 실패, D-1초 가능은 MARGIN_UNAVAILABLE
- P 과거는 DEPART_NOW, 과거 시각 추천 금지
- ETA null과 budget exhausted 구분
- 10분 이중 가산 없음
- 여러 후보에서 선택 경로 자동 변경 없음

### 여정 테스트

- 출발을 앞당기면 버스/열차/환승 조합 재선택
- 첫 버스 만차 후 둘째 차량
- stale 실시간과 API fallback
- trainCode/trainId 불일치
- FULL/EXACT/시간 겹침과 반대 방향 통제
- 같은 사건 다중 edge 중복 가산 없음
- ML 일부 edge 누락 시 전체 step fallback
- ACCESS 요청 방향이 출발지→첫 승차지인지 확인
- EGRESS 요청 방향이 마지막 하차지→목적지인지 확인
- 중간 WALK가 TRANSFER로 유지되는지 확인
- path endpoint와 EXACT 좌표 충돌 시 호출 생략
- 제휴 403/429/timeout 시 기존 WALK fallback과 실패 격리
- 원본 WALK와 별도 WALK가 중복 합산되지 않음
- 비용 범위 미확인 시 provider total에 별도 WALK를 가산하지 않음
- 여러 차량 대안의 endpoint 좌표를 섞지 않음

### 저장/동시성 테스트

- input revision 변경 중 완료 거부
- lease 만료 후 늦은 완료 거부
- event revision 변경 재평가
- accept 최신성/멱등/PREVIEW 차단
- outbox retry, crash 경계, stale cancel, dedup
- v1 result JSON 읽기 호환

### 명령

Backend는 Maven wrapper를 사용한다.

```text
mvnw.cmd test
```

ML 서비스는 해당 디렉터리에서 고정 fixture로 실행한다.

```text
python -m pytest -q
```

## 12. 완료 산출물

- Flyway migration과 rollback이 아닌 forward migration 설명
- OpenAPI/schema 및 공통 fixture
- 정책·여정·동시성·알림 테스트 결과
- ML model/runtime manifest와 지원 범위
- DT 입력 fixture 소비 테스트
- FE가 사용할 오류 code 목록
- feature flag와 기본값
- 제휴 도보 권한·service 설정·호출예산·cache와 fallback 운영 문서
- 성능 측정 조건: 후보 수, 평가 수, worker 수, 외부 호출 수, p95

## 13. BE/ML 완료 조건

- 실제 Workflow가 P 평가 후 필요한 경우에만 T 탐색을 호출한다.
- `<D`와 `<=T` 경계가 테스트로 고정된다.
- Client가 계산하지 않아도 모든 표시값과 상태를 응답한다.
- stale 입력·이벤트·lease 결과가 저장 또는 수락되지 않는다.
- DT 입력이 없거나 불확실할 때 명시적 fallback으로 동작한다.
- PREVIEW/운영 승인 상태가 수락·알림 게이트에 반영된다.
- outbox 실패가 성공으로 기록되지 않는다.
- 접근·이탈 도보가 검증된 endpoint에서만 적용되고 환승 WALK와 비용이 중복되지 않는다.
- 제휴 도보 기능이 비활성 또는 장애여도 기존 대중교통 경로가 명시적 fallback으로 유지된다.
