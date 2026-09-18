# 조건부 출발 조정 — 파트별 구현 계획과 인수 기준

- 수정일: 2026-09-16. **구현 전 작업 명세**이며 이 변경은 문서만 수정한다.
- 사용자 확정: 사용자가 출발 예정시각 P와 일정 시작시각 D를 입력한다. `ETA(P) < D`이면 P 유지, `ETA(P) >= D`이면 `D−10분`까지 도착할 수 있는 최신 출발 탐색.
- 정책 본문: [계산 흐름·예외](arrival-deadline-calculation.md). 원천 전체 명세: [데이터 출처·raw 필드·정규화 컬럼·품질 가이드](arrival-deadline-data-guide.md).
- 코드 확인 기준: `b994af1` 및 현재 관련 파일. 문서 브랜치 `feature/dt/S15P21E202-46-arrival-deadline-contracts`. 기존 MR은 문서 작업이며 아래 전체 기능 구현의 완료 MR이 아니다.
- 후속 대조: 원격 `dt/dev`의 `f6905b9`까지 수집·Bus Silver 변경을 확인했다. 기존 문서 MR 병합 후 이번 수정은 후속 문서 브랜치에서 제공한다. 아래 수집 계획은 이미 추가된 재시도·중복 억제·Silver 처리를 재사용한다.
- 아래 **현재**는 확인한 코드, **목표/추가**는 구현할 계약이다. 신규 파일명·컬럼은 팀이 그대로 작업을 나눌 수 있도록 제안한 명세이며 이미 존재한다는 뜻이 아니다.
- 이전 계획의 출발 입력 제거, `DEPARTURE_REQUIRED` 삭제, `planned_depart_at` 폐기 제안은 철회한다.

## 문서 탐색 — 세 파트 기준

착수 순서는 [팀 실행 가이드](arrival-deadline-team-guide.md), 공통 입출력·명칭·상태 기준은 [통합 계약](arrival-deadline-integrated-contract.md)을 먼저 읽는다. 아래 기존 작업 ID는 상세 설계를 추적하기 위해 유지한다.

| 담당 파트 | 상세 작업 문서 | 관련 작업 |
|---|---|---|
| BE/ML | [BE/ML 작업 가이드](arrival-deadline-be-ml-work-guide.md) | B01/B02/B02-W/M01/B03/N01, J01 서버 |
| FE | [FE 작업 가이드](arrival-deadline-fe-work-guide.md) | F01, J01 앱 |
| DATA | [DATA 작업 가이드](arrival-deadline-data-work-guide.md) | D01/D02/D03, O01 원본·Replay |

각 가이드는 목적·수정 파일·선행 데이터/출처·처리 순서·실패 대응·시험·인계를 포함한다. 공개 필드 이름은 통합 계약의 명칭 대응표를 따른다. 내부 예시 DTO를 별도 공개 계약처럼 구현하지 않는다.

## 0. 구현 전에 데이터부터 확보한다

**수집기·매핑기·모델 코드가 존재해도 실제 데이터를 보유하거나 운영에 사용할 수 있다는 뜻이 아니다.** 이 계획의 snapshot, crosswalk, 실측, 기준선 입력을 이미 준비된 데이터로 가정하지 않는다. [데이터 가이드 0절](arrival-deadline-data-guide.md#0-먼저-확인할-데이터-확보-현황과-확보-작업)의 실시간/정적/미확정 데이터 목록을 선행 작업으로 수행한다. 원천별 공식 출처·필요 필드·신청/확보 방법·현재 증거·미확보 시 제한을 그 표에 모았다.

| 구현 작업 | 먼저 확보할 데이터 / 출처 ID | 선행 작업이 끝났다는 증거 | 미확보 상태에서 가능한 작업 |
|---|---|---|---|
| 후보 분해·마스터 매핑 | S02 실제 카카오 응답, 버스/지하철 마스터 원본 및 manifest | 현재 API 권한·샘플, 적용일·namespace·SHA·운영 적재 확인 | fixture parser/매핑 테스트, 실서비스 EXACT 주장 불가 |
| 접근·이탈 도보 | S02-W 제휴 도보 권한, 승·하차 endpoint 근거 | 성공/실패 응답, service 권한·쿼터, node 좌표 검증 | 도보 Client 인터페이스와 fallback 구현 |
| 버스 대기 | S03 도착예측, S04 위치 보조 | mkTm/exps/차량/정류장 필드·코드·신선도·지원 노선 | provider fallback; 실시간 대기 기능 비활성 |
| 버스 ML·구간 기준선 | S13 학습 원본·정답·운영 모델, 실제 통과 fact | 원본 manifest와 정답 정의, 지원 범위, 승인·표본 증거 | PREVIEW/API fallback, 신규 실측 분위수 생성 불가 |
| 지하철 계산 | S05 시간표, S06/S07 실시간, 운행 crosswalk | 운행일/방향/열차/정차순서 일치와 범위 보고 | 확보한 시간표 범위의 추정, 실시간 결합 제한 |
| 통제 영향·지연 원인 | S08/S09/S12-N 공지·사건, S10 방향 도로 매핑 | 적용 수단·방향·기간·FULL/해제의 공식 근거 | 관련 공지/영향 가능성 표시, 확정 차단 금지 |
| 부분 통제 소요시간 보정 | S14 링크 속도 또는 운영 승인 모델/제공자 새 ETA | raw 필드·단위·관측시각·매핑·비용 범위 검증 | 임의 지연분 가산 금지 |
| 날씨·미래 시간대 | S11 관측, S11-F 예보, 공휴일/특별운행 달력 | 발표·대상시각과 적용일·코드표 검증 | 결측 표시; 미래 실측/평일 운행 단정 금지 |
| 알림·이동 중 | 검증된 cause와 위치·탑승 상태 | 사건 직접 영향·freshness, 사용자 동의/상태 정확성 | 원인 미확인 안내·위치 확인 필요 표시 |

기능 개발 순서는 **출처 확정 → 권한/자료 확보 → raw fixture → 필드·단위 검증 → 정규화 → ID 매핑 → 계산 → 인수 테스트**다. 미확정 제공기관 데이터를 확보하지 못하면 해당 기능을 제한하고 계약에서 null/unknown을 처리한다. 데이터가 없다는 이유로 운영자 통과기록·열차 대응표·실제 통제 해제를 내부에서 추측하여 생성하지 않는다.

각 작업 카드의 ‘선행 입력’은 확보 요청 목록이다. 확보 담당은 source-acquisition-register에 실제 증거를 제출하고 Backend 담당은 상태가 운영 검증인 범위만 활성화한다. ML 추가 입력은 [ML 데이터 결정사항](ml-data-decision.md)의 목표 범위와 현재 HTTP 모델 소비 필드를 구별한다.

## 1. 담당별 작업 묶음과 전달물

| 작업 ID | 주 담당 | 선행 | 완료 산출물 |
|---|---|---|---|
| C01 | Backend·Client·데이터 | 없음 | P/D 의미, 조건부 정책, v2 응답 fixture·schema |
| B01 | Backend 일정 | C01 | 입력 유지·검증, 작업 영속화, 반복 일정 출발 입력, migration |
| B02 | Backend 추천 | C01 | 공통 여정 계산기·증거 스냅샷·조건 분기·제한된 최신 출발 탐색 |
| B02-W | Backend 경로·외부 연동 | C01,B02 | 승·하차 endpoint 검증, 제휴 도보 API, 접근/환승/이탈 WALK 역할과 fallback |
| D01 | Collector | C01 | S03~S12 원천·필드·수집 상태·키 예산·신선도 |
| D02 | 데이터 마스터 | C01 | 시간표·열차 crosswalk·도로 방향 매핑·manifest·검증 corpus |
| D03 | Spark | D01,D02 | 사건 lifecycle·영향 edge·구간 상태·변경 이벤트·Replay |
| M01 | ML·Backend 예측 | B02 | 입력별 배치/캐시·모델 범위 검증·PREVIEW/운영 승인 경계 |
| B03 | Backend 작업 처리 | B01,B02,D03 | D 기준 tick·사건 wakeup·동시성·결과 원자 저장 |
| N01 | Backend 알림 | B03 | 변경 원인·outbox·재시도·중복 억제·인앱 설명 |
| F01 | Client | C01,B01,B02 | P/D 입력 유지·서버 결과·수락·불확실성·상태 화면 |
| J01 | Backend·Client | B03,F01 | 실제 출발·위치 상태·남은 여정 재탐색·종료 |
| O01 | Archiver·운영·QA | D01~J01 | 원본 보존·재현·성능·지표·단계 활성화 |

데이터 담당은 Collector/Spark/계약·데이터 artifact를 맡는다. Backend 일정·DB, Client, 인프라 파일은 해당 담당자가 구현한다. 이 문서는 인터페이스 전달물이며 데이터 작업자가 타 파트/배포 설정을 즉시 변경하라는 지시가 아니다.

## 2. 먼저 고정할 C01 계약

### 2.1 입력과 소유권

`plannedDepartAt`과 `arriveBy` 필드 이름은 유지한다. 신규 생성은 둘 다 필수, `P < D`, 미래 일정, 좌표·지역·소유권을 검증한다. update가 부분 수정인지 전체 교체인지 기존 DTO 의미를 보존하고, 적용 후 P,D의 최종 조합을 검증한다. null P의 기존 일정은 DEPARTURE_REQUIRED로 입력을 보완하게 한다. 시스템이 D에서 임의 출발을 역산해 이행하지 않는다.

- `plannedDepartAt`: 현재 사용자 계획. 직접 수정 또는 사용자의 명시적 추천 수락만 변경한다.
- `userInputDepartAt`: 마지막 직접 입력. 수락이 원래 입력 기록을 지우지 않게 추가한다.
- `proposedDepartAt`: 불변 평가 결과. 수락 전에는 plannedDepartAt을 덮지 않는다.
- `actualDepartAt`: 실제 출발 확인. P 도달을 실제 출발로 추정하지 않는다.
- `planSource`: USER_INPUT / ACCEPTED_RECOMMENDATION. 수락된 계획이 다음 평가의 P가 된다.

실행 상태, decision, 품질을 분리한다. `PENDING/RUNNING/READY/FAILED/STOPPED/DEPARTURE_REQUIRED` 같은 실행 상태와 `KEEP_DEPARTURE/ADVANCE_DEPARTURE/...` 정책 결론은 같은 enum에 섞지 않는다. PREVIEW와 DATA_INSUFFICIENT도 의미가 다르다.

### 2.2 v2 평가 응답의 필드 명세 — 추가

기존 `ApiResponse`의 data 안에 넣는다. v1의 Map 결과는 호환 adapter를 둔다. 아래 필드는 제안된 **목표 구조**이며 현재 API 응답 그대로가 아니다.

| 필드 | 타입/nullable | 의미·작성자 |
|---|---|---|
| schemaVersion | string, 필수 | `schedule-evaluation-v2` |
| evaluationId/scheduleId | string/long | 불변 결과·일정 식별 |
| inputVersion/eventRevision | long/long | 입력과 사건 상태 버전 |
| state/decision/triggerReason | enum / nullable / nullable | 처리 상태, 정책 결론, 최초/지각/통제/수정 등 원인 |
| userInputDepartAt/plannedDepartAt/arriveBy | timestamp | 직접 입력·현재 계획·일정 시작 |
| effectiveDepartAt | timestamp | P가 지나면 asOf로 대체한 평가 출발 |
| originalEstimatedArrivalAt | timestamp nullable | 이번 증거로 현재 P를 평가한 ETA. 과거 P 미평가 시 null |
| targetArriveAt | timestamp | 유지면 D, 조기 조정이면 D−600 |
| appliedBufferSeconds | int | 0 또는 600. 소요시간 가산값 아님 |
| proposedDepartAt/estimatedArrivalAt | timestamp nullable | 실행 가능한 제안 출발과 그 ETA |
| durationSeconds/latenessSeconds | long nullable | 실제 계산 초. lateness=max(0,ETA−D) |
| arrivesBeforeSchedule/meetsAdjustedTarget | boolean nullable | 각각 ETA<D / ETA<=T. 근거 없으면 null |
| selectedRouteId/proposedRouteId | long nullable | 선택 계획과 추천 대안. 자동 혼동 금지 |
| departureMissed | boolean | P가 지났으나 출발 미확인 |
| quality | object | preview, productionEligible, realtimeApplied, missingInputs, fallbackReasons, confidence 근거 |
| search | object | domain, from, until, resolutionSeconds, evaluatedCount, complete, incompleteReason |
| bestKnownDecision | enum nullable | SEARCH_INCOMPLETE일 때 발견한 후보의 조건 충족 상태. 최적 확정과 구별 |
| causes | object[] | 사건 identity/revision·source·영향 segment·공식 설명·근거 수준 |
| computedAt/asOf/validUntil/nextEvaluationAt | timestamp, 일부 nullable | 계산시각·증거시각·유효기간·다음 실행 |
| versions | object | policy/model/master/timetable/crosswalk/baseline 버전 |
| historicalFeasibleDepartAt | timestamp nullable | 별도 과거 검증이 된 설명용 값. 추천으로 사용 금지 |

시각은 응답에서 ISO 8601 +09:00, 기간은 초다. 모델 double은 내부 올림 규칙을 적용한 뒤 시간 비교한다. 분 표시는 `ceil(seconds/60)` 등 UI 표시 정책이며 비교값을 바꾸지 않는다. validity는 사용한 실시간·artifact의 유효기간과 결과 정책을 함께 적용한다. 오래된 결과를 stale 배지 없이 재사용하지 않는다.

정상 유지 예제:

```json
{
  "schemaVersion": "schedule-evaluation-v2",
  "evaluationId": "example-keep-1",
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
  "quality": {"preview": true, "productionEligible": false, "realtimeApplied": false, "missingInputs": [], "fallbackReasons": ["PROVIDER_DURATION"]},
  "search": {"domain": "SUPPLIED_ROUTE_CANDIDATES_AT_P", "evaluatedCount": 3, "complete": true},
  "causes": [],
  "versions": {"policy": "cdep-v1"},
  "asOf": "2026-09-17T08:00:00+09:00",
  "computedAt": "2026-09-17T08:00:01+09:00"
}
```

조건부 조기 출발 예제(가상 시험 데이터, 실제 모델 승인 결과가 아님):

```json
{
  "schemaVersion": "schedule-evaluation-v2",
  "evaluationId": "example-advance-1",
  "scheduleId": 101,
  "inputVersion": 3,
  "eventRevision": 5,
  "state": "READY",
  "decision": "ADVANCE_DEPARTURE",
  "triggerReason": "ARRIVAL_NOT_BEFORE_SCHEDULE",
  "plannedDepartAt": "2026-09-17T08:30:00+09:00",
  "arriveBy": "2026-09-17T09:00:00+09:00",
  "originalEstimatedArrivalAt": "2026-09-17T09:00:00+09:00",
  "targetArriveAt": "2026-09-17T08:50:00+09:00",
  "appliedBufferSeconds": 600,
  "proposedDepartAt": "2026-09-17T08:20:00+09:00",
  "estimatedArrivalAt": "2026-09-17T08:50:00+09:00",
  "durationSeconds": 1800,
  "latenessSeconds": 0,
  "arrivesBeforeSchedule": true,
  "meetsAdjustedTarget": true,
  "quality": {"preview": true, "productionEligible": false, "realtimeApplied": false, "missingInputs": [], "fallbackReasons": ["SYNTHETIC_FIXTURE"]},
  "search": {"domain": "FIXTURE_CONSTANT_DURATION", "evaluatedCount": 2, "complete": true},
  "causes": [],
  "versions": {"policy": "cdep-v1"}
}
```

예제는 핵심 필드 발췌다. 전체 schema에서 nullable/필수와 UI 기본값을 위 표에 맞춰 확정하고, producer/consumer fixture는 모든 필수 필드를 포함한다. cause가 비어 있는 ETA 증가에 ‘사고’ 문구를 넣지 않는다.

### 2.3 변경할 계약 파일

| 경로 | 구체 작업 |
|---|---|
| `docs/contracts/schedule-reevaluation.md` | 현재 동작과 v2 목표를 분리. P/D 필수, 조건부 목표, tick·입력 버전·수락 설명 |
| `docs/contracts/travel-time-calculation.md` | 전체 여정 함수·엄격한 `< D` 비교·10분 목표·비용 분해와 fallback |
| `docs/contracts/transit-waiting.md`, `subway-timing.md`, `road-impact.md` | raw→시간/통제 판단, 추가 trip/incident 모델, missing/stale 규칙 |
| `docs/frontend-api-contract.md` | 요청·v2 응답·상태별 화면·수락/출발 차이·HTTP 오류 |
| **추가** `docs/contracts/schedule-evaluation-v2.schema.json` | C01 응답의 필수/nullable/enum/초 단위·추가 필드 호환 명세 |
| `docs/contracts/events`, `topics.yaml`, `redis-keys.yaml` | poll-status/사건/운행 변경 이벤트, version·key·TTL·보존·소유자 |
| `docs/contracts/ml-serving-http.md`, `recommendation-data-provenance.md` | ML 승인 범위·캐시·모델 변경 검출·원천 추적 |

기계 계약을 문서와 다르게 임의 구현하지 않는다. 현재 road.impact의 POTENTIAL_IMPACT나 status enum에 새 값을 몰래 추가하지 않는다. 이벤트 v2와 구버전 adapter를 먼저 배포한 뒤 Producer를 전환한다.

## 3. B01: 일정 입력·DB·반복 일정

### 3.1 수정 파일과 메서드

아래 Backend 경로의 기준은 `backend/src/main/java/com/ssafy/ddubi/`다.

| 파일 | 현재 → 작업 |
|---|---|
| `domain/schedule/dto/request/ScheduleCreateRequest.java` | nullable P → 신규 필수 검증. arriveBy와 교차 검증 |
| `domain/schedule/dto/request/ScheduleUpdateRequest.java` | P 유지. 적용된 최종 P,D 검증, 추천 결과를 입력으로 오인하지 않음 |
| `domain/schedule/service/ScheduleService.java` create/update | 일정 저장과 평가 next_check_at=now 등록을 같은 트랜잭션으로 수행. 외부 API는 커밋 후 실행 |
| `domain/schedule/entity/Schedule.java` | 직접 입력 이력·planSource·입력 revision·이동 상태 컬럼 연결 |
| `domain/schedule/dto/request/RepeatCreateRequest.java`, `RepeatUpdateRequest.java` | 출발 local time와 일정 날짜 대비 day offset 추가 |
| `domain/schedule/service/ScheduleRepeatService.java` materialize | 해당 일정 날짜와 P/D를 같이 생성. 재생성 시 revision 증가, 취소·수정된 개별 일정 보존 |
| `domain/schedule/dto/response/ScheduleResponse.java` | 사용자 P, 현재 추천, 실제 출발을 명시적으로 구분 |
| `domain/schedule/service/HomeService.java` | 홈 조회에서 최신 평가 요약을 재사용. 홈만 별도로 역산하지 않음 |

반복 일정 기본안: 추가 입력 `plannedDepartTime=08:30:00`, `plannedDepartDayOffset=0`, 기존 도착 목표 입력 `localTime=09:00:00`. 야간 일정은 출발 날짜가 전날이면 offset=−1이다. 초기 지원은 0/−1로 제한하고 그 밖은 명시적 입력 오류로 처리한다. 반복 일정에 P가 없던 기존 행은 입력 필요 상태로 남긴다. 현재 repeat 기능에 입력이 없다는 이유로 D에서 시간을 빼서 자동 채우지 않는다.

### 3.2 새 migration에 넣을 컬럼 제안

기존 V1~V6 파일은 수정하지 않고 구현 시점의 다음 번호를 사용한다. 데이터 담당은 이 표를 Backend에 전달하고 Flyway 적용은 Backend 소유다.

| 테이블.컬럼 | SQL 타입·null/default | 사용·이행 |
|---|---|---|
| schedule.planned_depart_at | 기존 DATETIME(6) NULL 유지 | 신규는 앱 검증 필수, 기존 null 일정 허용 |
| schedule.user_input_depart_at | DATETIME(6) NULL, 추가 | 기존 P 복사 가능하나 과거 수락 이력 구별 불가 표시. 새 직접 입력부터 정확 추적 |
| schedule.plan_source | VARCHAR(32) NOT NULL DEFAULT 'LEGACY_UNKNOWN' | 새 USER_INPUT / ACCEPTED_RECOMMENDATION. 이행 데이터 출처 위조 금지 |
| schedule.input_revision | BIGINT NOT NULL DEFAULT 0 | 입력 변경·수락·취소·출발에서 원자 증가 |
| schedule.journey_state | VARCHAR(16) NOT NULL DEFAULT 'NOT_STARTED' | NOT_STARTED/IN_PROGRESS/ARRIVED. 일정 UPCOMING/DONE/CANCELED와 구분 |
| schedule.actual_depart_at | DATETIME(6) NULL | 출발 확인시각 |
| schedule_repeat.planned_depart_time | TIME NULL | 반복 출발 시각 |
| schedule_repeat.planned_depart_day_offset | SMALLINT NULL | 일정 날짜 대비 출발 날짜. 두 컬럼 함께 존재 |
| schedule_evaluation.input_revision | BIGINT NULL | claim이 읽은 입력 버전. 기존 input_updated_at도 호환 기간 유지 |
| recommendation.policy_version | 기존 VARCHAR(10) | `cdep-v1` 사용 가능. 긴 모델 버전은 JSON에 보존 |
| recommendation.reasons | 기존 JSON | version 표시한 v2 근거 모델. 기존 배열 형식 조회 adapter 유지 |
| recommendation.duration_min/buffer_min | 기존 int | 초 원본은 result_json/reasons에 보존. buffer_min은 조건부 0/10 의미로 주석·writer 갱신 |
| schedule_evaluation.result_json/candidates_json | 기존 LONGTEXT | v2 결과·스냅샷 참조. parse schema/version 검사 |

입력 revision은 시간이 같은 연속 수정을 구별하기 위해 단조 증가시킨다. DB 시간을 임의 입력 버전으로 새로 계산하지 않는다. 기존 input_updated_at + lease 보호를 제거하지 않고 새 revision을 함께 검사하며 이행한다. 입력 변경마다 관련 알림 대기도 취소한다.

### 3.3 저장과 수락

`ScheduleEvaluationStore.claim/latest/due`의 P 필수 조건과 DEPARTURE_REQUIRED는 **유지**한다. 단, 새로운 반복 입력이 들어오면 해당 일정도 평가 대상이 되어야 한다.

`complete`는 input_revision·lease_token·일정 활성 상태·journey_state를 검증하고 결과, 추천, 다음 tick, outbox를 한 트랜잭션에 저장한다. 추천 자동 계산은 사용자 선택 경로를 덮지 않는다. 최초 선택이 아직 없을 때의 기본 후보 저장과 기존 선택 변경을 구별한다.

`accept(evaluationId)`는 사용자 소유권, 최신 입력 버전, 유효기간, 운영 사용 가능 품질, 출발이 아직 실행 가능한지 확인한다. 통과하면 planned_depart_at/선택 route/planSource를 바꾸고 revision을 올린다. user_input_depart_at은 보존한다. 중복 수락은 동일 상태로 멱등 처리하고, 오래된 수락은 409 계열의 명시 오류로 반환한다. 서버가 재확인하지 않은 임의 시각·routeId를 Client가 수락 요청에 넣게 하지 않는다.

PREVIEW 수락 차단은 유지한다. 모델 승인·시간 분해 검증 작업 없이 기존 차단 if문만 삭제하는 것은 이 계획의 구현이 아니다.

## 4. B02: 공통 계산기와 조건부 탐색

### 4.1 파일별 책임

기준 경로는 `backend/src/main/java/com/ssafy/ddubi/domain/recommendation/`다.

| 파일 | 작업 내용·입출력 |
|---|---|
| `service/ScheduleEvaluationWorkflow.java` | claim → 후보 → snapshot → P 평가 → 조건부 T 탐색 → 변경 분석 → complete. 순수 계산·SQL·외부 요청을 한 메서드에 섞지 않음 |
| `evaluation/MlRoutePreview.java` | 실제 여정 계산 부분을 공통 서비스로 추출. 검증용 기존/새 결과 비교 역할은 유지 |
| **추가** `evaluation/JourneyEvaluationService.java` | evaluate(candidate,departure,snapshot,session) → timeline/ETA/quality/causes. 정방향 계산의 유일한 진입점 |
| `evaluation/RouteTimelineEvaluator.java` | WALK/WAIT/RIDE/TRANSFER 순차 계산 재사용. 시간·방향 통제·탑승·환승·이후 진입시간 검증 |
| **추가** `evaluation/ConditionalDeparturePolicy.java` | `< D` 유지, `>= D`이면 T=D−600. 이용 불가/미확인 ETA를 숫자로 비교하지 않음 |
| `evaluation/DepartureSuggestionService.java` | P 평가와 T 탐색 분리. timetable 후보/격자/예산을 받아 같은 JourneyEvaluationService 호출 |
| `evaluation/JourneyEvidence.java` | trip stop prediction·incident revision·time validity·cost coverage·source metadata 확장 |
| `evaluation/RouteEvidenceAssembler.java` | 후보 edge·Redis·시간표·기준선·통제 artifact를 검증한 snapshot 조립 |
| **추가** `evaluation/RouteLegRoleClassifier.java` | step 순서와 앞뒤 대중교통 구간으로 ACCESS_WALK/TRANSFER_WALK/EGRESS_WALK/RIDE 분류 |
| **추가** `evaluation/WalkingEndpointResolver.java` | 카카오 path endpoint와 EXACT 마스터 node 좌표를 비교해 도보 호출 endpoint 판정 |
| **추가** `evaluation/WalkingEvidence.java` | 별도 도보 결과·fallback·endpoint 근거·비용 범위·API 버전 보존 |
| **추가** `evaluation/EvaluationSnapshot.java` | asOf·원천시각·원본 참조·hash·버전·validity·scope coverage를 불변 객체로 보존 |
| **추가** `evaluation/RecommendationChangeAnalyzer.java` | 이전/현재 결과 → 시각·경로·정시·품질·사건 변화, 알림 판단 입력 |

현재 Workflow는 DepartureSuggestionService를 제대로 통합한 조건부 탐색이 아니라 입력 출발 후보 비교 중심이다. 기존 solver 이름만 교체하지 말고 실제 호출 경로를 이 표대로 연결한다.

### 4.2 구현 의사코드

```text
job = claim(schedule, inputRevision, lease)
E = snapshot(asOf, candidateSet, rawDataVersions, master, model, policy)
baseDeparture = max(P, asOf)                 // 이미 놓친 출발은 과거 추천 금지
base = evaluateAllCandidates(baseDeparture, E)

if base has usable candidate with ETA < D:
    choose selected route first, then compatible alternatives
    decision = KEEP_DEPARTURE if P >= asOf else DEPART_NOW
    buffer = 0
else if enough evidence to compare, or verified route invalidation:
    T = D - 600 seconds                     // 각 평가에서 D로부터 한 번만 계산
    search = findLatestFeasible([asOf, min(P,T)], T, E)
    if search proves feasible: recommend checked departure and route
    else: find executable best-arrival alternative using the same E
else:
    return DATA_INSUFFICIENT with known estimates

result = attach quality, causes, search scope, validity, next tick
completeIfCurrent(job, result, pendingNotification)
```

과거 P의 대안은 KEEP_DEPARTURE라는 이름으로 표현하지 않고 `departureMissed=true`와 DEPART_NOW를 함께 반환한다. 조건부 T 탐색에 들어간 경우 정시 전 도착은 가능하지만 10분 여유가 없으면 MARGIN_UNAVAILABLE로 구별한다. 수치 ETA가 있는 PREVIEW도 표시용 분기를 계산할 수 있지만 productionEligible=false를 유지한다. 탐색이 미완료이면 실행 가능한 후보를 찾았어도 전체 decision은 SEARCH_INCOMPLETE이며, 후보의 성격은 별도 `bestKnownDecision`으로 표시한다.

### 4.3 대중교통 분해·마스터 매핑·도보 API 보강

조건부 출발 계산 전에 후보를 다음 순서로 준비한다.

1. `PublicTransitRouteClient.search(origin,destination)`으로 카카오 대중교통 JSON을 받는다.
2. `KakaoRouteMapper`가 step을 BUS/SUBWAY/WALK로 파싱한다. 기존 WALK를 버리지 않는다.
3. BUS/SUBWAY만 `MasterDataTransitMappingResolver`로 매핑한다. 버스는 수도권 버스 마스터·노선·정류장 순번·geometry, 지하철은 노선·역 순서·별칭·실시간 crosswalk를 검증한다.
4. EXACT 인접 edge와 카카오 transit step의 path endpoint를 이용해 첫 승차·마지막 하차 좌표를 판정한다.
5. `RouteLegRoleClassifier`가 ACCESS/TRANSFER/EGRESS WALK를 분리한다.
6. 좌표와 제휴 권한이 준비된 후보는 접근·이탈 도보 API를 각각 호출한다.
7. `RouteEvidenceAssembler`가 원본 WALK를 동일 역할별로 교체하거나 fallback하고 비용 범위 검증 결과를 만든다.
8. `JourneyEvaluationService`가 ACCESS_WALK부터 EGRESS_WALK까지 순차 평가한다.

#### 변경·추가 파일

아래 경로의 기준은 `backend/src/main/java/com/ssafy/ddubi/domain/route/`다.

| 파일 | 구체 작업 |
|---|---|
| `client/dto/KakaoPublicTransitResponse.java` | stop에 없는 좌표를 추가하지 않음. path.points의 원문 좌표와 unknown 필드 허용 유지 |
| `service/KakaoRouteMapper.java` | mode 파싱 뒤 leg role 분류에 필요한 sequence/geometry/stop names 보존. 매핑 결과와 endpoint 후보 연결 |
| `model/RouteSegment.java` | `legRole`, boarding/alighting endpoint evidence 또는 별도 evidence 참조 추가. DB/API 하위 호환 adapter 마련 |
| `service/MasterDataTransitMappingResolver.java` | 기존 EXACT/PARTIAL/AMBIGUOUS/UNMAPPED 유지. 선택한 공식 node 좌표를 endpoint resolver에 제공 |
| `mapping/MetropolitanBusMaster.java`, 지하철 master provider | 정류장/역 좌표의 출처·버전·nullable을 노출. 중심점/출입구 의미 구분 |
| **추가** `client/WalkingRouteClient.java` | 접근·이탈 도보 조회 port |
| **추가** `client/KakaoWalkingRouteClient.java` | 제휴 host·service header·인증·timeout·429/4xx/5xx 처리 |
| **추가** `client/KakaoWalkingProperties.java` | baseUrl, path, service, enabled, timeout. 키는 기존 secret 경계 재사용 |
| **추가** `client/dto/KakaoWalkingResponse.java` | 실제 공식 fixture로 확인한 응답 필드만 선언. 자동차 DTO 재사용 금지 |
| `service/RouteSearchService.java`, `recommendation/service/ScheduleEvaluationWorkflow.java` | 후보 획득 뒤 도보 보강 session 호출, 후보별 호출 한도·캐시·실패 격리 |
| `evaluation/RouteEvidenceAssembler.java` | 역할별 교체, provider total/step 비용 범위, fallback reason 조립 |

제휴 도보 API는 후보당 최대 두 번이라 후보 15개면 최대 30회다. 모든 후보를 무조건 호출하지 않는다. 먼저 BUS/SUBWAY 마스터 매핑·기본 품질로 후보를 제한하고, 선택 경로와 상위 평가 후보를 우선하여 평가 세션별 호출 예산을 적용한다. 호출하지 않은 후보는 원본 WALK fallback을 유지한다. 초기 상한값은 실제 제휴 쿼터·latency를 확인한 뒤 설정으로 정한다.

도보 API 장애가 전체 대중교통 후보 검색을 503으로 만들지 않는다. 권한 없음/403은 운영 설정 오류로 지표·health에 표시하고 해당 평가에서는 원본 WALK fallback을 사용한다. 429는 retry-after/제휴 정책을 따르고 같은 평가에서 폭주 재시도하지 않는다. 좌표 불일치는 API 장애가 아니라 `WALK_ENDPOINT_UNVERIFIED` 품질 사유다.

#### 저장·응답 필드 추가안

| 필드 | 타입 | 설명 |
|---|---|---|
| `segments[].legRole` | enum | ACCESS_WALK/TRANSFER_WALK/EGRESS_WALK/RIDE |
| `segments[].endpointEvidence` | object nullable | provider/master 좌표·거리·매핑/정책 버전·판정 |
| `segments[].durationSource` | enum | KAKAO_WALKING_API/PUBLIC_TRANSIT_STEP/PROVIDER_TOTAL_FALLBACK 등 |
| `walkingApiApplied` | boolean | 접근·이탈 중 하나 이상 계산에 실제 반영됐는지 |
| `walkingApiCoverage` | NONE/ACCESS_ONLY/EGRESS_ONLY/BOTH | 호출 성공이 아니라 계산 반영 범위 |
| `costCoverage` | COMPLETE/PARTIAL/UNKNOWN | 중복 없는 구성요소 합산 가능 여부 |
| `fallbackReasons` | string[] | 권한·좌표·응답·예산·비용범위 미검증 이유 |

DB의 기존 route_segment mode는 WALK를 유지하고 역할은 새 컬럼 또는 versioned metadata JSON으로 추가한다. 조회·인덱스 필요성과 migration 담당 협의 후 결정한다. ACCESS/EGRESS를 BUS/SUBWAY segmentId로 만들지 않는다.

### 4.4 엄격한 경계와 탐색 완전성

현재 RouteTimelineEvaluator의 ON_TIME_ESTIMATE는 `<= arriveBy`를 사용할 수 있다. 전역 enum 의미를 조용히 바꾸지 말고 ConditionalDeparturePolicy에서 **직접 ETA.isBefore(D)**로 판정한다. T 성공 검사는 `!ETA.isAfter(T)`다. 기존 evaluator를 사용하는 다른 검색/검증 경로의 회귀를 막는다.

탐색 입력은 경로, 후보 출발시각, 고정 스냅샷이다. 이전 P에서 계산한 WAIT/RIDE를 고정값으로 넣고 출발만 바꾸면 잘못된 환승을 만든다. 새 시각마다 차량 선택과 hourSlot을 다시 산출한다.

초기 부하 시험 설정안은 `maxEvaluations=256`, `fallbackGridSeconds=60`, `maxComputeMillis=5000`이며 **운영 성능을 보증하는 제품 확정값이 아니다**. policy 설정에 두고 Replay 결과로 조정한다. 격자 범위는 [asOf,min(P,T)]와 공급자 지원 시간 범위의 교집합이다. 예산을 넘으면 SEARCH_INCOMPLETE다. 60초 격자로 찾은 값은 격자 내 최신이며 초 단위 전역 최신이라고 표현하지 않는다. 시간표 후보는 실제 운행 경계시각을 포함한다.

시간표 후보 생성은 다음 순서로 구현한다. 같은 경로에 여러 열차 조합이 있으면 마지막 열차부터 늦은 순으로 열거하며 예산을 공유한다.

```text
lastAllowedArrival = T - finalWalkSeconds
for lastTrip whose alightAt <= lastAllowedArrival:
    latestPlatformArrival = lastTrip.boardingCutoffAt
    for each preceding transfer/ride in reverse order:
        previousAlightDeadline = latestPlatformArrival - transferWalkSeconds
        choose verified previous trips with alightAt <= previousAlightDeadline
        latestPlatformArrival = previousTrip.boardingCutoffAt
    departureCandidate = latestPlatformArrival - initialWalkSeconds
    if asOf <= departureCandidate <= min(P,T):
        verify full forward journey and record the result
```

boardingCutoffAt은 검증된 승차 가능 마감 시각이다. 원천이 도착 ETA만 제공하면 출발/승차마감을 정확히 확보한 것으로 취급하지 않고 보수적 추정·품질 제한을 붙인다. 중간 버스의 미래 운행시각이 없으면 해당 조합의 정확한 역산을 멈추고 경로 전체를 제한 격자로 평가한다. 격자는 상한에서 과거 방향으로 열거하되 항상 asOf와 운행/통제/시간대 경계 후보를 포함한다. 단조성이 없어도 검증한 후보를 생략하지 않아야 그 후보 집합 내 최신을 주장할 수 있다. API의 현재 총시간은 미래 시각별 정확한 함수가 아니므로 fallback 결과의 탐색 domain과 한계를 표시한다.

### 4.5 스냅샷과 모델 캐시

- Redis MGET과 DB active baseline version 조회를 후보 묶음 단위로 한다. MGET도 서로 다른 원천 관측시각을 동일 시각으로 만들지 않으며 각 sourceGeneratedAt을 보존한다.
- snapshot 이후 검색 도중 들어온 사건은 다음 평가 revision으로 처리한다. 같은 계산 중 최신값을 무작위로 섞지 않는다.
- ML 캐시 키: modelVersion + providerNamespace + routeId + stop pair + occurrence + serviceDate + hourSlot + featureSnapshotVersion(사용 시).
- 기존 MlRoutePreview의 compare마다 새 session/제한을 만드는 구조를 탐색 전체 session으로 옮긴다. 같은 입력은 한 번만 요청하고 인접 edge를 batch로 묶는다.
- 모델 서버가 중간에 다른 version을 응답하면 같은 결과에 혼합하지 않고 재시도 또는 미완료로 종료한다.
- 외부 전체 시간 제한과 내부 계산 제한은 분리한다. lease 120초를 넘을 수 있는 작업은 갱신하거나 제한 안에서 중단한다. 늦은 결과는 lease fencing으로 거부한다.

## 5. 교통수단별 구현 작업

| 대상 파일 | 구현 절차 | 인수 기준 |
|---|---|---|
| `evaluation/BusWaitingService.java` | S03의 mkTm+exps를 절대 시각으로 만들고 플랫폼 도착 이후 차량 선택. 원천 코드·만차·우회 필터 유지. 미래 배차는 검증된 H만 사용 | 첫 차량 미탑승→둘째, stale, 미래 시각, 없는 셋째 차량, 순환 occurrence fixture |
| `evaluation/BusPreviewClient.java` | S13 batch 요청·모델 범위·cache·quality를 session에 연결 | 동일 입력 중복 호출 없음, model version 혼합 거부 |
| `evaluation/SubwayTimingService.java` | S05~S08의 trip crosswalk 검증 후 stop별 시각 적용. 취소/무정차 제외. 하차 예측 없으면 ESTIMATED 명시 | 시간표 trainCode를 실시간 ID로 착각하지 않음. 지연으로 다음 환승을 놓치면 재선택 |
| `evaluation/SubwayTimetable.java` | serviceDate·24시 초과 시각·역 순서 검증 유지. 도착 기준 후보 조회 인덱스 추가 | 전날 운행의 25시, 막차, 다른 dayType, 지선/급행 fixture |
| `evaluation/RoadImpactEvidence.java` | namespace/방향/occurrence/기간 매핑 후 FULL 여부 검사 | 반대 방향·시간 밖·부분 통제는 완전 차단하지 않음 |
| `route/client/KakaoWalkingRouteClient.java` (추가) | 검증된 origin/destination으로 접근·이탈 도보 조회, 제휴 권한·쿼터·오류 분류 | endpoint 순서, 권한 없음 fallback, 빈/깨진 응답, timeout, 429 fixture |
| `service/BaselineSnapshot.java` | 날씨·버전·p80/p95·표본 품질까지 보존 | DRY와 RAIN 혼합 금지, version 고정, 없는 표본 unknown |

접근·이탈 도보는 검증된 별도 도보 API 결과를 우선하며 실패하면 같은 역할의 제공자 WALK를 유지한다. 환승은 제공자 WALK를 유지한다. 로컬 미커밋 `TransferTimingService.java`와 관련 마스터/테스트는 이 문서의 구현 완료 항목이 아니다. 자동으로 채택·수정·삭제하지 않는다.

`MasterDataTransitMappingResolver`, `CanonicalSegmentIdFactory`, `RouteCandidateAssembler`, `RouteSegmentEdgeWriter`는 보수적 ID 매핑·저장 역할을 유지한다. 외부 후보 생성은 `PublicTransitRouteClient` 책임이며 Spark가 최단경로를 만들지 않는다.

## 6. D01·D02·D03: 수집·마스터·Spark

세부 raw 필드와 공식 URL은 [데이터 가이드](arrival-deadline-data-guide.md)의 S01~S13을 작업 입력으로 사용한다. 이 절은 해당 데이터를 어느 파일에서 어떤 결과로 만들지 정의한다.

### 6.1 Collector

`dt/dev`에 반영된 기존 기반을 먼저 유지한다. `notice_dedup.py`의 TOPIS ACK 후 해시 저장, `request_budget.py`의 bus_location 5분 후 최대 1회 재시도, `sources/subway_schedule.py`의 03:30~04:30 기본 생략과 특별 날짜 override, `sources/subway_response.py`의 정상 빈 응답 검증이 추가되어 있다. 이 기능을 신규 과제로 중복 구현하지 않는다. 새 poll-status에는 예정된 생략도 구분하고, 야간 운행 지원은 이 수집 공백·특별 운행 설정과 함께 검사한다.

| 수정/추가 파일 | 작업 알고리즘 | 결과·실패 처리 |
|---|---|---|
| `collector/src/main.py`, `sources/kafka_adapter.py` | 기본값과 설정 max로 주기 단축이 막힌 부분을 source policy로 변경. 노선/역 scope별 due와 실제 키 예산을 함께 계산 | 목표 주기를 못 맞추면 RATE_LIMITED, freshness 보장 위조 금지 |
| `collector/src/request_budget.py`, `config.py` | 키 그룹별 승인 쿼터/초당 제한·rolling window 유지, 재시도 포함 집계 | 429·예산 소진은 nextAllowedAt까지 backoff. 4xx 인증/형식 오류 무한 재시도 금지 |
| `collector/src/sources/bus_arrival.py`, `bus_location.py` | S03/S04 필드·코드·원천시각·대상 route 검증, 정상/빈/오류 fixture | raw와 source metadata를 Kafka에만 발행 |
| `collector/src/sources/subway_arrival.py`, `subway_location.py` | S06/S07 ID·recptnDt·건수/범위 검증 | 서울 외 미제공 구간과 요청 실패 구분 |
| `collector/src/sources/subway_alert.py` | S08 구간 코드 원본 보존, 본문 변경 revision | 범위 미확정 공지를 노선 전체 차단으로 승격 금지 |
| `collector/src/sources/traffic_incident.py`, `topis_notice.py` | S09/S12 원문·시간·ID·페이지 완전성 보존 | 예상 종료를 실제 해제로 사용하지 않음 |
| **추가** `collector/src/source_poll_status.py` | 성공/부분/실패·완전 스냅샷 판정 모델 | source.poll-status 스키마와 serializer 계약 테스트 |
| **추가** `collector/src/demand_registry.py` | Backend demand artifact를 원자 교체로 읽고 source/scope별 중복 제거·만료 | 사용자 식별값 제외. artifact 실패 시 마지막 유효 버전/고정 대상 fallback |

demand 전달 1차 구현안은 Backend가 버전 지정 JSON artifact를 생성하고 Collector가 제한된 간격으로 읽는 방식이다. 필드는 데이터 가이드 10절을 따른다. 운영 배포 경로·읽기 인증은 인프라 담당자가 기존 artifact 배포 방식으로 연결한다. 사용자마다 별도 Collector 프로세스나 Redis 쓰기를 만들지 않는다.

### 6.2 마스터·원천 확보

| 파일/도구 | 구체 작업 | 인수 산출물 |
|---|---|---|
| `backend/tools/transit-timing/build_timetable.py` | S05 CSV→기존 runtime JSON. dayType/serviceDate·validity·정차 패턴 검증 | 같은 입력 hash면 같은 artifact, 날짜·24시·결측 fixture |
| **추가** `backend/tools/transit-timing/build_train_crosswalk.py` | 검증된 공식 운행 대응 입력→staticTripKey/providerTrainId 매핑 | EXACT/AMBIGUOUS/UNMAPPED·coverage·근거 파일. 이름/번호 유사성만으로 EXACT 금지 |
| `backend/tools/road-impact/audit.py`, `promote.py` | S10 노드→구간→방향 링크→AccInfo namespace 검증 | SHA·validity·coverage·occurrence·검토 근거. 미확보면 operationalMappings 승격 안 함 |
| `backend/transit-master/road`, `backend/transit-master/subway` | 승인된 artifact와 manifest 배포 | Backend/Spark가 같은 SHA/version 읽기. 운영자·지역·기간 범위 표시 |

시간표 분석용 별도 index와 runtime loader의 형식이 다르면 adapter를 만들고 `SubwayTimetable` 로드 테스트로 검증한다. ‘CSV 다운로드 성공’을 운행 대응 완료로 처리하지 않는다.

### 6.3 Spark 최신값·사건·구간 상태

현재 목표 브랜치에는 `sparkjobs/common/bus_normalizer.py`, `common/bus_stream_sink.py`, `batch/bus_silver.py`가 있다. 버스는 realtime_latest에서 같은 Silver 결과를 HDFS와 Redis에 저장하며 기존 observation 호환 필드도 유지한다. 버스 정규화를 별도 구현하지 않고 이 순수 core와 필드명을 재사용한다. 원천 관측시각이 없으면 observedAt/validUntil=null인 계약과 ACCEPTED/repeatIndex 필터를 존중한다. Silver는 관측 전처리이며 ACTUAL_TRAVERSAL 생성 완료가 아니다.

| 수정/추가 파일 | 처리 순서·입력 → 출력 |
|---|---|
| `sparkjobs/streaming/realtime_latest.py` | raw parser→타입/원천시간 검증→provider key별 최신값→Redis. 보관 TTL과 freshness 분리 유지, S03~S08 컬럼 보완 |
| `sparkjobs/streaming/road_incidents.py`, `sparkjobs/common/road_impact.py` | S09 + S10→incident identity/revision→link hash→영향 변경 이벤트. 이미 있는 링크 이동 CAS·충돌 방어 유지 |
| **추가** `sparkjobs/common/incident_lifecycle.py` | active/ended/stale/unknown 전이 순수 함수. 원천 종료 근거·poll completeness·revision 기반 |
| **추가** `sparkjobs/streaming/transit_incidents.py` | 공지+정차/운행 매핑→적용 trip/stop/edge와 근거. 미매핑은 rejected/unknown |
| `sparkjobs/common/subway_crosswalk.py` | 정적 노선/역 매핑 재사용. 운행별 열차 대응을 정적 역 대응과 구별 |
| **추가** `sparkjobs/common/trip_stop_predictions.py` | 같은 serviceDate/trip/stop 정적·실시간 결합→TripStopPrediction. 없는 하차 예측 null |
| `sparkjobs/streaming/segment_status.py` | 실측 median/P50 연산 재사용, 검증된 운행 중단·도로 통제 원인 연결 |
| `sparkjobs/streaming/segment_status_live.py` | 순수 계산에 있는 blocked/사건 입력을 상시 실행 인자·manifest와 연결. snapshot clock 주입 |
| `sparkjobs/streaming/segment_observations.py`, `common/segment_facts.py` | S13 실제 통과 schema 검사→중복 제거·품질/미매핑 제외·Parquet facts |
| `sparkjobs/batch/segment_baseline.py` | 실제 사실로 분위수·표본·기간·날씨·버전 계산→내부 API upload/activate |

추가 파일은 역할 분리 제안이다. 기존 parser/transform을 재사용하며 Spark 3.x 실행 환경에 맞는 Python 3.8 문법을 유지한다. driver 전체 collect/toPandas는 사용하지 않는다. schema를 명시하고 join key의 namespace/masterVersion, event-time watermark, 상태 보존 범위를 문서화한다.

incident lifecycle 상태는 incidentKey별 최신 원천시각/revision·lastObservedAt·lastCompletePollAt·confirmedEndAt을 보존한다. watermark 밖의 데이터는 최신 상태를 되돌리지 않되 원본/rejected에 보존한다. 원천별 허용 지연과 보존은 실제 지연 분포를 측정해 설정하고 Replay에도 동일 값을 주입한다. checkpoint를 source/job별로 분리한다.

### 6.4 Kafka·Redis 출력 계약

- raw 토픽은 기존 transport.raw/city.raw를 우선 유지한다. 새 이벤트는 `schemaVersion,eventId,source,occurredAt,collectedAt`와 source별 필드를 검증한다.
- `segment.status` key는 segmentId, `road.impact`는 기존 key 계약을 유지한다. 새 운행 영향 알림은 제안 토픽 `transit.impact.v1`, key는 serviceDate+tripKey 또는 확정된 scope key다. 토픽 추가 전 파티션·보존·재처리 계약을 확정한다.
- 이벤트는 계산할 데이터를 매번 모두 복제하는 용도가 아니라 영향 scope·revision을 알려 Backend를 깨우는 용도다. 권위 있는 최신 상태는 Redis/버전 artifact에서 조회한다.
- 제안 운행 예측 Redis key는 `transit:trip-stop:v1:{serviceDate}:{tripKey}:{stopSequence}`이며 writer는 Spark, reader는 Backend다. TTL은 validUntil 잔여 시간 이내. 원천별 horizon 밖에서는 시간표 fallback만 가능하다.
- 기존 `segment:status`는 TTL 300초 이하와 validUntil을 함께 유지한다. 과거 이벤트 재생이 현재 값을 덮지 못하게 version/time CAS를 적용한다.
- Redis 성공 전 변경 Kafka 이벤트를 발행하지 않는다. 부분 실패는 멱등 재시도하고 Backend는 이벤트 revision과 조회한 상태 revision을 비교한다. 먼저 도착한 trigger에 데이터가 없으면 bounded retry/unknown이다.
- 두 서비스가 서로 다른 namespace, 단위, artifact를 쓰는 경우 배포 성공이 아니라 계약 오류로 탐지한다.

## 7. B03: 주기·사건·작업 경쟁

### 7.1 파일별 수정

| 경로 (recommendation 기준) | 작업 |
|---|---|
| `evaluation/ScheduleMonitoringPolicy.java` | 현재 출발 중심 nextEvaluation 인자를 P/D와 상태로 확장. 기본 tick은 D−60/50/40/30/25/20/15/10/9…0분의 미래 최솟값 |
| `service/ScheduleEvaluationScheduler.java` | 등록 후 wake와 DB due 복구를 결합. 현재 15초 조회·20건 순차 처리 병목을 bounded worker로 개선 |
| `service/ScheduleEvaluationWakeup.java` | 사건으로 next_check_at을 당기되 lease를 무단 해제하지 않음. input/event revision을 올리고 연속 wake 합침 |
| `adapter/SegmentStatusListener.java` | segment.status/road.impact 재처리 dedup, transit impact 구독 추가, eventId/revision 저장 후 대상 일정 wake |
| `service/ScheduleEvaluationStore.java` | due/claim/complete/retry의 새 revision·journeyState·validUntil 처리, notification outbox 원자 저장 |

작업 부하 시험 기본안은 worker=4, claim batch=20, lease=120초, 필요 시 30초마다 lease 갱신이다. 한 일정은 한 lease만 가진다. 실제 CPU·외부 한도를 측정해 설정으로 조정한다. 이 값은 SLA 보증이나 확정 운영 배포 설정이 아니다.

### 7.2 영향 일정 역조회와 중복

현재 경로 `route_segment_edge(segment_id) → route_segment → route(is_selected=true) → schedule(UPCOMING)`를 유지한다. 별도 schedule_segment 테이블을 만들지 않는다. provider step이 여러 edge로 전개되므로 route_segment.segment_id만 검사하지 않는다.

추가 후보 감시 기본안: 아직 수락하지 않은 최신 proposedRouteId의 edge도 함께 조회해 위험/회복을 알 수 있게 한다. 선택+최신 제안의 union이며 모든 과거 후보를 무제한 감시하지 않는다. 최초 경로 저장 전 구간 미보유 일정은 등록 즉시 평가·정기 복구로 처리하고 이벤트만 의존하지 않는다.

dedup은 동일 eventId의 재전달과 동일 incidentKey/revision의 재관측을 구별한다. 제안 `processed_impact_event` 테이블은 `consumer_name VARCHAR(64), event_id VARCHAR(128), processed_at DATETIME(6)`를 복합 PK로 가지며 처리+일정 wake를 같은 트랜잭션으로 묶는다. 보존은 Kafka 최대 재생/재시도 범위 이상으로 설정하고 삭제 시 오래된 Replay는 별도 consumer namespace로 실행한다.

### 7.3 상태·재시도 규칙

- 수정/취소/수락/출발: 입력 revision 증가, 대기 알림 취소, 이전 평가 저장 거부.
- 사건만 바뀜: event_revision 증가. 계산 중이면 완료 후 최신 revision 재평가 예약. 이전 revision 결과에는 운영 권고 수락을 열지 않는다.
- 외부 429/5xx/timeout: 기존 유효 결과는 stale 여부와 함께 조회 가능, 작업은 지수 backoff+상한으로 재시도. 입력 오류/인증 4xx는 같은 요청 무한 반복 금지.
- 후보 cache 현재 기본 900초는 ‘원천 후보 보관’이다. 완전 통제·출발지 변경·운행 종료로 무효면 TTL 이전에도 새 후보 요청.
- 등록 직후는 커밋 후 wake + next_check_at=now로 실행한다. 요청 실패로 wake가 유실돼도 DB due가 복구한다.
- D를 지난 경우와 IN_PROGRESS는 출발 전 tick을 멈춘다. 이동 중 작업으로 넘기거나 상태 확인 표시를 유지한다.

## 8. N01: 근거 기반 알림·전송 보장

현재 Workflow는 notificationJson/key를 null로 넘긴다. `NotificationService.create()`는 트랜잭션 안에서 FCM을 동기 호출하고, 실패 결과가 반환돼도 Dispatcher가 처리 키를 남길 수 있다. 이 흐름을 아래처럼 바꾼다.

### 8.1 수정 파일

| 파일 | 작업 |
|---|---|
| `domain/recommendation/evaluation/RecommendationChangeAnalyzer.java` (추가) | 이전/현재 결과·관련 사건→유의미한 변화와 cause 근거 |
| `domain/recommendation/service/EvaluationNotificationDispatcher.java` | outbox claim→최신 입력/상태 검증→발송→결과별 ack/retry |
| `domain/notification/service/NotificationService.java` | 인앱 기록 생성과 외부 전송 분리. 실패를 전송 성공으로 반환하지 않음 |
| `domain/notification/service/NotificationCommand.java` | evaluationId,decision,incidentKey/revision,affectedSegments,templateCode 전달 |
| `domain/notification/service/SuppressionStore.java` | 의미가 같은 알림 dedup. 악화/해제 revision은 새로운 전이로 허용 |
| `domain/notification/entity/NotificationType.java` | EARLY_DEPART/ROUTE_CHANGE/ON_TIME_UNLIKELY/DATA_STALE 등 기존 유형 재사용. 공식 통제/해제 유형 추가 시 DB·Client 함께 변경 |
| `global/push/FcmPushSender.java` | retryable 오류·잘못된 토큰·성공 구분. timeout의 전달 여부 불명확 처리 |

### 8.2 원인 판정·문구

| 근거 | 표시 가능한 설명 | 금지 |
|---|---|---|
| 공식 FULL 통제 + EXACT 영향 edge + 시간 겹침 | ‘이용 구간 통제로 해당 경로를 사용할 수 없습니다’ | 주변 사고를 원인으로 확정 |
| 공식 지하철 중단 + 해당 방향/역/운행 적용 | ‘해당 구간 운행 중단으로 대안 경로를 안내합니다’ | 노선명만으로 전 구간 차단 |
| 같은 열차의 검증된 시간표 대비 지연 | ‘열차 도착예측이 시간표보다 늦습니다’ | 장애 원인을 만들어 냄 |
| ETA만 증가 | ‘예상 소요시간이 늘어났습니다. 원인은 확인 중입니다’ | 근거 없이 교통량 증가/사고라고 함 |
| T 달성 가능한 조기 출발 | ‘08:20 출발 시 08:50 도착 예상’ | ‘10분 일찍 나가세요’만 표시 |
| T 실패, D 전 가능 | ‘10분 여유 확보는 어렵지만 08:25 출발 시 08:55 도착 예상’ | 정시 도착 불가라고 함 |
| 만료된 사건 | ‘최신 통제 여부를 확인할 수 없습니다’ | 상황 종료 알림 |

cause에는 `incidentKey,revision,source,officialNoticeAt,affectedSegmentIds,evidenceLevel,reasonCode,observedAt`를 저장한다. 사건별 지연분은 검증된 반사실 비교가 없으면 null이다. 같은 사건이 여러 edge에 걸려도 전체 지연을 edge마다 가산하지 않는다.

출발 변경 알림의 시험 기본안은 180초 이상 변경 또는 경로/정시 가능성 전환 시 발송, 같은 유형은 5분 억제다. **공식 완전 통제·운행 중단·정시 가능성 악화는 작은 분 변화와 무관하게 검토**한다. 이 임계값은 사용자 확정값이 아니라 정책 설정·Replay 검증 대상이다. 동일 사건 재수집은 새 알림이 아니다.

### 8.3 outbox DB 제안

추가 `evaluation_notification_outbox`:

| 컬럼 | SQL 타입·제약 |
|---|---|
| id | BIGINT PK |
| schedule_id | BIGINT NOT NULL, schedule FK |
| evaluation_id | VARCHAR(128) NOT NULL |
| input_revision | BIGINT NOT NULL |
| dedup_key | CHAR(64) NOT NULL UNIQUE |
| payload_json | JSON NOT NULL, 사용자 표시용 정제 내용 |
| status | VARCHAR(16) NOT NULL: PENDING/SENDING/SENT/RETRY/FAILED/CANCELED/SUPPRESSED |
| attempts | INT NOT NULL DEFAULT 0 |
| next_attempt_at | DATETIME(6) NOT NULL |
| lease_token/lease_until | VARCHAR(36) NULL / DATETIME(6) NULL |
| last_error_code/provider_message_id | VARCHAR(128) NULL / VARCHAR(255) NULL |
| created_at/sent_at | DATETIME(6) NOT NULL / DATETIME(6) NULL |

인덱스는 `(status,next_attempt_at,lease_until)`, `(schedule_id,input_revision)`를 추가한다. dedup_key는 일정+입력 revision+알림유형+사건 identity/revision+의미 있는 결과 변화 fingerprint의 hash다. evaluationId만 쓰면 매분 새로운 알림이 된다.

전송은 DB 트랜잭션 밖에서 한다. 실패는 retryable 여부에 따라 재예약하며 시험 기본은 최대 5회, 30초부터 지수 backoff/최대 5분이다. 출발/일정/근거 유효기간을 넘은 알림은 취소한다. 성공 후에만 SENT 처리한다. FCM 성공 응답 전 프로세스가 죽으면 중복 전달 가능하므로 Client도 notificationId로 중복 표시를 억제한다. exactly-once 전송을 주장하지 않는다.

## 9. F01: Client 화면·API 작업

| 파일 | 유지/수정 내용 | 완료 화면 |
|---|---|---|
| `client/lib/presentation/screens.dart` ScheduleFormScreen | **출발 날짜·시간 입력 유지**. P<D, 필수·서버 오류 표시. 등록 후 계산 상태 조회 | P와 D가 모두 보이며 자동 추천으로 입력칸 덮지 않음 |
| `client/lib/domain/models.dart` | 직접 입력·현재 계획·추천·실제 출발 필드 구별 | 편집 저장 시 원래 P가 추천값으로 몰래 바뀌지 않음 |
| `client/lib/domain/schedule_evaluation.dart` | v2 응답 typed DTO/enum·nullable parser 추가 | 알 수 없는 새 enum은 오류 대신 안전한 정보 표시 |
| `client/lib/services/schedule_api.dart`, `api_schedule_repository.dart` | 등록·수정·최신 결과·수락·출발 응답 연결 | HTTP 409/422/503와 stale 상태 구분 |
| `client/lib/presentation/schedule_evaluation_card.dart` | 최신 결과·target·buffer·quality·cause 표시. DEPARTURE_REQUIRED 유지 | KEEP/ADVANCE/MARGIN_UNAVAILABLE/지각/정보 부족/미완료 각각 문구 |
| `client/lib/services/recommendation_api.dart` | desiredArrivalAt−durationMinutes 로컬 역산과 후보 index별 추천 라벨 제거 | 서버 P-aware 계산값만 표시 |
| `client/lib/presentation/notification_center.dart` | scheduleId/evaluationId 딥링크·최신성·이미 출발한 일정 안내 | 옛 알림에서 만료 추천 수락 불가 |

일정 저장 전 일반 경로 검색도 정책 결과를 보여 주려면 P,D를 갖는 조회 전용 계약을 추가한다. 초기 작업은 **추가 제안 endpoint `POST /api/v1/routes/evaluate`**로 좌표/P/D를 받아 공통 계산기를 호출하고 일정/추천 DB는 저장하지 않는 방식이다. 기존 `/routes/search`는 후보 조회 의미를 유지한다. 별도 화면에서 구현하지 않을 경우 단순 후보 정보로 표시하며 조건부 추천이라고 부르지 않는다.

기존 일정 endpoint는 재사용한다:

- `POST /api/v1/schedules/{scheduleId}/recommendations`: 재평가 요청. 진행 중이면 상태/동일 작업을 반환하고 중복 외부 호출 금지.
- `GET .../recommendations/latest`: 최신 v2 결과·실행 상태 조회. Client polling은 서버 재계산 주기와 별개다.
- `POST .../recommendations/accept`: evaluationId만 전달. 성공 후 P 변경은 서버 응답을 재조회.
- `POST /api/v1/schedules/{scheduleId}/departure`: 실제 출발 확인으로 확장, 아래 J01 적용.

조회는 현재 30초 polling을 유지할 수 있으나 화면 비활성/일정 종료 때 중단하고 실패 backoff를 둔다. 서버 정기 계산은 앱 실행 여부와 무관하다. nullable ETA·미확인 원인에 0분/정상 기본값을 넣지 않는다.

## 10. J01: 실제 출발·이동 중·도착

현재 `/departure`는 감시 중단만 한다. `RecommendationController`, `ScheduleEvaluationStore.stop`, `domain/report/service/ArrivalService`를 다음 상태 전이에 맞춰 수정한다.

| 요청/상태 | 처리 |
|---|---|
| 출발 확인, NOT_STARTED | 서버 확인시각 actualDepartAt 저장, journeyState=IN_PROGRESS, input revision 증가, 출발 전 대기 알림 취소 |
| 같은 출발 확인 재시도 | 최초 actualDepartAt 유지, 멱등 응답 |
| IN_PROGRESS + 최신 위치/탑승 상태 | 현 지점·시각에서 목적지까지 남은 여정 평가. 과거 출발 조정 금지 |
| 위치 없음/오래됨/권한 거절 | 위치/탑승 상태 확인 필요 표시. 정밀 우회 안내를 만들어 내지 않음 |
| 도착 확인 | 기존 ArrivalService의 보고 저장과 journeyState=ARRIVED·감시 종료를 원자적으로 연결 |
| 일정 취소 | 진행 중 작업과 알림 취소. 이동 상태 기록은 이력으로 보존 |

**추가** Backend `domain/schedule/service/JourneyStateService.java`, request DTO `JourneyPositionRequest.java`; Client `client/lib/services/journey_location_service.dart`를 제안한다. 위치 전달 endpoint는 `POST /api/v1/schedules/{id}/journey/position`이며 `longitude,latitude,accuracyMeters,observedAt,sequence,mode,providerVehicleId(optional)`를 받는다. 로그인 사용자의 일정만 허용하고 관측 순서역전·미래시각·지원범위 밖·부정확 좌표를 거부/저품질로 분리한다.

위치 신선도·정확도 기준은 모바일 위치 오차 실측 후 정책값으로 고정한다. 차량 ID를 위치 근접으로 추측하지 않는다. 위치 동의·중단·보관 기간을 명시한 뒤 필요한 최소 상태만 저장한다. 이동 중 버스/열차에 탑승한 상태를 일반 출발지에서 재검색하는 것과 구별한다.

야간 일정을 지원하므로 자정·막차·운행 종료는 첫 구현의 필수 시험이다. 실제 막차를 놓치면 다음 운행일 열차를 당일 연결로 만들지 않는다. 대체 교통수단 지원이 없으면 지원 범위 내 경로 없음과 데이터 미제공을 구분한다.

## 11. O01: 보존·Replay·인프라 전달

- `archiver/src/config.py`: 새 raw/poll/impact 토픽을 보존할지 계약별 결정. 현재 transport.raw/city.raw 원본 보존은 유지한다.
- `archiver/src/replay.py`: event time·재생 배속·중복·순서역전 fixture를 추가. 미래 정보가 과거 평가에 들어가지 않게 asOf를 고정한다.
- 기준선/사건/시간표/모델 artifact manifest와 원본 참조를 함께 보존한다. hash만 있고 원본을 잃으면 재현할 수 없으므로 보존 경로와 기간을 지정한다.
- 인프라 담당 전달: 새 topic/consumer group, Spark job별 checkpoint, Redis key/TTL/memory, artifact 배포 SHA, Backend worker·lease, outbox retry, 키 그룹 쿼터, 모니터링·보존 설정.
- 데이터 작업에서 `infra/`, Docker, `.gitlab-ci.yml`을 직접 수정하지 않는다. 필요한 환경변수·포트가 아니라 논리 인터페이스와 자원 산정표를 전달한다.

필수 지표: source별 원천 age/호출 성공·부분 실패/429/예산잔여, 매핑 EXACT 비율·미매핑 이유, 사건 active→stale/ended 전이, Kafka lag, Spark 처리지연·state 크기, Redis missing/stale, 평가 queue age·p95 latency·lease 만료·검색 미완료 비율, 모델 cache hit, 출발/도착 오차, outbox 지연·실패·중복 억제.

## 12. 삭제·대체·유지 목록

| 대상 | 결론·삭제 조건 |
|---|---|
| P 입력, planned_depart_at, DEPARTURE_REQUIRED | **유지**. 이전 제거 계획 철회 |
| Client의 D−API총시간 역산 | 서버 v2 연결 뒤 대체. 단순 후보 화면에는 추천인 척 표시하지 않음 |
| 항상 D에서 최신 출발을 찾는 정책 | ConditionalDeparturePolicy의 P 우선 비교로 대체 |
| `DepartureSuggestionService` 기존 격자 탐색 | 유용한 예산/평가 루프 재사용, `< D`와 `<= T` 분리·동적 재평가 연결 |
| `service/DepartureSolver.java`, `TravelTimeEstimator.java` | 참조 검색·테스트·대체 완료 후 중복 부분만 삭제 후보 |
| `service/RecommendationService.java`, `RecommendationReasonWriter.java` | 과거 근거 조회 유지. writer는 새 versioned 근거로 이행 |
| RouteSearchService/BusJourneyEvaluator | 실제 검색에서 사용 중. 이름이 비슷하다는 이유로 삭제 금지 |
| 경로 후보 생성·매핑·route_segment_edge writer | 재사용 |
| 기존 migration·과거 추천/알림·원본 | 유지, 새 migration/adapter로 이행 |
| 미커밋 환승 코드·자료·별도 수집 전달 문서 | 이번 문서 작업에서 수정·추가 커밋하지 않음 |

## 13. 구현 순서·기능 활성화

1. **C01:** v2 계약과 합성 fixture를 먼저 만들고 Client/Backend가 같은 값·경계로 해석하는지 확인한다.
2. **B01+B02:** P 유지 분기·조건부 T 탐색·작업 영속화까지 가장 작은 end-to-end를 구현한다. 합성 fixture로 경로·환승·경계·예산을 검증한다.
3. **D01/D02/M01 병행 준비:** 실시간 호출 예산·원천 필드·도로/열차 대응·모델 품질을 확보한다. 데이터 미확보는 해당 기능 비활성화로 표현한다.
4. **D03+B03:** 사건 변화가 해당 일정 wake로 이어지고 같은 snapshot으로 재계산되는지 검증한다.
5. **F01+N01:** 화면은 서버 결과로 통일하고 outbox를 연결한다. 처음에는 shadow/검증 모드로 기존 결과와 비교하며 자동 운영 권고를 열지 않는다.
6. **J01+O01:** 출발 이후/야간/장애/Replay/부하 검증 후 지원 범위를 명시해 기능을 단계 활성화한다.
7. 구버전 Client와 기존 result_json 조회가 안전한 상태에서 중복 계산기와 adapter 제거 여부를 결정한다.

운영 활성화 게이트는 서로 독립적이다: P/D 정책 fixture 통과, 시간 분해 검증, ML production 승인, 도로 FULL 근거·매핑 검증, 열차 crosswalk 범위 검증, 쿼터 내 신선도, 알림 복구 검증. 일부 통과만으로 다른 기능의 정확도를 보증하지 않는다. 실패 시 기능 flag로 이전 조회/명시적 fallback을 유지하며 DB를 롤백 삭제하지 않는다.

## 14. 파트별 인수 테스트

| ID | 시나리오 | 기대 결과/검증 위치 |
|---|---|---|
| T01 | P08:30,D09:00, ETA08:59:59 | P 유지·buffer0, policy 단위 테스트 |
| T02 | ETA09:00:00 | T08:50 탐색·600초, 경계 1초 차이 검증 |
| T03 | 출발08:20에서 ETA08:50 | T equality 성공, 10분 이중 가산 없음 |
| T04 | P의 다른 후보만 정시 전 도착 | P 유지+경로 제안, 선택 경로 자동 변경 없음 |
| T05 | 앞당긴 출발에서 환승 차량 변경 | 전체 재평가, 고정 소요 뺄셈 결과와 구별 |
| T06 | 지금 출발08:55 도착, T08:50 | MARGIN_UNAVAILABLE, 지각으로 표시 금지 |
| T07 | 지금 출발09:05 도착 | 가장 빠른 도착과 300초 지각, 과거 출발 추천 금지 |
| T08 | ETA 불명확/후보 예산 소진 | DATA_INSUFFICIENT/SEARCH_INCOMPLETE 구분 |
| T09 | opposite direction/통과시간 이후 통제 | 차단 안 함, road fixture |
| T10 | FULL·EXACT·시간 겹침 통제 | 후보 제외, 원천 사건/구간 추적 |
| T11 | PARTIAL 통제·지연 근거 없음 | 임의 지연분 가산 금지 |
| T12 | 공지 만료·poll 실패·페이지 일부만 성공 | 해제/정상으로 오판하지 않음 |
| T13 | trainCode와 trainId 불일치·다른 운행일 | realtime 결합 거부, fallback/unknown |
| T14 | 열차 무정차·취소·막차·25시 | 해당 운행 제외·올바른 날짜/다음 후보 |
| T15 | 원천 stale·수집시각만 최신 | 실시간 사용 거부, sourceGeneratedAt 유지 |
| T16 | 일정 수정 중 계산 완료 | 이전 inputRevision 결과 저장 거부 |
| T17 | lease 만료 후 늦은 완료 | 새 소유자 결과 보존 |
| T18 | 계산 중 사건 revision 증가 | 최신 평가 재예약, 이전 결과 수락 제한 |
| T19 | D 기준 tick/늦은 등록/출발 이전 장거리 | 정확한 다음 미래 tick, 과거 tick 폭주 없음 |
| T20 | 반복 일정 출발·전날 offset·P null 이행 | 올바른 P/D, DEPARTURE_REQUIRED 보존 |
| T21 | 동일 입력·시각·모델/마스터로 Replay | 결과·decision·원인·다음 tick 동일 |
| T22 | ML 버전 변경/지원일 밖/일부 edge 누락 | 혼합 금지, PREVIEW/UNAVAILABLE 유지 |
| T23 | FCM 실패/성공 전 crash/중복 이벤트 | 제한 재시도·stale 취소·Client dedup |
| T24 | 출발 확인 후 늦게 조기 출발 알림 실행 | 취소. 남은 여정만 평가 |
| T25 | 앱 일정·홈·추천·알림 | 같은 평가 ID의 ETA/P/D·품질 표시 일치 |
| T26 | 구버전 JSON/Client가 새 enum 수신 | 과거 조회 정상, 안전한 unknown 표시 |
| T27 | 출발지→첫 BUS/SUBWAY endpoint | 경도·위도 순서의 ACCESS_WALK 요청, 응답 초·m·geometry 보존 |
| T28 | 마지막 BUS/SUBWAY endpoint→목적지 | EGRESS_WALK 요청 방향이 뒤집히지 않음 |
| T29 | BUS→WALK→SUBWAY 및 SUBWAY→WALK→BUS | 중간 WALK는 TRANSFER_WALK, 접근/이탈 API로 교체하지 않음 |
| T30 | path endpoint와 EXACT 마스터 좌표 충돌 | 도보 호출 생략, WALK_ENDPOINT_UNVERIFIED fallback |
| T31 | 도보 제휴 권한 없음/403·429·timeout | 대중교통 WALK fallback, 전체 후보 실패로 변경하지 않음 |
| T32 | 원본 ACCESS WALK와 별도 WALK 모두 존재 | 하나만 계산하고 durationSource 기록 |
| T33 | 원본 ACCESS WALK 없음·unassigned 비용 미검증 | 별도 WALK를 providerTotal에 가산하지 않고 PARTIAL/UNKNOWN |
| T34 | 여러 버스 대안의 승차 좌표가 다름 | 선택한 EXACT 대안 좌표만 사용, 대안 간 혼합 금지 |

테스트 파일 위치:

- Backend 정책/순수 계산: `backend/src/test/java/com/ssafy/ddubi/domain/recommendation/evaluation/`에 ConditionalDeparturePolicyTest·DepartureSuggestionServiceTest 확장.
- Backend 저장/작업: `.../domain/recommendation/service/`, 일정/반복 `.../domain/schedule/service/`, 알림 `.../domain/notification/`에 DB·동시성/전송 fixture.
- Collector `collector/tests`, Spark `sparkjobs/tests`, ML `ml-serving/tests`, Client `client/test`에 위 담당별 fixture와 contract test.
- 원천 예제는 비밀키·사용자 정보 없이 저장하고 기계 schema에 통과시킨다. 실제 값과 합성 값을 manifest로 구별한다.

실제 구현 시 Backend는 저장소 pom.xml에 맞춘 Maven 관련 테스트, Client는 `flutter test`, Python 파트는 해당 디렉터리에서 `python -m pytest -q`를 실행한다. 실시간 전체 흐름은 테스트 Kafka/Redis/DB와 고정 clock으로 검증한다. 성능 검증은 고정 입력량·worker 수·원천 호출 예산을 기록하고 정확도 시험과 따로 보고한다.

**이번 문서 수정의 검증 범위는 참조 경로·링크·JSON 예제·조건부 수식·diff다. 위 구현 테스트를 이미 통과했다는 의미가 아니다.**

## 15. 파트별 실행 작업 카드

이 절은 각 담당자가 이 문서만 보고 이슈를 쪼갤 수 있도록 **선행 입력 → 실제 수정 순서 → 산출물 → 실패 처리 → 완료 증거**를 고정한다. 경로에 `(추가)`가 붙은 파일은 목표 구조이며, 구현 중 동일 책임의 기존 파일이 확인되면 중복 파일을 만들지 말고 그 파일을 확장한다.

### 15.1 C01 계약 담당

**선행 입력:** 이 문서 2절의 v2 예제, 계산 정책의 decision/quality 정의, 기존 Controller 응답 fixture.

1. `docs/contracts/schedule-evaluation-v2.schema.json`을 추가하여 필수 필드, nullable, enum, `date-time`, 초 단위를 기계 검증한다.
2. `resultVersion=2`를 최상위에 두고 `decision`, `executionStatus`, `quality`를 서로 다른 enum으로 정의한다.
3. `plannedDepartAt`, `userInputDepartAt`, `proposedDepartAt`, `actualDepartAt`을 한 필드로 합치지 않는다.
4. timeline leg와 cause를 `$defs`로 만들고 `additionalProperties` 정책을 명시한다. 구버전 Client를 위해 최상위 확장 필드는 허용하더라도 핵심 객체의 오타는 schema가 거부하게 한다.
5. 정상, `DATA_INSUFFICIENT`, `SEARCH_INCOMPLETE`, FULL 통제, 과거 P, PREVIEW 예제를 fixture로 저장한다.
6. Backend Jackson 직렬화 결과와 Client fixture parser가 같은 파일을 읽는 contract test를 만든다.

**산출물:** schema, 예제 6종 이상, 필드 변경표, Client/Backend contract test. **실패 처리:** enum 합의가 끝나지 않으면 문자열을 임의 추가하지 않고 schema PR에서 미확정 목록으로 남긴다. **완료 증거:** 모든 예제가 schema를 통과하고 단위·시각 경계 T01~T03을 양쪽 테스트가 같은 값으로 해석한다.

### 15.2 B01 일정·반복 일정 담당

**수정 순서:** request DTO → service 검증 → migration/entity → 평가 작업 생성 → response DTO → 반복 materialize → 통합 테스트 순서로 진행한다.

```text
create(request):
  P = request.plannedDepartAt
  D = request.arriveBy
  require P != null, D != null, P < D
  transaction:
    schedule.userInputDepartAt = P
    schedule.plannedDepartAt = P
    schedule.planSource = USER_INPUT
    schedule.inputRevision = 1
    insert schedule_evaluation(nextCheckAt=now, inputRevision=1)
  afterCommit: worker.wake(scheduleId)
```

update는 patch가 적용된 **최종 P,D**를 검증한다. 사용자가 P를 직접 바꾼 경우 `userInputDepartAt=P`, `planSource=USER_INPUT`, revision 증가, 이전 추천/알림 취소, `nextCheckAt=now`를 한 트랜잭션에서 처리한다. 제목만 바뀌었을 때도 현재 구현 정책상 경로 입력에 영향을 주는지 분류하고, 영향 없는 변경으로 불필요한 외부 호출을 만들지 않는다.

반복 일정은 `occurrenceDate + plannedDepartDayOffset + plannedDepartTime`으로 P를, 기존 `occurrenceDate + localTime`으로 D를 만든 뒤 P<D를 검증한다. offset=-1이면 전날이다. DST가 없는 Asia/Seoul이어도 `LocalDateTime` 조합 뒤 명시적으로 zone을 붙인다. 이미 개별 수정·취소된 occurrence는 재생성으로 덮지 않는다.

**DB 이행:** 기존 P가 있으면 `user_input_depart_at=P`, `plan_source=LEGACY_UNKNOWN`; 기존 P가 없으면 null을 유지하여 `DEPARTURE_REQUIRED`로 노출한다. D에서 임의 역산하여 채우지 않는다. migration rollback을 데이터 삭제로 설계하지 않는다.

**완료 증거:** create/update/accept/repeat/legacy 각각 통합 테스트, 트랜잭션 rollback 시 평가 행도 남지 않는 테스트, afterCommit wake 유실 시 DB due가 다시 집는 테스트.

### 15.3 B02 후보 조립·계산 담당

**호출 순서:**

```text
ScheduleEvaluationWorkflow.evaluate(claim)
  1. RouteCandidateProvider.loadOrRefresh(schedule, snapshotAsOf)
  2. RouteLegRoleClassifier.classify(steps)
  3. MasterDataTransitMappingResolver.resolve(BUS/SUBWAY)
  4. WalkingEndpointResolver.resolve(EXACT mapped first/last transit)
  5. WalkingRouteClient.fetch ACCESS/EGRESS with request budget
  6. RouteEvidenceAssembler.freeze all evidence and versions
  7. JourneyEvaluationService.evaluate each candidate at P
  8. ConditionalDeparturePolicy.decide P branch
  9. if required, DepartureSuggestionService.search T
 10. RecommendationChangeAnalyzer.compare previous/current
 11. ScheduleEvaluationStore.complete(result, nextCheckAt, outbox)
```

1~6에서 외부/Redis/DB 읽기를 끝내고 불변 `EvaluationSnapshot`을 만든다. 7~9의 반복 평가에서는 snapshot을 다시 읽지 않는다. 다만 출발시각에 따라 차량 선택, hourSlot, 사건 겹침은 매번 다시 계산한다.

`JourneyEvaluationService`는 `EvaluationInput(candidate, departAt, D, snapshot)`을 받고 `CandidateEvaluation`을 반환한다. 예외를 catch하여 임의의 큰 소요시간으로 바꾸지 말고 `usable`, `quality`, `failureReasons`로 반환한다. 필수 leg 누락은 INSUFFICIENT, 공식 FULL 통제는 UNUSABLE, 외부 timeout은 FALLBACK 가능 여부에 따라 분기한다.

탐색은 시간표에서 생성한 후보와 제한 격자를 합치고 중복 절대시각을 제거한 뒤 늦은 순으로 검증한다. 한 후보가 T를 만족해도 그보다 늦은 미검증 후보가 남아 있으면 최신 출발로 확정하지 않는다. 예산 종료 시 `SEARCH_INCOMPLETE`, `searchedFrom`, `searchedUntil`, `evaluatedDepartures`, `remainingCandidateCount`를 남긴다.

**완료 증거:** T01~T08, T27~T34와 timeline 합계=durationSeconds, snapshot hash 고정, 동일 입력의 외부 호출 cache hit를 검증한다.

### 15.4 D01 Collector 담당

**source 하나를 추가하거나 고칠 때의 동일한 절차:**

1. 키를 제거한 raw fixture에 정상, 정상 빈 응답, 일부 페이지, 인증 오류, rate limit, 오래된 응답을 넣는다.
2. source 모듈은 HTTP 상태와 원천 내부 결과코드를 함께 검사한다. HTTP 200의 오류 XML/JSON을 성공으로 발행하지 않는다.
3. 원천 ID·발생시각·페이지 완전성·대상 scope를 검증하고 raw envelope로 Kafka에 발행한다.
4. Kafka ACK 후에만 dedup/request cursor를 갱신한다. 발행 실패는 성공 poll로 기록하지 않는다.
5. `SourcePollStatus`에 SUCCESS/EMPTY_COMPLETE/PARTIAL/FAILED/RATE_LIMITED/SCHEDULED_SKIP, pagesExpected/pagesReceived, sourceGeneratedAt, nextAllowedAt을 기록한다.
6. 본문 revision과 ‘이번 poll에서 여전히 존재함을 확인’한 observedAt을 분리한다.

`DemandRegistry`는 `scopeKey`로 합치고 `neededFrom<=now<expiresAt`인 항목만 활성화한다. 한 사용자의 일정이 여러 개여도 provider 요청은 한 번이다. artifact 파싱 실패 시 마지막 검증 버전을 사용하되 만료 뒤에는 고정 최소 scope 또는 수집 불가 상태로 전환한다. 사용자 ID·제목·정확한 출발지 좌표를 artifact에 넣지 않는다.

**완료 증거:** source별 fixture/공식 필드표/실제 호출량 산정, 24시간 rolling budget 테스트, Kafka 실패 후 재시도 멱등성, poll-status 계약 테스트.

### 15.5 D02 마스터·매핑 담당

**버스:** 공식 노선-정류장 순서에서 `(providerNamespace, routeId, fromStopId, toStopId, fromOccurrence, toOccurrence)`를 만들고 인접성·방향을 검증한다. canonical segment ID를 기존 factory와 동일 규칙으로 생성하고 중복/순환 노선은 occurrence로 구별한다.

**지하철:** 역 마스터 매핑과 운행 매핑을 분리한다. 역 이름 일치는 후보 생성일 뿐이다. `serviceDate/dayType + line + direction + trainPattern + stopSequence`가 확인된 경우만 staticTripKey와 providerTrainId를 EXACT로 올린다.

**도로:** 공식 버스 구간→BIS 링크→공간 링크 방향→TOPIS 표준링크→AccInfo namespace 순서로 evidence chain을 만든다. 중간 파일 하나라도 버전/방향을 검증하지 못하면 mappingStatus를 낮춘다.

모든 artifact는 `artifactVersion, createdAt, validFrom, validUntil, sourceFiles[{name,sha256,officialRef}], recordCount, rejectedCount, builderVersion` manifest와 함께 원자 배포한다. Backend와 Spark가 시작할 때 expected SHA를 검사한다. 이전 artifact를 삭제하지 말고 Replay 기간 동안 보존한다.

**완료 증거:** 같은 입력의 byte-stable artifact, mapping 결과별 통계, AMBIGUOUS/UNMAPPED 샘플, Backend/Spark loader 계약 테스트.

### 15.6 D03 Spark 담당

각 잡은 `parse → validate → normalize → deduplicate → event-time join/state → output/rejected` 순서를 지킨다. parser/normalize는 배치 DataFrame에서도 실행 가능한 순수 함수로 분리한다.

| 잡 | 상태 키 | 핵심 join | 출력 전 게이트 |
|---|---|---|---|
| realtime latest | source+provider entity | 동일 키의 최신 occurredAt/revision | future/stale/invalid ID 제외, TTL과 validUntil 일치 |
| trip stop prediction | serviceDate+tripKey+stopSequence | timetable left join realtime EXACT crosswalk | 다른 운행일·방향 결합 금지 |
| incident lifecycle | source+incidentKey | revision과 complete poll heartbeat | poll 실패를 ENDED로 전이 금지 |
| road/transit impact | incident revision+mapping version | incident 범위와 edge/trip artifact | PARTIAL/AMBIGUOUS를 confirmed 영향으로 승격 금지 |
| segment status | segmentId+window | ACTUAL_TRAVERSAL과 활성 baseline version | p50·표본·freshness 없으면 unknown |
| segment baseline | segmentId+dow+hour+weather | 검증된 fact만 group | p50≤p80≤p95, sampleCount, 전량 upload 후 activate |

watermark 밖 데이터는 최신 상태를 되돌리지 않지만 raw/rejected에서 추적한다. Redis 쓰기 성공 전에 변경 Kafka 이벤트를 발행하지 않는다. 다중 sink 원자성이 없으면 deterministic eventId와 재시도 시 멱등 upsert로 복구한다.

**완료 증거:** 중복·순서역전·늦은 이벤트·checkpoint 재시작·Redis 부분 실패·Replay 동일성 테스트와 state 크기/lag 측정.

### 15.7 B03 스케줄러·사건 wake 담당

`ScheduleMonitoringPolicy.next(D,asOf)`는 D 기준 tick 표에서 다음 미래 시각을 반환하는 순수 함수로 만든다. DB due query는 `next_check_at<=now`, UPCOMING, NOT_STARTED, D가 지나지 않은 행을 batch로 읽고 `lease_token/lease_until`을 조건부 갱신한다.

```text
claim: READY/RETRY -> RUNNING, attempts+1, lease 발급
complete current revision: RUNNING -> READY(next tick) 또는 FINISHED
retryable failure: RUNNING -> RETRY(nextAttemptAt, errorCode)
permanent input failure: RUNNING -> WAITING_INPUT
lease timeout: 다른 worker가 새 token으로 재claim
old token complete: 저장 거부
```

사건 listener는 이벤트 payload의 segmentIds를 그대로 신뢰하지 않고 mappingVersion과 `route_segment_edge`를 확인한다. `processed_impact_event` insert와 일정 `event_revision+1/next_check_at=min(current,now)`를 같은 트랜잭션에 둔다. 동일 eventId는 멱등이고 같은 incident의 새 revision은 새 평가다.

**완료 증거:** T16~T19, worker 2개 동시 claim, lease 만료, listener redelivery, 일정 수정과 complete 경쟁 테스트.

### 15.8 N01 알림 담당

`RecommendationChangeAnalyzer`는 이전과 새 v2 결과를 입력받아 `ChangeSet`을 만든다. 비교 항목은 decision, proposedDepartAt 초 차이, routeId, usable, on-time 가능성, quality, cause revision이다. 문구 생성 전에 알림 필요 여부와 templateCode를 결정한다.

평가 완료 트랜잭션은 notification을 직접 보내지 않고 outbox PENDING만 저장한다. dispatcher는 짧은 트랜잭션으로 lease를 잡고, 트랜잭션 밖에서 FCM을 호출한 뒤 별도 트랜잭션으로 SENT/RETRY/FAILED를 저장한다. 발송 직전 schedule inputRevision, journeyState, payload validUntil을 다시 확인한다.

```text
PENDING/RETRY -> SENDING (lease)
SENDING + provider success -> SENT
SENDING + retryable -> RETRY(nextAttemptAt)
SENDING + permanent token failure -> 다른 device 결과 확인 후 FAILED 또는 SENT
obsolete revision/departed/expired -> CANCELED
alarm off/no device/policy suppressed -> SUPPRESSED
```

FCM 성공 뒤 ack 전에 죽는 경우 중복 가능성을 인정하고 `notificationId/dedupKey`를 Client에 보낸다. raw 사건 본문과 API key는 payload에 넣지 않는다.

**완료 증거:** T23, 알람 off, 장치 없음, 일부 device 성공, timeout 후 재시도, stale 취소, 같은 incident revision 중복 억제 테스트.

### 15.9 F01 Client 담당

일정 등록 화면은 P와 D를 별도 컨트롤로 유지하고 P<D를 로컬에서 먼저 검증하되 서버 오류를 최종 기준으로 표시한다. 등록 성공 후 일정 상세로 이동하여 latest endpoint를 조회하고 P를 추천값으로 덮어쓰지 않는다.

| 서버 상태 | 화면 행동 |
|---|---|
| PENDING/RUNNING | 마지막 유효 결과가 있으면 시각·최신성을 함께 표시, 없으면 계산 중 |
| KEEP_DEPARTURE | 입력 P와 ETA 표시, 10분 여유 적용 문구 없음 |
| ADVANCE_DEPARTURE | 현재 P, 제안 P, 목표 T, ETA, 수락 버튼 |
| MARGIN_UNAVAILABLE | 가능한 출발/ETA와 ‘10분 여유 미달’을 함께 표시 |
| ON_TIME_UNLIKELY | 가장 빠른 ETA와 지각 예상, 과거 출발 권고 금지 |
| DATA_INSUFFICIENT/STALE | 누락 원천·마지막 확인시각, 0분/정상 기본값 금지 |
| SEARCH_INCOMPLETE | 현재 최선과 검색 범위, 확정 최신 출발 문구 금지 |

수락은 evaluationId만 보내고 409이면 최신 결과를 다시 읽는다. 알림 딥링크도 evaluationId의 유효성을 재조회한다. 앱 polling은 재계산을 매번 요청하지 않으며 백그라운드/종료 일정에서 중단한다.

**완료 증거:** 각 상태 golden/widget test, nullable/unknown enum parser, 편집 후 입력 P 보존, 알림 중복 표시 억제, 409/503 복구 테스트.

### 15.10 J01·O01 이동 중·Replay·운영 담당

출발 endpoint는 최초 요청에서만 `actualDepartAt`을 설정하고 IN_PROGRESS로 전이한다. 위치 sequence가 이전 이하이면 무시/409 처리하고, 미래 관측·허용 정확도 밖 값은 low quality로 격리한다. 남은 여정 계산은 현재 위치에서 일반 길찾기를 매번 새로 하는 단일 방식으로 만들지 않고 탑승 확인 상태가 있으면 해당 trip/vehicle의 남은 stop부터 계산한다.

Replay manifest에는 `asOf, inputRevision, candidatePayloadHash, raw archive refs, timetable/master/model/policy versions, active baseline version, event revisions, random seed(사용 시)`를 넣는다. 재생 중 wall clock이나 현재 Redis를 읽지 않는다. 동일 manifest 결과의 decision/timeline/cause/nextCheckAt hash를 비교한다.

운영 대시보드는 최소 source age, queue age, evaluation p95, SEARCH_INCOMPLETE, stale/unknown 비율, outbox retry, mapping EXACT 비율을 기능 flag와 함께 보여 준다. 데이터가 오래되면 알림과 추천 수락을 어떤 순서로 제한하는지 runbook에 기록한다.

**완료 증거:** 출발/도착 멱등성, 위치 순서역전, 막차·자정, 동일 Replay hash, 장애별 기능 제한과 복구 절차를 staging에서 검증한다.

## 16. PR·인계 단위 완료 체크리스트

각 작업 PR은 아래 항목을 모두 적는다.

- 변경한 입력 원천과 정확한 필드·ID namespace·시각·단위
- 변경한 파일과 공개 메서드, 새 DB/Kafka/Redis/API 계약
- 정상 처리 순서와 결측·stale·중복·순서역전·외부 실패 처리
- 결과에 남기는 source/revision/version/confidence/validUntil
- 실행한 단위·계약·통합·Replay 테스트와 미실행 사유
- 기능 flag, 구버전 호환, rollback 시 유지할 데이터
- 다음 파트가 받을 fixture·schema·artifact SHA와 담당자

‘API 연결’, ‘실시간 반영’, ‘알림 완료’처럼 범위를 확인할 수 없는 문구만으로 완료 처리하지 않는다. 예를 들어 ‘버스 실시간 반영’은 어느 원천의 어떤 ETA 필드를 어떤 절대시각으로 만들고, 어떤 노선/정류장 매핑과 freshness를 통과했으며, 어떤 fallback과 fixture로 검증했는지까지 있어야 한다.
