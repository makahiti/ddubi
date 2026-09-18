# FE 담당 작업서 — 조건부 출발 조정

## 2026-09-17 BE 계약 정정

사용자가 지정한 [카카오맵 공식 도보 문서](https://developers.kakao.com/docs/ko/kakaomap/rest-api#walking)를 기준으로 `GET https://dapi.kakao.com/v2/routing/walk`를 사용한다. 이전 제휴 API·service·priority·default_speed 설명은 이 정정으로 대체한다. REST API 키와 `start_x/start_y/end_x/end_y`, WGS84, `route_mode=SHORTEST`를 사용하며 운영 기본값은 OFF다.

- `durationSource`: `KAKAO_WALKING_API`, `PUBLIC_TRANSIT_STEP`, `PROVIDER_TOTAL_FALLBACK`, `ML_PREVIEW`, `TIMETABLE`. 최종 계산에 사용된 출처를 표시한다.
- `endpointEvidence`: nullable 객체. `verified`, `source`, `masterVersion`, `mappingVersion`, `policyVersion`, `distanceMeters`, `coordinateType`, `judgment`, `reason`을 보존한다. HTTP 실패와 좌표 검증 실패는 구분한다.
- `legRole`: `ACCESS_WALK`, `TRANSFER_WALK`, `EGRESS_WALK`, `RIDE`. 승하차 없는 WALK는 표시상 `ACCESS_WALK`로 정규화하고 `WALK_NO_TRANSIT_ENDPOINT`로 별도 API 호출을 생략한다.
- provider 전체시간 fallback에서는 개별 `segments[].durationSeconds=null`이다. 전체 `durationSeconds`를 구간별로 중복 배분하지 않는다.
- `causes`: `eventId`, `source`, `segmentIds`, `effect`, `confirmed`를 최종 BE 계약으로 유지한다. `effect=BLOCKED|POTENTIAL_IMPACT`이며 `confirmed`는 실제 영향 검증 결과다. FE !147 최신 파서는 이 필드들의 호환 읽기를 지원한다. 확인되지 않은 설명·사건 revision은 만들어 넣지 않는다.

기계 계약과 비어 있지 않은 도보·사건 예제는 `docs/contracts/schedule-evaluation-v2.schema.json`, `docs/contracts/examples/conditional-departure/details/`를 따른다. 상세 검증·DT 소비 조건은 `docs/contracts/conditional-departure-be-runtime.md`에 기록한다.

## 1. 목표

Flutter Client가 출발시각을 추측하지 않고 Backend의 `schedule-evaluation-v2` 결과를 그대로 해석·표시한다. 사용자가 입력한 P, 현재 계획 P, 서버 제안, 실제 출발을 구분하고, PREVIEW·데이터 부족·탐색 미완료를 정상 추천처럼 보이지 않게 한다.

공통 계약은 `docs/MVP_plan/conditional-departure-integration.md`를 따른다.

## 2. 담당 범위

### 포함

- 일정 등록·수정에서 `plannedDepartAt`과 `arriveBy` 입력 및 검증
- v2 평가 typed model과 안전한 enum 파싱
- 일정 저장 전 `/routes/evaluate` 연결
- 일정 재평가·최신 결과·수락·출발 확인 API 연결
- 조건별 결과 카드와 품질·근거 표시
- polling 종료/backoff/stale 처리
- 알림 딥링크와 중복 표시 억제
- 접근·환승·이탈 도보 역할과 도보 계산 출처·fallback 품질 표시
- 기존 v1 결과와 새 enum에 대한 호환 처리

### 제외

- `D - 총소요시간`으로 출발시각 계산
- 후보 배열 순번을 추천/최단/안전으로 임의 분류
- `productionEligible`, 실시간 적용 여부, 원인 판정
- 모델/데이터가 없을 때 ETA를 0이나 기존 값으로 보정
- 수락 요청에 임의 `proposedDepartAt`이나 `routeId` 전달
- 직선거리나 고정 보행속도로 접근·이탈 도보시간 계산
- 별도 도보시간을 기존 WALK 또는 전체시간에 더하는 계산

## AI 실행 절차

### 0단계 — 시작 전 확인

1. FE 공식 작업 branch와 기준 develop을 확인한다. PM에서 직접 작업하거나 PM을 push하지 않는다.
2. `client/`의 수정·미추적 파일을 확인하고 기존 작업을 덮지 않는다.
3. 아래 현재 파일을 먼저 읽고 데이터 흐름을 그림으로 정리한다.
   - `client/lib/domain/schedule_evaluation.dart`
   - `client/lib/services/recommendation_api.dart`
   - `client/lib/services/schedule_api.dart`
   - `client/lib/services/api_schedule_repository.dart`
   - `client/lib/presentation/schedule_evaluation_card.dart`
   - `client/lib/presentation/notification_center.dart`
   - 일정 등록·수정 화면과 상태 관리 코드
4. `desiredArrivalAt - durationMinutes`, 후보 index 기반 라벨, v1 Map 파싱의 실제 호출 화면을 모두 찾는다.
5. 현재 Client 테스트가 통과하는지 관련 테스트부터 실행해 baseline을 기록한다.

시작 산출물:

- 현재 화면→service→repository→endpoint 호출 흐름
- 제거할 로컬 계산 위치 목록
- 유지해야 할 v1 호환 경로
- Backend에서 아직 받지 못한 fixture 목록

### 1단계 — fixture와 typed model

1. BE/ML이 제공한 JSON fixture를 `client/test/fixtures/` 또는 프로젝트 기존 fixture 위치에 저장한다.
2. KEEP, ADVANCE, MARGIN, LATE, DATA, SEARCH와 실행 상태 fixture를 각각 파싱하는 실패 테스트를 먼저 추가한다.
3. `ScheduleEvaluationV2`와 하위 typed model을 구현한다.
4. enum은 `unknown(rawValue)` 또는 동등한 안전 표현을 제공한다.
5. nullable과 필수 필드를 schema에 맞춰 구분하고, 누락된 필수값은 조용히 0/빈 문자열로 바꾸지 않는다.
6. `Z`, `+09:00`, 날짜 경계 fixture를 확인한다.
7. walking quality와 segment role/source를 파싱한다.

1단계 통과 기준:

- 모든 공통 fixture 파싱 테스트 통과
- 알 수 없는 state/decision/legRole/durationSource fixture에서 앱 예외 없음
- v1 fixture 파싱 회귀 없음

### 2단계 — API와 repository

1. `/routes/search`의 단순 후보 조회와 `/routes/evaluate`의 조건부 평가를 별도 메서드와 반환 타입으로 분리한다.
2. 요청에는 사용자 입력 P/D와 좌표를 오프셋 포함 ISO 8601로 전달한다.
3. 일반 추천 화면에서 로컬 `D-duration` 계산과 index 기반 추천 라벨을 제거한다.
4. 일정 재평가 POST, latest GET, evaluationId accept, departure 확인 호출을 typed response로 연결한다.
5. 409, 422, 429, 503과 네트워크 오류를 서로 다른 domain failure로 매핑한다.
6. polling은 화면 lifecycle과 일정 상태에 맞춰 시작·중단하고 backoff를 둔다.

2단계 통과 기준:

- repository 단위 테스트에서 요청 body와 endpoint가 계약과 일치
- accept body에 evaluationId 이외의 임의 시각·routeId 없음
- 코드 검색 결과 `desiredArrivalAt.subtract` 기반 추천 계산 없음
- fixture adapter가 production API 실패를 숨기는 fallback으로 남지 않음

### 3단계 — 화면

1. 일정 입력 화면에서 P/D를 모두 보여 주고 P<D 선검증과 서버 field error를 연결한다.
2. 결과 카드는 먼저 state를 처리한 뒤 READY일 때 decision을 처리한다.
3. P, D, T, 제안 출발, ETA, 계산시각, 유효기간을 서버 값 그대로 표시한다.
4. PREVIEW/production eligibility와 수락 버튼 상태를 연결한다.
5. DATA_INSUFFICIENT, SEARCH_INCOMPLETE, LATE를 다른 문구와 아이콘으로 구분한다.
6. 경로 상세에서 ACCESS/TRANSFER/EGRESS와 duration source를 표시한다.
7. stale 결과와 오래된 알림에서는 최신 결과 재조회 전 수락을 비활성화한다.
8. 알림을 열었을 때 scheduleId/evaluationId로 최신 결과를 다시 확인한다.

3단계 통과 기준:

- 상태별 widget golden 또는 화면 테스트 통과
- 서버 fixture의 시각과 화면 표시값 대조 완료
- PREVIEW, stale, unknown enum에서 수락 불가
- 도보 fallback이 경로 없음이나 0분으로 표시되지 않음

### 4단계 — 실제 Backend 연동

1. fixture와 실제 개발 서버 응답을 필드 단위로 비교한다.
2. schema 차이가 있으면 Client에서 임의 보정하지 말고 BE/ML 담당에게 차이를 전달한다.
3. 일정 등록→평가 중→결과→수락 차단/수락→재조회 흐름을 확인한다.
4. 앱 재실행 후에도 로그인·일정과 최신 평가가 정상 조회되는지 확인한다.
5. 같은 evaluationId가 홈·일정 상세·알림에서 같은 P/D/ETA를 표시하는지 확인한다.

4단계 통과 기준:

- fixture와 실제 응답의 필드·enum·nullable 차이 없음
- 일정 등록부터 최신 평가 조회까지 실제 API 흐름 성공
- 오래된 evaluation accept가 409 이후 최신 화면으로 복구
- 앱 재실행 후 로그인·일정·평가 조회 유지

### 5단계 — 완료 보고

AI는 다음 표를 채워 전달한다.

| 항목 | 보고 내용 |
|---|---|
| 기준 | branch, HEAD, 비교한 Backend fixture/schema version |
| 변경 | 파일별 한 줄 설명 |
| 제거 | 로컬 시간 역산·index 라벨 제거 위치 |
| 호환 | v1/unknown enum/stale 처리 방식 |
| 검증 | analyze/test와 상태별 화면 결과 |
| 연동 | 실제 endpoint별 성공·오류 확인 |
| 남음 | Backend 계약 차이, 미지원 화면, 수동 확인 항목 |

FE 범위를 넘어 Backend, DT, 서버 설정을 수정하지 않는다. 필요한 변경은 파일·필드·이유를 handoff로 남긴다.

## 3. 변경 대상과 구현 지침

### 3.1 일정 입력

대상:

- `client/lib/presentation/screens.dart`
- `client/lib/domain/models.dart`
- `client/lib/services/schedule_api.dart`
- `client/lib/data/api_schedule_repository.dart` 또는 실제 repository 경로

요구사항:

1. 출발일시 P와 일정 시작 D를 모두 표시하고 신규 등록에서 필수로 한다.
2. Client 선검증은 `P < D`, 필수값, 좌표 형식까지만 수행한다.
3. 서버 422의 상세 code를 해당 입력 필드 아래에 표시한다.
4. 서버 추천을 조회해도 입력창의 P를 자동으로 바꾸지 않는다.
5. 사용자가 추천 수락을 명시적으로 완료한 뒤 서버 응답을 재조회해서 P를 갱신한다.
6. `userInputDepartAt`, `plannedDepartAt`, `proposedDepartAt`, `actualDepartAt`을 한 필드로 합치지 않는다.
7. 반복 일정 편집은 기존 DTO의 부분수정/전체수정 의미를 유지한다.

### 3.2 v2 domain model

대상:

- `client/lib/domain/schedule_evaluation.dart`

추가할 typed 구조:

- `ScheduleEvaluationV2`
- `EvaluationState`
- `DepartureDecision`
- `EvaluationQuality`
- `EvaluationSearch`
- `EvaluationCause`
- `EvaluationVersions`
- `RouteLegRole`, `DurationSource`, `EndpointEvidence`

파싱 규칙:

- `schemaVersion`으로 v1/v2를 구분한다.
- 알 수 없는 `state`는 화면을 깨뜨리지 않고 unknown/정보 부족 형태로 보존한다.
- 알 수 없는 `decision`도 원문 문자열을 잃지 않는다.
- nullable ETA, proposed 시각, route ID, bestKnownDecision을 강제로 기본값 처리하지 않는다.
- ISO 8601의 `Z`와 오프셋을 정상 파싱한 뒤 로컬 표시로 변환한다.
- `durationSeconds`와 `appliedBufferSeconds`는 별개다.
- `validUntil < now`면 응답 state와 별도로 stale 표시가 가능해야 한다.
- `walkingApiApplied`, `walkingApiCoverage`, `costCoverage`와 구간별 `legRole`, `durationSource`를 보존한다.
- 알 수 없는 legRole이나 durationSource도 앱 종료 없이 원문과 함께 안전하게 처리한다.

### 3.3 일반 경로 평가

대상:

- `client/lib/services/recommendation_api.dart`
- 경로 검색 결과를 사용하는 화면/repository

삭제할 동작:

- `desiredArrivalAt.subtract(Duration(minutes: durationMinutes))`
- 후보 index 0/1/2를 추천/최단/안전으로 지정하는 로직
- 도착시각을 무조건 사용자 희망 D로 표시하는 로직

대체 동작:

1. P와 D가 모두 있는 화면에서는 `POST /api/v1/routes/evaluate`를 호출한다.
2. 응답의 `proposedDepartAt`, `estimatedArrivalAt`, `decision`, `quality`를 표시한다.
3. 서버 endpoint가 아직 활성화되지 않은 환경에서는 `/routes/search` 결과를 단순 후보로만 표시한다.
4. 단순 후보에는 “추천 출발”이라는 문구를 사용하지 않는다.

### 3.4 일정 평가 카드

대상:

- `client/lib/presentation/schedule_evaluation_card.dart`

표시 우선순위:

1. 실행 상태
2. 정책 결론
3. P/D/T/제안 출발/ETA
4. PREVIEW·production eligibility
5. missing/fallback/cause
6. 계산시각·유효기간·다음 갱신

결정별 기본 문구:

| decision | 기본 표시 |
|---|---|
| KEEP_DEPARTURE | 현재 출발 계획을 유지해도 일정 전에 도착할 것으로 예상됩니다. |
| ADVANCE_DEPARTURE | 제안 시각에 출발하면 일정 10분 전 도착이 예상됩니다. |
| DEPART_NOW | 계획한 출발시각이 지났습니다. 현재 가능한 경로를 확인하세요. |
| MARGIN_UNAVAILABLE | 10분 여유 확보는 어렵지만 일정 전 도착 가능한 경로입니다. |
| LATE_EXPECTED | 현재 확인 가능한 경로로는 일정 시작 이후 도착이 예상됩니다. |
| DATA_INSUFFICIENT | 최신 도착 예측에 필요한 정보가 부족합니다. |
| ROUTE_UNAVAILABLE | 현재 이용 가능한 경로를 확인하지 못했습니다. |
| SEARCH_INCOMPLETE | 제한 시간 안에 탐색을 완료하지 못했습니다. 확인된 대안만 표시합니다. |

추가 규칙:

- `quality.preview=true`면 카드 상단에 “검증용”을 명시한다.
- `productionEligible=false`면 추천 수락 버튼을 비활성화하고 이유를 표시한다.
- `SEARCH_INCOMPLETE`에서는 `bestKnownDecision`을 보조 설명으로만 사용한다.
- `causes`가 없으면 사고·정체·날씨를 원인으로 만들어 표시하지 않는다.
- FULL/EXACT 근거가 없는 도로 사건은 “영향 가능성” 이상으로 표현하지 않는다.
- ETA가 null이면 `00:00`, `0분`, “정상”으로 표시하지 않는다.
- 제안과 현재 선택 경로가 다르면 구간 요약을 함께 표시한다.
- P와 proposed가 같아도 경로가 다르면 경로 변경 제안으로 표시한다.
- 경로 상세에서 접근 도보, 환승 도보, 이탈 도보를 구분한다.
- `KAKAO_WALKING_API` 반영 구간과 `PUBLIC_TRANSIT_STEP` fallback 구간을 개발·검증 화면에서 확인할 수 있게 한다.
- `costCoverage=PARTIAL|UNKNOWN`이면 완전한 구성요소 합산 결과처럼 표현하지 않고 품질 안내를 표시한다.
- 도보 제휴 권한 없음이나 endpoint 미검증은 “경로 없음”이 아니라 기존 WALK fallback으로 표현한다.

### 3.5 API 상태와 polling

대상:

- 일정 API/repository
- 평가 상태 provider/controller

규칙:

- 재평가 POST 후 완료될 때까지 최신 결과 GET을 사용한다.
- 화면 비활성, 일정 종료, `STOPPED`에서는 polling을 중단한다.
- 429/503/네트워크 실패는 지수 backoff를 적용한다.
- 409 수락 실패는 최신 결과를 다시 가져오고 “추천이 변경됨”으로 표시한다.
- STALE일 때 이전 결과를 보여 줄 수 있지만 stale 배지를 제거하지 않는다.
- Client polling 주기를 서버 계산 주기로 오해하지 않는다.

### 3.6 알림

대상:

- `client/lib/presentation/notification_center.dart`
- push/deep-link 처리 코드

요구사항:

- payload의 `notificationId`, `scheduleId`, `evaluationId`, `decision`을 파싱한다.
- 같은 `notificationId`는 앱 안에서 중복 표시하지 않는다.
- 알림을 열면 최신 평가를 다시 조회한다.
- 알림의 evaluationId가 최신과 다르면 수락 버튼을 제공하지 않는다.
- 이미 출발·도착·취소된 일정에는 과거 조기 출발 권고를 적용하지 않는다.

## 4. BE/ML 담당에게 먼저 받을 것

작업 초기에 다음 산출물을 요청하고 fixture 기반으로 개발한다.

1. v2 성공 JSON: KEEP, ADVANCE, MARGIN_UNAVAILABLE, LATE, DATA_INSUFFICIENT, SEARCH_INCOMPLETE
2. PENDING/RUNNING/FAILED/STOPPED/DEPARTURE_REQUIRED JSON
3. 409/422/503 오류 JSON
4. PREVIEW와 production eligible 각각의 예제
5. 알림 payload 예제
6. 도보 ACCESS/TRANSFER/EGRESS, BOTH/부분 coverage와 endpoint 미검증 fixture
7. enum 목록과 nullable/required schema

Backend가 준비될 때까지 fixture adapter를 사용하되 production 코드에 fixture fallback을 남기지 않는다.

## 5. 테스트

### 단위 테스트

- 모든 state/decision 파싱
- 알 수 없는 enum 안전 처리
- `Z`, `+09:00`, 날짜 경계 시각 표시
- nullable ETA/route/proposed 처리
- validUntil 만료 판정
- `durationSeconds` 표시 반올림이 정책 비교를 바꾸지 않음

### widget 테스트

- KEEP/ADVANCE/MARGIN/LATE/DATA/SEARCH 각 카드 문구
- PREVIEW 수락 버튼 비활성화
- 409 후 최신 결과 재조회
- stale 결과 배지
- cause 없는 ETA 증가에서 사고 문구 미표시
- 제안 경로가 달라졌을 때 구간 표시
- 접근·환승·이탈 도보 역할 표시
- 도보 API BOTH/부분/미적용 coverage와 costCoverage 품질 표시

### API/repository 테스트

- 요청 P/D 오프셋 보존
- `/routes/evaluate`와 `/routes/search` 의미 분리
- 로컬 `D-duration` 계산이 호출되지 않음
- 수락 body가 evaluationId만 포함
- polling 중단과 backoff
- walking 필드를 모르는 구버전 응답의 호환 처리

실행:

```text
flutter analyze
flutter test
```

## 6. 완료 산출물

- 변경 코드와 Client 테스트
- FE가 사용한 v2 fixture 목록
- 화면별 캡처: KEEP, ADVANCE, MARGIN, LATE, DATA, SEARCH, PREVIEW
- 경로 상세 캡처: ACCESS/TRANSFER/EGRESS 및 도보 API 적용·fallback
- API별 요청/응답 로그에서 비밀값을 제거한 예제
- 지원하지 못한 enum/필드와 fallback 목록
- BE/ML fixture와 실제 응답의 diff가 없다는 확인

## 7. FE 완료 조건

- 앱 어디에서도 D에서 duration을 빼서 추천 출발을 만들지 않는다.
- 서버 P/D/ETA/decision과 화면 값이 동일하다.
- PREVIEW 결과를 수락할 수 없다.
- 정보 부족과 지각, 탐색 미완료를 구분한다.
- 구버전 응답이나 새 enum으로 앱이 종료되지 않는다.
- 일정 입력, 홈, 일정 상세, 추천, 알림 화면이 같은 evaluationId의 값을 표시한다.
- 접근·이탈 도보시간을 Client가 재계산하거나 중복 합산하지 않는다.
