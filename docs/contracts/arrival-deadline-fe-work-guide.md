# FE 작업 가이드 — 일정 입력·추천 이해·수락·이동 상태

- 상태: 구현 전 작업 명세. 추가 화면·필드·endpoint가 현재 구현돼 있다는 의미가 아니다.
- 기준: [공통 v2 응답 명세와 예제](arrival-deadline-implementation-plan.md#22-v2-평가-응답의-필드-명세--추가), [계산 정책](arrival-deadline-calculation.md), [BE/ML 작업](arrival-deadline-be-ml-work-guide.md), [DATA 작업](arrival-deadline-data-work-guide.md).
- FE는 사용자의 입력·동의·상태와 서버 결과를 정확히 연결한다. 최종 출발 계산과 교통 API 키 관리는 BE/ML, 원천 확보와 데이터 품질은 DATA의 책임이다.

## 1. 사용자가 경험할 흐름

```text
출발지/목적지 + 출발 예정 P + 일정 시작 D 입력
→ 일정 저장 → 최초 계산 중 → 경로/출발/도착/품질 확인
→ 필요할 때 새 출발·경로 제안 수락
→ 변경 알림에서 최신 결과 확인
→ 출발 확인 → 남은 경로/도착예상 확인 → 도착 확인
```

P=08:30,D=09:00,ETA=08:59이면 ‘08:30 출발 예정, 08:59 도착 예상’이다. ETA=09:00 이상으로 판단되어 새 출발이 08:20이면 ‘현재 계획 08:30 / 추천 출발 08:20 / 예상 도착 08:50’을 구분한다. 추천을 보여 주는 것만으로 편집 화면의 P를 바꾸지 않는다.

## 2. 작업 목록과 수정 위치

| 작업 | 무엇을 만드는가 | 주요 파일 |
|---|---|---|
| FE01 모델·API | v2 결과와 오류를 손실 없이 읽기 | `client/lib/domain/schedule_evaluation.dart`, `models.dart`, `services/schedule_api.dart`, `api_schedule_repository.dart` |
| FE02 일정 입력 | P/D와 반복 일정 출발 입력 | `client/lib/presentation/screens.dart`의 ScheduleFormScreen, 일정 DTO |
| FE03 추천 화면 | 유지/앞당김/여유 미달/지각/정보 부족 구별 | `client/lib/presentation/schedule_evaluation_card.dart`, 일정 상세·홈의 호출부 |
| FE04 경로 상세 | 접근/환승/이탈·대기·탑승·근거 표시 | 기존 경로 표시 컴포넌트, 필요 시 신규 timeline 위젯 |
| FE05 수락·갱신 | 오래된 추천 수락과 응답 역전 방지 | `schedule_api.dart`, `api_schedule_repository.dart`, 추천 카드 |
| FE06 알림 | 이유·변경 시각·최신 결과 연결 | `client/lib/presentation/notification_center.dart`, 기존 push 수신/딥링크 처리 |
| FE07 이동 상태 | 출발·위치 동의·도착 확인 | 기존 일정 화면, 신규 `client/lib/services/journey_location_service.dart` 제안 |
| FE08 테스트·이행 | 기존 화면 계산 제거와 공통 fixture 검증 | `client/test/`, `client/lib/services/recommendation_api.dart` |

기존 파일의 관련 클래스/메서드를 먼저 찾고 같은 책임의 중복 repository를 만들지 않는다. 표에 없는 신규 위젯은 UI 분리가 필요할 때만 추가한다.

## 3. FE01 — 서버 응답 모델과 API 계층

**입력:** BE/ML이 제공할 v2 schema와 정상/경계/오류 fixture. 확보되지 않은 실시간 값은 null·quality로 들어오므로 이를 처리하는 화면부터 만든다.

1. `schemaVersion`으로 새 응답과 과거 응답을 구별한다. 파싱 실패 시 화면 crash 대신 마지막 결과/재조회 상태를 표시한다.
2. `state`는 실행 상태, `decision`은 정책 결과, `quality`는 데이터 품질 객체로 각각 읽는다. 서로 다른 축을 enum 하나로 합치지 않는다.
3. `userInputDepartAt`, `plannedDepartAt`, `proposedDepartAt`, `actualDepartAt`을 별도 모델 필드로 둔다. 날짜를 빼고 HH:mm 문자열만 저장하지 않는다.
4. `estimatedArrivalAt`, `durationSeconds`, `causes`, `validUntil`, `nextEvaluationAt`, `inputVersion`, `eventRevision`을 보존한다. null ETA를 0초로 변환하지 않는다.
5. `quality.preview`, `productionEligible`, `realtimeApplied`, `missingInputs`, `fallbackReasons`를 읽는다. 새 reason code는 원문 코드 대신 안전한 일반 문구로 표시하고 진단 로그에는 개인정보 없이 기록한다.
6. 공개 응답에 정의되지 않은 leg/geometry 세부값이 필요하면 BE에 DTO 확장을 요청한다. 카카오 raw JSON을 앱에서 직접 해석하는 우회 구현은 하지 않는다.

| 요청 | FE 전달 | 정상 후 행동 | 오류 후 행동 |
|---|---|---|---|
| 일정 등록/수정 | 기존 request 필드+P/D·좌표·반복 출발 | 서버 저장값 반영 후 latest 조회 | 필드 검증 오류를 해당 입력에 표시 |
| `POST /api/v1/schedules/{id}/recommendations` | 기존 계약의 재평가 요청 | 상태 조회로 전환 | 연속 탭/재시도로 중복 계산 요청 금지 |
| `GET .../recommendations/latest` | scheduleId | 버전 확인 후 최신 결과 반영 | 네트워크 실패 표시·유한 backoff |
| `POST .../recommendations/accept` | evaluationId | 서버 P/선택 경로 재조회 | 409이면 최신 제안 재확인, 자동 재수락 금지 |
| `POST /api/v1/schedules/{id}/departure` | 기존 계약 및 BE 합의 값 | IN_PROGRESS 응답 확인 | 실패를 출발 확인 성공으로 표시하지 않음 |
| 신규 제안 `POST .../journey/position` | 좌표·정확도·관측시각·sequence·확인된 상태 | 최신 이동 상태 표시 | 권한/위치 불량과 서버 실패를 구별 |

endpoint의 최종 request/response는 공통 계약과 BE Controller로 검증한다. 표의 위치 endpoint는 아직 구현 대상이다.

**완료 시험:** 정상 v2/구버전/unknown enum/null ETA/PREVIEW/잘못된 JSON parser 테스트. BE와 동일 fixture를 사용해 타임존·초 단위를 대조한다.

## 4. FE02 — 일정·반복 일정 입력

1. 출발지와 목적지를 선택하고 사용자 좌표를 기존 장소 모델로 저장한다. 경도·위도 순서를 API 계층에서 한 번만 변환한다.
2. P와 D는 각각 날짜·시간을 입력한다. P<D를 사전 검증하고 서버 오류를 최종 기준으로 표시한다. 자정 일정에서 출발 날짜가 전날인 경우도 허용한다.
3. 반복 일정은 도착 localTime과 출발 plannedDepartTime/DayOffset을 별도 입력한다. 전날/당일 선택을 사용자가 이해하도록 날짜 예제를 함께 보여 준다.
4. 저장 중 버튼 중복 실행을 막고 실패 시 입력을 보존한다. 등록 성공은 저장 완료이며 경로 계산 완료와 구분한다.
5. 기존 P=null 일정은 ‘출발 예정시각 입력 필요’로 편집을 유도한다. 클라이언트가 D-duration으로 P를 채우지 않는다.
6. 편집 진입 시 plannedDepartAt을 읽고 미수락 proposedDepartAt을 입력란에 넣지 않는다. 수락 후 현재 계획이 바뀌었으면 서버 저장값을 사용한다.

**화면 완료 기준:** 입력 P/D·출발 날짜·반복 offset이 서버에 그대로 전달되고 제목만 수정했을 때 출발시각이 바뀌지 않는다. 23:30→다음 날 01:00, 동일 시각 오류, 과거 D, 서버 지역 거부를 시험한다.

## 5. FE03 — 상태별 추천 카드

아래 문구는 예시다. 시간과 사유는 해당 evaluationId 응답에서 가져오며 화면에서 역산하지 않는다.

| decision/상태 | 표시할 내용 | 사용자 행동 |
|---|---|---|
| PENDING/RUNNING | 최초면 계산 중, 이전 결과가 있으면 확인시각과 갱신 중 | 중복 재평가 요청 억제 |
| KEEP_DEPARTURE | 현재 P, ETA, 입력 출발 유지 | 별도 변경 없는 경우 수락 불필요 |
| KEEP_DEPARTURE + 경로 변경 | P 유지, 현재/제안 경로 구별, 새 ETA | 유효한 경로 제안 수락 |
| ADVANCE_DEPARTURE | 현재 P·새 출발·ETA·T, 변경 이유 | 품질/유효성 통과 시 수락 |
| DEPART_NOW | 계획 출발 경과, 지금 출발 ETA | 출발 확인·최신 대안 확인 |
| MARGIN_UNAVAILABLE | ‘10분 여유는 어렵지만 08:25 출발 시 08:55 도착 예상’ | 가능한 대안 확인 |
| ON_TIME_UNLIKELY | 가장 빠른 ETA, 지각 예상, 검증된 범위 | 대안 확인; 과거 출발 권고 금지 |
| NO_USABLE_ROUTE | 운행 종료/확정 통제 등 근거, 지원 범위 | 상황 확인·일정 편집 |
| DATA_INSUFFICIENT | 필요한 정보와 마지막 확인시각 | 입력 보완/재조회 가능한 경우만 안내 |
| SEARCH_INCOMPLETE | 현재 발견한 대안, 탐색 미완료 | 확정 최신 출발로 표시 금지 |
| DEPARTURE_REQUIRED | 사용자 출발시각 누락 | 일정 편집 |

ETA=D이면 지각초는 0이지만 일정 시작 전 도착은 아니다. ‘정시 도착 안전’이라고 표현하지 말고 ‘일정 시작과 동시에 도착 예상, 여유 없음’으로 표시한다. 표시 분 올림은 허용하되 상태 판정은 서버 decision을 따른다.

품질 표시는 decision과 함께 나온다. PREVIEW면 검증용 표시와 수락 차단, stale이면 마지막 확인시각과 갱신 필요, 일부 realtimeApplied면 해당 구간만 실시간 적용으로 표시한다. 모든 구간이 최신인 것처럼 전체 ‘실시간’ 배지를 붙이지 않는다.

## 6. FE04 — 경로 상세·도보·환승·원인

**받을 값:** BE가 정규화한 leg 순서, role, 시작/종료시각, durationSeconds, 수단·노선·승하차 이름, geometry, durationSource, 품질·원인. 없으면 임의 시간을 만들어 표시하지 않는다.

1. ACCESS_WALK, WAIT, BUS/SUBWAY RIDE, TRANSFER_WALK, EGRESS_WALK를 시간순으로 렌더링한다.
2. 도보 경로선은 검증된 geometry가 있을 때 그린다. geometry가 없으면 실제 보행 가능 길인 것처럼 직선을 안내하지 않는다.
3. 대기와 환승 도보를 따로 표시한다. BUS/SUBWAY 전체 step에 포함된 시간과 상세 leg 합을 UI에서 중복 합산하지 않는다.
4. ‘도보 API’, ‘시간표 기준’, ‘실시간 도착예측’, ‘원본 경로 기준’ 등 사용자 이해에 필요한 출처를 표시한다. 내부 eventId·SHA는 일반 화면에서 숨긴다.
5. 사건 원인은 서버 causes의 검증된 노선/구간·이유·확인시각을 사용한다. 주변 사고 제목만 보고 경로 지연 이유로 붙이지 않는다.
6. 오래된 공지는 ‘최신 상황 확인 불가’이며 ‘통제 해제’가 아니다. 알 수 없는 사유는 ‘원인 확인 중’으로 표시한다.

**완료 시험:** 버스→도보→지하철, 처음/마지막 도보 누락, 25시 운행 날짜, 통제 범위 미확정, null geometry, providerTotal fallback을 같은 fixture로 검증한다.

## 7. FE05 — polling·응답 순서·수락

1. 화면 진입·복귀 때 latest를 조회하고 화면 비활성/종료 일정에서는 polling을 중지한다. 앱 polling과 서버 D 기준 재계산은 별개다.
2. 동일 schedule의 응답은 inputVersion을 우선 비교한다. 과거 입력 응답은 버리고 같은 입력의 새 결과 순서는 BE 계약의 computedAt/evaluation 식별 규칙으로 판단한다. evaluationId 문자열을 정렬해 최신을 결정하지 않는다.
3. 입력 변경/추천 수락 이후 이전 비동기 응답이 도착해도 화면을 되돌리지 않는다. 요청 generation 토큰 등으로 오래된 콜백을 무효화한다.
4. 수락 버튼은 유효한 추천·productionEligible·미출발 상태일 때 제공한다. 최종 판정은 서버가 하며 409이면 재조회한다.
5. 수락 요청은 evaluationId만 보내고 FE가 계산한 출발시각/임의 routeId를 넣지 않는다. 성공 후 서버의 plannedDepartAt과 selectedRouteId를 다시 표시한다.
6. 네트워크 단절 시 마지막 결과를 유효한 최신 추천처럼 표시하지 않는다. 조회 재시도와 변경 수락 재시도를 구별하고 수락을 자동 반복하지 않는다.

**완료 시험:** 응답 A/B 순서 뒤집힘, 수락 중 수정, 유효기간 경과, 앱 백그라운드 복귀, 동일 버튼 연속 탭. 홈·일정 상세·알림에서 같은 평가의 P/ETA가 일치해야 한다.

## 8. FE06 — 알림 수신·이유·최신 결과 연결

알림 payload는 BE가 발행한 notificationId, scheduleId, evaluationId와 정제된 표시 내용이다. 제목/본문 raw 공지를 앱에서 재분석하지 않는다.

1. notificationId로 중복 수신 표시를 억제한다. 같은 사건의 악화/해제처럼 새로운 의미의 알림은 서버 ID와 전이 기준을 따른다.
2. 탭하면 scheduleId의 최신 상태를 조회한다. 과거 evaluationId는 발생 당시 설명으로 볼 수 있지만 그 추천을 즉시 수락시키지 않는다.
3. 일정 수정/취소/도착/출발 후에는 현재 상태를 안내한다. 이미 이동 중인 사용자에게 ‘일찍 출발’ 버튼을 보이지 않는다.
4. 알림 비허용/장치 토큰 없음 상태에서도 인앱 최신 평가 조회는 동작해야 한다. push 성공을 사용자가 확인했다는 증거로 기록하지 않는다.

**완료 시험:** 중복 push, 만료 추천 딥링크, 다른 사용자 일정 접근 실패, 앱 종료/실행 중 수신, 알림 off에서 수동 조회.

## 9. FE07 — 출발·위치·도착

1. 사용자가 출발 확인을 누르면 서버 endpoint로 전송하고 성공한 actualDepartAt/journeyState를 반영한다. P가 됐다는 이유로 자동 출발 처리하지 않는다.
2. 위치가 필요한 이동 중 기능을 사용할 때 목적과 중단 방법을 설명하고 기존 플랫폼 권한 흐름으로 동의받는다. 위치 권한 거절도 정상 화면 분기로 만든다.
3. 위치 관측값은 longitude/latitude/accuracyMeters/observedAt/sequence와 함께 보낸다. sequence 재시작·동일 관측 재전송 처리는 BE와 계약을 정한다. 클라이언트가 차량 ID를 좌표 근접으로 추측하지 않는다.
4. 위치가 stale하거나 정확도가 낮으면 ‘현재 위치 확인 필요’를 표시하고 정밀 우회 안내를 주장하지 않는다. 재전송 queue는 보관/만료 정책에 따라 제한한다.
5. 탑승 여부는 확인된 사용자 행동/근거를 사용한다. 도착 확인 성공 후 위치 전송·polling을 중지하고 ARRIVED를 반영한다.

**완료 시험:** 출발 두 번, 출발 실패, 위치 권한 거절/회수, 관측 순서 역전, 앱 재시작, 도착 후 전송 중단. 위치 전송 주기·정확도 임계값은 승인된 정책을 받아 사용하며 임의로 고정하지 않는다.

## 10. 다른 파트와 주고받을 산출물

| 방향 | 전달물 | 인계 완료 조건 |
|---|---|---|
| BE/ML→FE | v2 schema, 상태별 fixture, 오류 코드, 수락/이동 API, 품질·원인 문구 기준 | parser·화면 테스트와 계약 일치 |
| FE→BE/ML | 입력 payload, 편집/반복/자정 시나리오, 요청 순서역전 재현 | 같은 입력이 서버에서 같은 결과로 해석됨 |
| DATA→BE/ML→FE | 실제 제공 범위·최신성·결측·fallback 이유 | FE는 공개 DTO만 소비하고 Redis/raw API에 직접 연결하지 않음 |

API 미완료 기간에는 BE와 합의한 합성 fixture를 써서 화면을 개발한다. 시연용 합성 데이터는 실제 운행 결과로 표시하지 않는다. `client/lib/services/recommendation_api.dart`의 로컬 D-duration 역산은 서버 v2 연결 검증 후 제거한다.

완료 PR에는 변경 화면, 입력/응답 예제, 정상·null·stale·preview·409·네트워크 실패 테스트 결과, 남은 API/데이터 의존성을 포함한다. 검증은 `client`에서 `flutter test`와 변경 범위의 정적 분석을 수행한다. 이 문서 작성은 해당 테스트 통과를 의미하지 않는다.
