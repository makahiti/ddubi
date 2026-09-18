# 조건부 출발 조정 3파트 통합 계약

## 2026-09-17 BE 계약 정정

사용자가 지정한 [카카오맵 공식 도보 문서](https://developers.kakao.com/docs/ko/kakaomap/rest-api#walking)를 기준으로 `GET https://dapi.kakao.com/v2/routing/walk`를 사용한다. 이전 제휴 API·service·priority·default_speed 설명은 이 정정으로 대체한다. REST API 키와 `start_x/start_y/end_x/end_y`, WGS84, `route_mode=SHORTEST`를 사용하며 운영 기본값은 OFF다.

- `durationSource`: `KAKAO_WALKING_API`, `PUBLIC_TRANSIT_STEP`, `PROVIDER_TOTAL_FALLBACK`, `ML_PREVIEW`, `TIMETABLE`. 최종 계산에 사용된 출처를 표시한다.
- `endpointEvidence`: nullable 객체. `verified`, `source`, `masterVersion`, `mappingVersion`, `policyVersion`, `distanceMeters`, `coordinateType`, `judgment`, `reason`을 보존한다. HTTP 실패와 좌표 검증 실패는 구분한다.
- `legRole`: `ACCESS_WALK`, `TRANSFER_WALK`, `EGRESS_WALK`, `RIDE`. 승하차 없는 WALK는 표시상 `ACCESS_WALK`로 정규화하고 `WALK_NO_TRANSIT_ENDPOINT`로 별도 API 호출을 생략한다.
- provider 전체시간 fallback에서는 개별 `segments[].durationSeconds=null`이다. 전체 `durationSeconds`를 구간별로 중복 배분하지 않는다.
- `causes`: `eventId`, `source`, `segmentIds`, `effect`, `confirmed`를 최종 BE 계약으로 유지한다. `effect=BLOCKED|POTENTIAL_IMPACT`이며 `confirmed`는 실제 영향 검증 결과다. FE !147 최신 파서는 이 필드들의 호환 읽기를 지원한다. 확인되지 않은 설명·사건 revision은 만들어 넣지 않는다.

기계 계약과 비어 있지 않은 도보·사건 예제는 `docs/contracts/schedule-evaluation-v2.schema.json`, `docs/contracts/examples/conditional-departure/details/`를 따른다. 상세 검증·DT 소비 조건은 `docs/contracts/conditional-departure-be-runtime.md`에 기록한다.

## 1. 목적과 적용 기준

이 문서는 조건부 출발 조정 기능을 FE, BE/ML, DT 세 담당자가 병렬로 구현한 뒤 PM 로컬 통합 브랜치에서 바로 연결하기 위한 공통 계약이다. 세부 계산·원천 정책은 아래 문서를 기준으로 하며, 충돌 시 이 문서의 역할 경계와 최신 코드 정정 사항을 먼저 적용한다.

2026-09-16 사용자가 최신본으로 제공한 5개 문서를 다시 검토해 카카오모빌리티 제휴 도보 길찾기, ACCESS/TRANSFER/EGRESS WALK, 승·하차 좌표 검증, 비용 중복 방지 요구를 반영했다. 저장소의 원본 계약 문서가 아직 교체되지 않은 동안에는 해당 첨부본과 이 handoff 문서를 최신 작업 기준으로 사용한다.

- `docs/contracts/arrival-deadline-calculation.md`
- `docs/contracts/arrival-deadline-implementation-plan.md`
- `docs/contracts/arrival-deadline-data-guide.md`
- `docs/contracts/schedule-reevaluation.md`
- `docs/contracts/travel-time-calculation.md`
- `docs/contracts/kakao-public-transit-route.md`

작성 시점의 통합 기준은 로컬 `PM@fa4e0e4`, 포함된 원격 기준은 `origin/develop@8b12e02`다. 작업 시작 시 각 담당자는 원격 기준을 다시 확인한다. PM은 로컬 통합 전용이며 push하거나 MR 대상으로 사용하지 않는다.

기존 승격 흐름은 다음과 같다.

- FE: `fe/dev -> fe/main -> develop`
- BE/ML: `be/dev -> develop`
- DT: `dt/dev -> dt/main -> develop`

새 장기 통합 브랜치를 만들지 않는다. 각 담당자는 기존 팀 작업 흐름에서 변경을 제출하고, 최종 교차 검증만 PM에서 수행한다.

## AI에게 전달할 때의 실행 규칙

각 담당 AI에는 이 공통 문서와 자기 파트 작업서를 함께 전달한다. AI는 아래 순서를 건너뛰지 않는다.

1. 현재 checkout, branch, HEAD, upstream과 `git status`를 확인한다.
2. 자기 파트 디렉터리와 기존 사용자 변경·미추적 파일을 구분한다.
3. 최신 첨부 계약과 현재 코드를 대조하고, 이미 구현된 기능과 새 작업을 목록으로 만든다.
4. 공통 schema와 fixture가 없으면 제품 코드보다 먼저 만든다.
5. 상대 파트가 아직 없어도 fake adapter/fixture로 자기 파트 테스트가 가능하게 만든다.
6. 한 단계가 끝날 때마다 관련 테스트를 통과한 뒤 다음 단계로 이동한다.
7. 기존 fallback과 v1 호환을 유지한 상태에서 새 기능을 flag 또는 adapter 뒤에 연결한다.
8. 자기 범위를 벗어난 파일이 필요하면 임의로 수정하지 말고 필요한 계약과 이유를 handoff에 기록한다.
9. 작업 완료 시 변경 파일, 계약 변경, 테스트 결과, 미완료·위험, 상대 파트가 할 일을 정해진 형식으로 보고한다.

AI가 작업을 중단하고 확인해야 하는 경우:

- 공통 필드명·enum·endpoint를 문서와 다르게 바꿔야 하는 경우
- 기존 migration을 수정해야만 진행할 수 있다고 판단한 경우
- 제휴 도보 권한, 실제 응답 schema 또는 공식 좌표 없이 값을 추측해야 하는 경우
- PREVIEW 수락 차단이나 기존 fallback을 제거해야 하는 경우
- PM을 push하거나 새 통합 브랜치를 만들어야 하는 경우
- 다른 담당자의 활성 변경과 같은 파일을 동시에 수정해야 하는 경우

각 단계의 완료는 코드 작성이 아니라 `산출물 존재 + 관련 테스트 통과 + 다음 파트가 읽을 수 있는 예제 제공`으로 판단한다.

## 2. 최신 코드 기준 정정

기존 계획 문서를 구현 지시로 전환할 때 다음 사항을 반드시 정정해서 해석한다.

1. Backend는 Gradle이 아니라 Maven이다. 관련 검증은 `mvnw.cmd`로 실행한다.
2. 현재 `ScheduleEvaluationWorkflow`는 동일한 P에서 후보를 비교할 뿐 조건부 T 탐색을 수행하지 않는다.
3. 기존 `DepartureSuggestionService`는 D까지 도착 가능하면 탐색을 종료하므로 그대로 연결하면 안 된다. `ETA == D`도 새 정책에서는 T 탐색 대상이다.
4. 현재 알림 planner, dispatcher, `schedule_evaluation.notification_json/key`는 이미 존재한다. 새 작업은 이 기능을 삭제하거나 중복 구현하는 것이 아니라 독립 outbox, claim/retry/ack, 최신성 검증으로 이행하는 것이다.
5. Client의 `desiredArrivalAt - durationMinutes` 계산은 임시 표시 로직이다. 새 기능에서는 서버 계산을 사용하고 삭제한다.
6. PREVIEW 결과 저장은 현재 허용되지만 수락은 차단된다. 모델 운영 승인 없이 차단을 해제하지 않는다.
7. 최신 설계는 접근·이탈 도보를 카카오모빌리티 제휴 도보 API로 보강한다. 일반 카카오 REST 권한과 같다고 보지 않으며, 제휴 권한이 없으면 기존 대중교통 WALK로 fallback한다.
8. 별도 도보시간을 provider 전체시간이나 기존 WALK에 무조건 더하지 않는다. 동일 역할은 교체하고 비용 범위가 불명확하면 provider 전체시간 fallback을 유지한다.

## 3. 담당 경계

| 담당 | 소유 범위 | 다른 파트에 넘기는 결과 | 맡지 않는 범위 |
|---|---|---|---|
| FE | Flutter DTO, API 연결, 일정 입력, 결과·품질·오류·알림 UI | Client contract test, 화면별 상태표, 구버전 응답 호환 결과 | 출발 추천 계산, ETA 자체 역산, 데이터 품질 판정 |
| BE/ML | 일정/평가 DB, API v2, 조건부 정책, 여정 평가, 제휴 도보·모델 호출과 fallback, scheduler, outbox | OpenAPI/JSON fixture, DB migration, 도보·ML 응답 계약, 통합 테스트 결과 | 원천 수집, Kafka/Spark 정규화, 임의 마스터 생성 |
| DT | Collector, Kafka, Spark, Redis, HDFS/Parquet, 원천·마스터·crosswalk·좌표 artifact | 스키마, fixture, Redis 예제, 버전 manifest, 좌표 의미, 품질/신선도 보고 | Client/Backend DB 직접 수정, 사용자 추천 판단, 외부 도보 API 호출, 모델 운영 승인 |

ML 경계는 다음처럼 나눈다.

- DT는 모델에 넣을 수 있는 검증된 입력과 버전·품질 정보를 만든다.
- BE/ML은 ML HTTP 호출, request batching/cache, 응답 검증, 지원 날짜 확인, fallback 및 `productionEligible` 판정을 맡는다.
- 모델 재학습이 필요하면 BE/ML 담당이 학습/runtime 계약을 소유하고 DT가 제공한 데이터 manifest를 입력으로 사용한다.
- 데이터가 있다는 이유만으로 모델을 운영 승인하지 않으며, 모델 응답이 있다는 이유만으로 DT 데이터 품질을 확정하지 않는다.

도보 API 경계는 다음처럼 나눈다.

- DT는 첫 승차·마지막 하차에 사용할 공식 정류장·역 좌표와 출처·버전·좌표 의미를 제공한다.
- BE/ML은 카카오 path endpoint와 EXACT 마스터 좌표를 검증하고, 제휴 도보 API를 호출하며, ACCESS/TRANSFER/EGRESS 역할과 비용 중복을 판정한다.
- FE는 서버가 확정한 구간·시간·품질만 표시하고 직선거리나 고정 보행속도로 시간을 만들지 않는다.

## 4. 동결할 공통 의미

### 4.1 시간

- `P = plannedDepartAt`: 현재 사용자가 실행하려는 출발 계획
- `D = arriveBy`: 일정 시작시각
- `T = D - 600초`: P에서 ETA가 D보다 빠르지 않을 때만 사용하는 조정 목표
- `asOf`: 평가 증거를 고정한 기준시각
- `effectiveDepartAt = max(P, asOf)`
- 서버 내부 비교는 `Instant`와 초 단위를 사용한다.
- API 시각은 ISO 8601 오프셋 포함 형식으로 반환한다. 예: `2026-09-17T08:30:00+09:00`.
- FE는 파싱한 시각을 로컬 시간으로 표시하되 문자열 끝의 `Z`나 오프셋을 제거해 재해석하지 않는다.

### 4.2 정책 경계

1. usable 후보 중 `ETA < D`가 있으면 P를 유지한다.
2. `ETA == D`는 유지가 아니라 T 탐색 대상이다.
3. T 성공은 `ETA <= T`다.
4. T는 한 번만 계산하고 이동시간에 10분을 다시 더하지 않는다.
5. P가 과거면 과거 출발을 추천하지 않고 `departureMissed=true`와 실행 가능한 현재 이후 결과를 반환한다.
6. ETA가 없거나 근거가 불충분하면 숫자 0이나 정상으로 대체하지 않는다.
7. 탐색 예산이 끝나면 `SEARCH_INCOMPLETE`이며, 찾은 후보의 성격은 `bestKnownDecision`으로 별도 전달한다.

### 4.3 실행 상태와 정책 결정

실행 상태 `state`와 계산 결론 `decision`은 별개다.

`state` 허용값:

- `PENDING`, `RUNNING`, `READY`, `FAILED`, `STOPPED`, `DEPARTURE_REQUIRED`, `STALE`

`decision` 최소 허용값:

- `KEEP_DEPARTURE`
- `ADVANCE_DEPARTURE`
- `DEPART_NOW`
- `MARGIN_UNAVAILABLE`
- `LATE_EXPECTED`
- `DATA_INSUFFICIENT`
- `ROUTE_UNAVAILABLE`
- `SEARCH_INCOMPLETE`

새 enum이 추가될 수 있으므로 FE는 알 수 없는 문자열을 안전한 정보 부족 상태로 표시한다.

## 5. FE-BE API 계약

### 5.1 일정 저장 전 조회

`POST /api/v1/routes/evaluate`

요청 최소 필드:

```json
{
  "origin": {"longitude": 127.0, "latitude": 37.0},
  "destination": {"longitude": 127.1, "latitude": 37.1},
  "plannedDepartAt": "2026-09-17T08:30:00+09:00",
  "arriveBy": "2026-09-17T09:00:00+09:00"
}
```

이 API는 일정·추천 DB를 변경하지 않는다. 기존 `/routes/search`는 후보 조회 의미를 유지한다.

### 5.2 일정 평가

- `POST /api/v1/schedules/{id}/recommendations`: 재평가 요청
- `GET /api/v1/schedules/{id}/recommendations/latest`: 최신 결과 조회
- `POST /api/v1/schedules/{id}/recommendations/accept`: body에는 `evaluationId`만 전달
- `POST /api/v1/schedules/{id}/departure`: 실제 출발 확인

공통 응답은 `ApiResponse.data` 안의 `schedule-evaluation-v2` 구조를 사용한다. 최소 필드는 다음과 같다.

```json
{
  "schemaVersion": "schedule-evaluation-v2",
  "evaluationId": "immutable-id",
  "scheduleId": 101,
  "inputVersion": 3,
  "eventRevision": 4,
  "state": "READY",
  "decision": "KEEP_DEPARTURE",
  "triggerReason": "INITIAL",
  "userInputDepartAt": "2026-09-17T08:30:00+09:00",
  "plannedDepartAt": "2026-09-17T08:30:00+09:00",
  "effectiveDepartAt": "2026-09-17T08:30:00+09:00",
  "arriveBy": "2026-09-17T09:00:00+09:00",
  "originalEstimatedArrivalAt": "2026-09-17T08:59:00+09:00",
  "targetArriveAt": "2026-09-17T09:00:00+09:00",
  "appliedBufferSeconds": 0,
  "proposedDepartAt": "2026-09-17T08:30:00+09:00",
  "estimatedArrivalAt": "2026-09-17T08:59:00+09:00",
  "durationSeconds": 1740,
  "latenessSeconds": 0,
  "arrivesBeforeSchedule": true,
  "meetsAdjustedTarget": null,
  "departureMissed": false,
  "selectedRouteId": 11,
  "proposedRouteId": 11,
  "quality": {
    "preview": true,
    "productionEligible": false,
    "realtimeApplied": false,
    "walkingApiApplied": true,
    "walkingApiCoverage": "BOTH",
    "costCoverage": "COMPLETE",
    "missingInputs": [],
    "fallbackReasons": ["PROVIDER_DURATION"]
  },
  "search": {
    "domain": "SUPPLIED_ROUTE_CANDIDATES_AT_P",
    "evaluatedCount": 3,
    "complete": true
  },
  "bestKnownDecision": null,
  "causes": [],
  "computedAt": "2026-09-17T08:00:01+09:00",
  "asOf": "2026-09-17T08:00:00+09:00",
  "validUntil": "2026-09-17T08:02:00+09:00",
  "nextEvaluationAt": "2026-09-17T08:05:00+09:00",
  "versions": {"policy": "cdep-v1"}
}
```

nullable 필드를 누락과 동일하게 취급하지 않는다. Backend schema와 FE fixture는 동일 JSON 파일 또는 동일 생성 원본을 사용한다.

경로 상세 구간에는 `legRole`, `durationSource`, `endpointEvidence`를 추가한다. WALK 역할은 `ACCESS_WALK`, `TRANSFER_WALK`, `EGRESS_WALK`로 구분하고 BUS/SUBWAY는 `RIDE`다. `walkingApiCoverage`는 호출 성공 횟수가 아니라 최종 계산에 실제 반영된 범위이며 `NONE`, `ACCESS_ONLY`, `EGRESS_ONLY`, `BOTH`를 사용한다. `costCoverage`가 `PARTIAL` 또는 `UNKNOWN`이면 일부 비용을 합친 결과를 운영 추천으로 확정하지 않는다.

### 5.3 오류 의미

- `400/422`: 필드 형식, P/D 관계, 좌표 또는 지원 범위 오류
- `401/403`: 로그인 또는 일정 소유권 오류
- `404`: 일정/평가 대상 없음
- `409`: 오래된 evaluation, input revision 변경, 이미 실행 불가능한 수락
- `429`: 평가 요청 제한
- `503`: 외부 경로·모델·데이터 의존성 일시 실패

오류 응답은 기계 판별 가능한 `code`를 제공하고 FE는 HTTP 상태만으로 문구를 추측하지 않는다.

## 6. DT-BE/ML 데이터 계약

모든 실시간 값은 다음 메타데이터를 보존한다.

- `source`, `providerNamespace`, provider ID
- `sourceGeneratedAt`, `collectedAt`, `timestampBasis`
- `mappingVersion`, `masterVersion`
- `validUntil` 또는 소비자가 계산할 수 있는 TTL 근거
- `qualityFlags`, `missingFields`, `rejectionReasons`
- 원천 내용 기준 `contentHash` 또는 안정적인 사건 identity/revision

Backend는 Redis 키 부재를 정상으로 해석하지 않는다. DT는 빈 정상 응답, 부분 응답, 실패, 제한, 비운행 생략을 구분한다.

필수 전달 인터페이스:

| 종류 | DT 출력 | BE/ML 소비 |
|---|---|---|
| 버스 도착 | route/stop/vehicle별 절대 ETA와 품질 | 플랫폼 도착 이후 탑승 가능한 차량 선택 |
| 버스 위치 | route/vehicle/section occurrence와 관측시각 | 도착 정보 보조, 실제 통과로 단정 금지 |
| 지하철 | 역/열차 ETA·위치·공지 및 crosswalk 상태 | 시간표와 EXACT 결합 또는 fallback |
| 승·하차 좌표 | 공식 정류장·역 좌표, 출처·버전·중심점/출입구 의미 | path endpoint 검증과 접근·이탈 도보 endpoint 결정 |
| 도로 사건 | incident identity/revision, 방향 link, 기간, 통제 수준 | EXACT+FULL+시간 겹침일 때만 차단 |
| 날씨/기준선 | bucket, version, sample window/count, p50/p80/p95 | 같은 버전·조건 내 비교 |
| ML 입력 | canonical segment, feature 시각, snapshot version | 모델 요청과 cache key 구성 |
| 상태 이벤트 | segment status/road impact와 event revision | 관련 일정 wake 및 재평가 |

## 7. 병렬 작업 중 동결 규칙

1. 공통 enum, JSON 필드명, Redis key/schema를 바꾸려면 세 담당 문서를 함께 수정한다.
2. BE/ML은 첫날에 request/response fixture와 enum 목록을 FE에 제공한다.
3. DT는 첫날에 Redis/Kafka fixture와 버전 필드 목록을 BE/ML에 제공한다.
4. 실제 의존성이 준비되지 않아도 FE는 fixture, BE/ML은 fake port, DT는 schema test로 작업을 계속할 수 있어야 한다.
5. fallback은 성공처럼 숨기지 않고 `quality.fallbackReasons`에 기록한다.
6. PREVIEW와 `productionEligible=false`는 연동 완료 여부와 별개다. 수락과 자동 운영 알림은 각 게이트 통과 전까지 닫아 둔다.
7. 기존 migration을 수정하지 않고 새 migration을 추가한다.
8. 제휴 도보 API의 service 식별자·권한·쿼터가 확인되지 않아도 전체 경로 기능을 막지 않는다. 기능 flag와 기존 WALK fallback으로 분리한다.

## 8. 통합 순서와 완료 게이트

1. 세 파트가 공통 fixture를 파싱·검증한다.
2. BE/ML 순수 정책과 조회 전용 API를 FE fixture 화면에 연결한다.
3. DT 좌표·교통 fixture를 BE/ML snapshot과 walking endpoint resolver에 연결한다.
4. 접근·이탈 도보 보강을 기능 flag로 연결하고 fallback·비용 중복 방지를 검증한다.
5. 일정 저장·재평가·수락 차단을 연결한다.
6. 사건 wake와 durable outbox를 연결한다.
7. PM에서 고정 clock E2E를 수행한다.

공통 필수 시나리오:

| 시나리오 | 기대 |
|---|---|
| ETA 08:59:59, D 09:00 | KEEP, buffer 0 |
| ETA 09:00:00 | T 08:50 탐색 |
| T에 정확히 도착 | ADVANCE 성공, 이중 buffer 없음 |
| T 실패, D 전 도착 가능 | MARGIN_UNAVAILABLE |
| 현재 출발도 D 이후 도착 | LATE_EXPECTED, 과거 추천 없음 |
| ETA 없음 | DATA_INSUFFICIENT |
| 탐색 예산 소진 | SEARCH_INCOMPLETE + bestKnownDecision |
| 입력 수정 중 이전 평가 완료 | 이전 결과 저장·수락 거부 |
| stale 원천 | 실시간 미적용과 fallback 이유 표시 |
| PREVIEW 결과 | 표시·저장 가능, 수락 차단 |
| 출발지→첫 승차지 | ACCESS_WALK, origin→boarding 순서로 제휴 도보 요청 |
| 마지막 하차지→목적지 | EGRESS_WALK, alighting→destination 순서로 요청 |
| BUS→WALK→SUBWAY | 중간 WALK는 TRANSFER_WALK, 별도 도보 API로 교체하지 않음 |
| path와 EXACT 좌표 충돌 | 도보 호출 생략, WALK_ENDPOINT_UNVERIFIED fallback |
| 제휴 403·429·timeout | 기존 WALK fallback, 전체 대중교통 경로 실패로 처리하지 않음 |
| 원본 WALK와 별도 WALK 동시 존재 | 동일 역할은 하나만 계산하고 durationSource 기록 |

파트 완료는 자기 테스트 통과만을 뜻하지 않는다. 위 공통 fixture의 생산자와 소비자가 같은 의미로 통과하고 PM E2E에서 평가 ID·P·D·ETA·품질이 일치해야 연동 완료다.

## 담당 AI 시작 프롬프트

문서 링크만 보내지 말고 담당 AI에 아래 시작 문구를 함께 전달한다.

### FE

```text
docs/MVP_plan/conditional-departure-integration.md와
docs/MVP_plan/conditional-departure-fe.md를 먼저 전부 읽어라.
현재 FE 공식 작업 브랜치와 사용자 변경을 확인한 뒤, FE 작업서의 AI 실행 절차를 0단계부터 순서대로 수행하라.
공통 fixture/schema를 먼저 고정하고 Client가 출발시각이나 도보시간을 자체 계산하지 않게 하라.
Backend나 DT 파일은 수정하지 말고 필요한 계약 변경은 handoff에 기록하라.
각 단계 통과 기준을 검증하고 마지막에 정해진 완료 보고 표로 결과를 제출하라.
```

### BE/ML

```text
docs/MVP_plan/conditional-departure-integration.md와
docs/MVP_plan/conditional-departure-be-ml.md를 먼저 전부 읽어라.
현재 BE/ML 공식 작업 브랜치, migration, 사용자 변경과 실제 호출 흐름을 확인한 뒤 AI 실행 절차를 0단계부터 순서대로 수행하라.
schema/fixture와 순수 정책 테스트를 제품 연결보다 먼저 만들고, 접근·이탈 도보는 실제 제휴 fixture와 검증된 endpoint가 있을 때만 활성화하라.
Collector/Spark/Flutter는 수정하지 말고 필요한 입력·계약은 handoff에 기록하라.
각 단계 통과 기준과 Maven/pytest 결과를 확인하고 마지막에 정해진 완료 보고 표로 결과를 제출하라.
```

### DT

```text
docs/MVP_plan/conditional-departure-integration.md와
docs/MVP_plan/conditional-departure-dt.md를 먼저 전부 읽어라.
현재 DT 공식 작업 브랜치와 기존 수집·Spark·artifact 작업을 보존한 뒤 AI 실행 절차를 0단계부터 순서대로 수행하라.
원천 fixture/schema와 시간·ID·버전 의미를 먼저 고정하고, 실제 통과·예측·기준선을 섞지 마라.
Backend DB/API, Flutter, 제휴 도보 Client는 수정하지 말고 필요한 소비 계약은 handoff에 기록하라.
각 단계 통과 기준과 pytest/Spark/replay/소비 테스트 결과를 확인하고 마지막에 정해진 완료 보고 표로 결과를 제출하라.
```
