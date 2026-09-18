# 조건부 출발 추천 — BE/ML·FE·DATA 통합 계약

- 상태: **구현 전 통합 계약안**. 현재 endpoint·DB·Kafka·Redis·모델이 아래 내용을 모두 구현했다는 의미가 아니다.
- 범위: 일정 등록 → 후보 분해/매핑/도보 → 여정 계산 → 조건부 출발 → 재계산 → 원인 알림 → 이동 중/종료.
- 이 문서는 파트 간 명칭·입출력·책임의 통합 기준이다. 구현 전 기계 schema와 테스트 fixture를 함께 작성한다.
- 원천 확보 상태와 실제 raw 필드는 [데이터 가이드](arrival-deadline-data-guide.md), 계산 의미는 [계산 정책](arrival-deadline-calculation.md), 파일별 작업은 [공통 구현 계획](arrival-deadline-implementation-plan.md)을 따른다.

## 1. 책임과 데이터 경계

| 경계 | 생산자 → 소비자 | 생산자의 책임 | 소비자의 책임 |
|---|---|---|---|
| 일정 입력 | FE → BE | 사용자 P/D·장소·반복 입력을 보존 | 검증·소유권·DB 저장·revision |
| 후보/도보 | 외부 제공자 → BE | 외부 계약 범위의 후보 응답 | 권한·단위·좌표·비용 범위 검사 |
| 원천 관측 | 제공기관 → DATA | 공개/제휴 명세 범위 자료 | 확보 기록·raw 보존·정규화·품질 |
| 매핑 artifact | DATA → BE/Spark | 출처·SHA·적용일·coverage | 같은 버전 사용·불일치 거부 |
| 실시간/사건 | DATA → BE | 시각·ID·상태·원인·TTL 계약 | freshness·통과시각·후보 영향 검사 |
| 예측 | ML → BE | 검증된 target·버전·지원 범위 | 승인/결측 확인·fallback·여정 연결 |
| 최종 결과 | BE → FE | decision·ETA·품질·수락 가능성 | 계산값 그대로 표시·오래된 결과 억제 |
| 알림 | BE → FE | 변화 판정·원인 검증·outbox | notificationId dedup·최신 결과 재조회 |
| 실제 이동 | FE → BE | 동의한 위치·사용자 확인 상태 | 관측 품질 검증·남은 여정 평가 |

DATA는 사용자별 경로를 생성하지 않고 서비스 MySQL에 직접 쓰지 않는다. FE는 Redis/공공 교통 API/ML에 직접 연결하지 않는다. BE가 최종 추천과 사용자 DB 쓰기의 소유자다.

## 2. 사용자 결정과 계산 불변식

- P=`plannedDepartAt`, D=`arriveBy`이며 사용자 입력은 P<D다.
- 이용 가능한 P 출발 후보 ETA<D이면 P를 유지한다. 같은 P의 대안 경로가 가능하면 경로 변경만 제안할 수 있다.
- 비교 가능한 후보가 ETA>=D이면 T=D-600초로 설정하고 ETA<=T인 가장 늦은 실행 가능한 출발을 탐색한다.
- 600초는 목표시각을 한 번 앞당기는 값이다. 소요시간/구간에 다시 가산하지 않는다.
- 정보 부족을 ‘모두 늦음’, 탐색 예산 소진을 ‘경로 없음’으로 바꾸지 않는다.
- 후보 범위·시간 해상도 내 최선만 주장한다. 외부 후보 밖 전 교통망 최적해를 보장하지 않는다.
- 추천 수락 전 P/선택 경로를 자동 변경하지 않는다. 과거 P를 현재 실행 가능한 출발로 반환하지 않는다.

## 3. 공통 시간·단위·ID·버전

| 항목 | 공통 규칙 |
|---|---|
| 시각 | ISO 8601 +09:00, Asia/Seoul. 날짜 포함, 초 단위 판정 |
| 기간/거리 | duration/delay/buffer는 초, 거리 m. 원천 분/kph는 경계에서 명시 변환 |
| 좌표 | WGS84 경도·위도. 원천 투영좌표는 CRS 확인 후 변환 |
| 원천시각 | sourceGeneratedAt, collectedAt, observedAt, computedAt을 구별. 없는 원천시각은 null |
| 운행일 | serviceDate와 달력 날짜 구별. 25시는 해당 운행일 다음 날 01시 |
| ID | providerNamespace+provider ID와 canonical segmentId 구별. 문자열 선행 0 보존 |
| 중복 | eventId=전달 중복; incidentKey=사건 동일성; revision=사건 내용 변경 |
| 입력 버전 | DB input_revision/내부 inputRevision을 공개 응답 inputVersion으로 변환 |
| 품질 버전 | master/timetable/crosswalk/baseline/model/policy 및 artifact SHA 보존 |
| 유효성 | validUntil 이전이고 해당 범위/버전이 유효한 경우만 사용; Redis 존재만으로 fresh 아님 |

## 4. 용어 충돌 해소와 공개 응답 기준

기존 작업 카드의 내부 DTO 이름은 그대로 HTTP/Kafka 필드가 아니다. 신규 구현은 아래 기준으로 계약 테스트를 고정한다.

| 이전 설명/내부 표현 | 통합 기준 |
|---|---|
| resultVersion=2 | 공개 결과는 `schemaVersion="schedule-evaluation-v2"` 사용 |
| executionStatus / 내부 작업 상태 | 공개 결과는 `state`; 내부 READY/RETRY/lease 상태는 DB 내부 전이로 별도 관리 |
| inputRevision | 공개 결과 `inputVersion`; 내부/DB 이름과 adapter로 연결 |
| estimatedArriveAt / ETA | 공개 결과 `estimatedArrivalAt` |
| quality=LIVE/PREVIEW/STALE 등의 설명 | 공개 `quality`는 아래 객체. 상황 이름을 같은 필드의 문자열로 보내지 않음 |
| SKIPPED_NON_SERVICE / SKIPPED | 신규 poll 계약은 `SCHEDULED_SKIP`. 생략이 비운행 증거라는 뜻 아님 |

원천 producer의 기존 enum은 일괄 덮어쓰지 않는다. 신규 schema/adapter를 먼저 배포하고 호환 전환한다. 이 문서는 기존 실행 계약을 묵시적으로 바꾸지 않는다.

## 5. FE → BE 일정 입력·수락

기존 일정 create/update DTO의 장소 구조를 유지하면서 P/D 필수 검증을 추가한다. 다음은 **정책 입력 부분만** 보여 주는 예제이며 전체 등록 request가 아니다.

```json
{
  "plannedDepartAt": "2026-09-17T08:30:00+09:00",
  "arriveBy": "2026-09-17T09:00:00+09:00"
}
```

BE는 사용자 소유권·미래 D·지원 좌표·P<D를 검증한다. 반복 출발은 plannedDepartTime+plannedDepartDayOffset(초기 0/-1)으로 생성한다. 기존 P=null은 DEPARTURE_REQUIRED다.

수락 request는 evaluationId를 식별자로 사용한다. 수락 시 소유권·inputVersion/eventRevision·validUntil·productionEligible·미출발·실행 가능성을 다시 검사한다. 오래된 결과는 409, 입력 오류는 기존 error envelope의 필드 오류로 반환한다. 재시도 수락의 멱등성과 오류 코드는 BE/FE fixture에서 확정한다.

## 6. DATA → BE 실시간·사건·마스터

### 6.1 확보 게이트

모든 source는 officialUrl,serviceName,acquisitionStatus,sampleRef/SHA,coverage,원천시각 필드,승인 쿼터,검증일을 인계한다. 상태는 미확인/신청 중/샘플 확보/운영 검증을 구별한다. 운영 검증이 안 된 source는 그 범위의 실시간 보증에 사용하지 않는다.

열차 운행 crosswalk, 차량별 실제 통과기록, FULL 통제/실제 해제, 모든 역 미래 예측은 현재 확보됐다고 가정하지 않는다. 원천이 없는 내부 요구 필드는 null/unknown과 기능 제한으로 표현한다.

### 6.2 관측·매핑·사건의 최소 계약

| 자료 | 필수 의미 | null/실패 의미 |
|---|---|---|
| 버스 도착 | namespace/route/stop,원천시각,제공 차량/rank,ETA/초,상태코드,유효성 | 원천시각 없으면 fresh ETA 확정 불가 |
| 지하철 정차예측 | serviceDate,trip/stop,방향/패턴,정적 시각,실시간 시각,매핑/버전 | 제공되지 않는 predicted departure/alight는 null |
| 마스터 | version/SHA/validity,namespace,node/edge/occurrence,방향,coverage | 모호하면 EXACT 금지 |
| 사건 | incidentKey/revision,출처,kind/lifecycle,기간,제약 범위,원천 refs | FULL/방향/차종 미확인은 UNKNOWN |
| 영향 | incident revision+mappingVersion,segment/trip/stop 범위,matchStatus,근거 | PARTIAL은 확정 차단 불가 |
| 기준선 | segment+dow/hour/weather+version,p50/p80/p95,sampleCount,기간 | 실측/표본 미확보면 계산 불가 |

신규 poll-status enum은 `SUCCESS | EMPTY_COMPLETE | PARTIAL | FAILED | RATE_LIMITED | SCHEDULED_SKIP`다. `completeSnapshot`, pagesExpected/pagesReceived, rowCount, sourceGeneratedAt, nextAllowedAt을 함께 기록한다. 원천이 현재 활성 전체 목록을 보증하지 않으면 EMPTY_COMPLETE도 사건 종료의 충분조건이 아니다.

Kafka/Redis 물리 키·schema는 기존 `topics.yaml`, `redis-keys.yaml`, events schema를 기준으로 버전 변경한다. 문서에 나온 내부 DTO를 기존 additionalProperties=false schema에 그대로 발행하지 않는다.

## 7. ML → BE 예측

기존 [ML HTTP 계약](ml-serving-http.md)을 유지하고 확장 시 버전을 올린다. batch 요청의 segment_key는 결과 연결 키, provider namespace/route/stop/occurrence는 DATA의 검증된 마스터 ID다. service_date/hour_slot은 평가 대상 edge 진입 예상시각에서 생성한다.

응답의 model_version,target,지원 기간,production flags,status,reason,mean_seconds,표본/결측 정보를 검사한다. 평균을 분위수로 표시하지 않는다. 버스 step의 일부 edge 실패는 승인된 전체 step fallback으로 처리하며 source 전체시간과 부분 예측을 임의 혼합하지 않는다. 모델 버전이 탐색 중 바뀌면 평가를 분리/재시도한다.

정답 원천·학습/예측 feature 일치·시간순 평가가 확인되지 않으면 PREVIEW 제한을 유지한다. 데이터 가이드의 목표 feature 목록이 현재 모델 HTTP 입력에 자동 추가되는 것은 아니다.

## 8. BE 내부 여정과 공개 v2 결과

### 8.1 내부 계산 계약

`evaluate(candidate,departure,snapshot)`는 동일한 snapshot으로 정방향 계산한다. snapshot에는 asOf·원천값/시각·모델/마스터/시간표/정책 버전을 고정한다. 출발별 차량 선택과 진입시각은 매번 다시 계산한다.

leg는 ACCESS_WALK/TRANSFER_WALK/EGRESS_WALK/WAIT/RIDE 등의 계산 역할, startAt/endAt,durationSeconds,source,quality 근거,causeRefs를 갖는다. 외부 provider step과 내부 leg는 1:1이 아닐 수 있다. 비용 범위는 COMPONENTIZED/PROVIDER_TOTAL/PARTIAL로 구별하고 UNASSIGNED 시간을 대기로 단정하지 않는다.

### 8.2 공개 결과

공통 구현 계획 2.2절의 전체 명세가 기준이다. 필수 그룹은 다음과 같다.

| 그룹 | 필드 | 소비 규칙 |
|---|---|---|
| 식별/상태 | schemaVersion,evaluationId,scheduleId,inputVersion,eventRevision,state | 옛 입력 결과가 새 화면을 덮지 않음 |
| 정책 | decision,triggerReason,bestKnownDecision | 탐색 미완료·정보 부족·불가를 구별 |
| 사용자/목표 | userInputDepartAt,plannedDepartAt,arriveBy,effectiveDepartAt,targetArriveAt,appliedBufferSeconds | 직접 입력·수락 계획·추천을 분리 |
| 결과 | proposedDepartAt,estimatedArrivalAt,durationSeconds,latenessSeconds,route IDs | unknown ETA/기간은 null, 0으로 채우지 않음 |
| 판정 | arrivesBeforeSchedule,meetsAdjustedTarget,departureMissed | 각각 <D,<=T 의미; FE 재판정 금지 |
| 근거 | quality,causes,search,versions | 일부 실시간/preview/fallback·탐색 범위 표시 |
| 시각 | asOf,computedAt,validUntil,nextEvaluationAt | stale·만료 처리 |

공개 `quality` 객체는 `preview:boolean`, `productionEligible:boolean`, `realtimeApplied:boolean`, `missingInputs:string[]`, `fallbackReasons:string[]`와 confidence 근거를 가진다. realtimeApplied=true는 일부 근거가 반영됐다는 뜻이며 전체 leg 실시간 보증이 아니다. confidence의 수치·등급 세부 구조는 기계 schema에서 확정하고 미확보면 임의 1.0을 넣지 않는다.

정상 유지 예제 등 전체 응답은 공통 계획 fixture를 사용한다. 아래는 일관성 시험용 **부분 결과**다.

```json
{
  "schemaVersion": "schedule-evaluation-v2",
  "state": "READY",
  "decision": "ADVANCE_DEPARTURE",
  "plannedDepartAt": "2026-09-17T08:30:00+09:00",
  "arriveBy": "2026-09-17T09:00:00+09:00",
  "targetArriveAt": "2026-09-17T08:50:00+09:00",
  "appliedBufferSeconds": 600,
  "proposedDepartAt": "2026-09-17T08:20:00+09:00",
  "estimatedArrivalAt": "2026-09-17T08:50:00+09:00",
  "durationSeconds": 1800,
  "arrivesBeforeSchedule": true,
  "meetsAdjustedTarget": true
}
```

## 9. 실행·이동·알림 상태

공개 평가 state는 `PENDING/RUNNING/READY/FAILED/STOPPED/DEPARTURE_REQUIRED`를 사용하고 신규 확장은 schema로 합의한다. 내부 작업 RETRY/lease 상태를 그대로 공개하지 않는다. decision은 계산 정책의 8종과 SEARCH_INCOMPLETE 시 bestKnownDecision을 따른다.

journeyState는 NOT_STARTED→IN_PROGRESS→ARRIVED다. 취소는 일정 lifecycle로 관리한다. 출발 확인은 실제 확인시각을 한 번 저장하고 출발 전 알림을 취소한다. IN_PROGRESS에서는 현재 위치·확인된 탑승 상태의 남은 여정을 평가하며 과거 출발 조정을 중단한다.

기본 tick은 D-60,-50,-40,-30,-25,-20,-15,-10,-9…0분 및 등록 즉시다. 수정/수락/관련 사건은 별도 wake다. 과거 tick은 재생하지 않는다. 장거리 출발 전 추가 점검은 아직 제품 정책 제안이며 확정 기본 주기에 몰래 추가하지 않는다.

알림은 결과 저장과 outbox 생성을 같은 transaction에 넣고 전송은 밖에서 수행한다. FE는 notificationId로 중복 표시를 억제하고 딥링크에서 최신 결과를 재조회한다. 원인 안내에는 공식 사건+직접 영향 근거가 필요하며 불명확하면 원인 확인 중이다.

## 10. 세 파트 공통 인수 시험

| fixture | DATA | BE/ML | FE |
|---|---|---|---|
| P08:30,D09:00,ETA08:59:59 | 시간·단위 보존 | KEEP,buffer0 | 출발 유지 표시 |
| ETA09:00,새 출발08:20→08:50 | 출발별 근거 고정 | T equality 성공,buffer600 | P/추천/ETA 구별 |
| mkTm08:00,exps120,수집08:00:30 | ETA08:02 | 대기 계산에 원천시각 사용 | 최신성 표시 |
| 시간표 train과 실시간 ID 모호 | UNMAPPED | 정적 추정/fallback | 전체 실시간 표시 금지 |
| FULL·방향·시간 검증 통제 | 사건+EXACT 영향 | 후보 제외·원인 | 구간과 대안 표시 |
| poll 실패·TTL 만료 | STALE/UNKNOWN | 해제 확정 금지 | 확인 불가 표시 |
| 평가 중 일정 수정 | 동일 원천 보존 | 옛 revision 저장/수락 거부 | 옛 응답 반영 금지 |
| FCM timeout/재전송 | 해당 없음 | outbox retry | notificationId dedup |
| 실제 통과 정답 없음 | 미확보 기록 | ML/baseline 운영 주장 금지 | preview/정보 부족 표시 |

## 11. 계약 변경과 활성화

문서→schema/fixture→producer/consumer 구현→호환 테스트→범위 제한 활성화 순서다. BE/ML·FE·DATA 각각 담당 소비 경계를 검토한다. 기존 필드 타입/enum/단위 변경은 adapter 또는 새 버전으로 전환한다.

운영 준비는 데이터 확보, 매핑, 모델 승인, 시간 분해, freshness/쿼터, 알림 복구, FE 상태 처리를 별도 평가한다. 하나가 통과했다고 전체 기능을 완료 처리하지 않는다. 이 문서에 기록된 미확정 값은 API 권한·실제 샘플·측정 또는 팀 합의 없이 숫자/성공 상태로 채우지 않는다.
