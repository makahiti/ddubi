# BE/ML 작업 가이드 — 일정 입력부터 최종 추천·알림까지

- 상태: 구현 전 작업 명세. 파일·인터페이스의 추가안은 구현 완료를 뜻하지 않는다.
- 작업 기준: [공통 구현 계획](arrival-deadline-implementation-plan.md), [계산 정책](arrival-deadline-calculation.md), [원천·확보 목록](arrival-deadline-data-guide.md).
- 책임: 사용자 일정·경로 후보·DB 저장·여정 계산·조건부 출발 탐색·모델 제공·작업 스케줄링·알림을 BE/ML이 맡는다. 외부 교통자료의 확보·정규화·공용 artifact 생산은 DATA, 화면·입력·동의는 FE와 연결한다.
- 모든 데이터는 확보 증거가 있어야 사용한다. 테스트 fixture, 파일 존재, API 코드 존재는 운영 데이터 확보 증거가 아니다.

## 1. 최종적으로 만들어야 하는 기능

사용자가 P=08:30, D=09:00을 입력하면 서버가 실제 후보의 전체 여정을 계산한다. ETA=08:59이면 08:30을 유지한다. ETA=09:00 이상이면 T=08:50을 목표로 다시 탐색하여 ‘08:20 출발 → 08:50 도착 예상’처럼 제시한다. 새 출발을 수락하기 전 사용자 P와 선택 경로를 덮어쓰지 않는다.

계산은 `접근 도보 → 차량 대기 → 탑승 → 환승 도보 → 다음 대기/탑승 → 최종 도보`의 시간순 연결이다. 도보·환승·버스/지하철 이동시간을 단순 총합하는 것만으로는 다음 차량 탑승 가능 여부를 판단할 수 없다.

## 2. 작업 목록과 순서

| 작업 | 목적 | 선행 입력 | 완료 결과 |
|---|---|---|---|
| BM01 일정·응답 계약 | 입력과 추천의 소유권 고정 | FE 입력 합의, 공통 v2 명세 | DTO/schema/반복 일정/입력 revision |
| BM02 후보·도보·매핑 | 외부 후보를 계산 가능한 leg로 조립 | 카카오 권한/샘플, DATA 마스터 | 순서 있는 candidate와 비용 범위 |
| BM03 여정 계산 | 출발별 차량·환승·ETA 평가 | DATA 최신값/시간표/사건 | timeline와 근거·품질 |
| BM04 ML | 버스 이동 예측의 입력·정답·운영 범위 검증 | DATA 원본·정답·manifest | 버전 지정 예측/fallback |
| BM05 조건부 출발 | P 유지 또는 T 목표 탐색 | BM03/BM04 | decision·출발/도착·탐색 한계 |
| BM06 스케줄러 | 등록·D 기준 tick·사건 재평가 | 일정 DB, DATA 변경 이벤트 | lease/revision 보호 작업 |
| BM07 저장·수락·알림 | 사용자가 신뢰할 결과 전달 | BM05/BM06, FE DTO | 최신 결과·수락·outbox |
| BM08 이동 중·연동 | 출발 이후 남은 경로 평가 | FE 위치 동의/상태 | IN_PROGRESS/ARRIVED 처리·통합검증 |

BM01과 fixture 기반 BM02~BM07은 데이터 확보 중에도 개발할 수 있다. 실제 운행 반영과 운영 추천 활성화는 source별 확보·매핑·신선도 검증 후 진행한다.

## 3. BM01 — 일정 입력, DB, 반복 일정, API

**파일:** `backend/src/main/java/com/ssafy/ddubi/domain/schedule/` 아래 `dto/request/ScheduleCreateRequest.java`, `ScheduleUpdateRequest.java`, `RepeatCreateRequest.java`, `RepeatUpdateRequest.java`, `entity/Schedule.java`, `service/ScheduleService.java`, `ScheduleRepeatService.java`, `dto/response/ScheduleResponse.java`. DB는 `backend/src/main/resources/db/migration/`의 다음 Flyway 번호로 추가한다.

1. 신규 입력에서 `plannedDepartAt=P`, `arriveBy=D`, 출발/목적 좌표를 필수 검증한다. P<D, 미래 D, 지원 지역, 소유권을 검사한다.
2. `user_input_depart_at`, `plan_source`, `input_revision`, `journey_state`, `actual_depart_at` 추가안을 공통 계획 3.2절과 맞춘다. 기존 P=null은 DEPARTURE_REQUIRED이며 D에서 임의 역산하지 않는다.
3. create 트랜잭션에서 일정과 `schedule_evaluation.next_check_at=now`를 함께 저장한다. 커밋 후 worker를 깨우고 외부 API는 DB 트랜잭션 밖에서 호출한다.
4. update는 patch 적용 후 최종 P/D를 검사한다. 경로 입력 수정 시 revision 증가·기존 결과 무효화·대기 알림 취소·즉시 재평가를 원자 처리한다.
5. 반복 일정에는 `plannedDepartTime`, `plannedDepartDayOffset`을 추가한다. 발생 날짜+offset+time으로 P를 만든다. 전날 출발은 -1이며 개별 수정/취소 occurrence를 덮지 않는다.
6. 응답 계약은 공통 계획 2.2절의 `schemaVersion`, `state`, `decision`, `quality` 구조를 사용한다. DB의 input_revision은 응답 inputVersion에 매핑하고 중복 이름을 만들지 않는다.

**완료 시험:** P=D 입력 오류, P=null 기존 일정 조회, 전날 출발 반복 일정, 저장 rollback 시 평가 행도 rollback, 수정과 계산 완료의 경쟁. FE에 정상/입력오류/409 예제를 전달한다.

## 4. BM02 — 카카오 후보 분해·마스터 매핑·접근/이탈 도보

**입력 출처:** S02 카카오 대중교통 응답, S02-W 카카오모빌리티 제휴 도보, DATA가 제공한 버스/지하철 마스터. 제휴 도보 권한은 확보 미확인이므로 fallback을 먼저 완성한다. raw 필드는 데이터 가이드 3절을 사용한다.

**파일:** 기존 `PublicTransitRouteClient`, `MasterDataTransitMappingResolver`, `CanonicalSegmentIdFactory`, `RouteCandidateAssembler`, `RouteSegmentEdgeWriter`를 재사용한다. `domain/recommendation/evaluation/RouteLegRoleClassifier.java`, `WalkingEndpointResolver.java`, `WalkingEvidence.java`, `domain/route/client/KakaoWalkingRouteClient.java`는 추가 제안이다.

1. `routes[].steps[]`를 순서대로 읽어 BUS/SUBWAY/WALK로 정규화한다. 차량 대안이 여러 개면 각각 보존한다.
2. BUS/SUBWAY의 노선·승하차·방향·경유 순서를 마스터에 매핑한다. EXACT/PARTIAL/AMBIGUOUS/UNMAPPED와 이유를 남긴다.
3. WALK 양옆의 transit leg를 확인하여 ACCESS/TRANSFER/EGRESS로 분류한다.
4. 첫 transit의 시작, 마지막 transit의 끝 geometry는 endpoint 후보로만 취급한다. EXACT 마스터 좌표와 비교하며 역 중심점만 있으면 출입구 확정으로 표시하지 않는다.
5. 검증 성공 시 `출발 좌표→첫 승차 좌표`, `마지막 하차 좌표→목적 좌표`를 도보 API에 요청한다. 요청 좌표 순서는 경도,위도다.
6. 별도 도보 성공값은 동일 역할의 원본 WALK를 교체한다. 환승 WALK는 유지한다. API 전체시간에 도보를 또 더하지 않는다.
7. 모든 leg 비용 범위를 설명하지 못하면 PROVIDER_TOTAL fallback으로 남기고 미분해 시간을 WAIT로 임의 변경하지 않는다.
8. 저장 시 각 transit step을 인접 edge로 전개해 `route_segment_edge`를 기록한다. provider ID를 canonical segmentId로 사용하지 않는다.

**실패:** 403/429/timeout, 좌표 충돌, 마스터 미적재는 구분된 fallback reason이다. 실패 도보 0초, 임의 직선거리 도보, 서로 다른 차량 대안의 edge 합치기를 금지한다.

**완료 시험:** 기존 T27~T34, WALK 교체 전후 합계, 순환노선 occurrence, 정류장/출입구 혼동. DATA에 미매핑 raw 샘플과 사유를 전달하고 FE에 leg별 geometry/source 예제를 전달한다.

## 5. BM03 — 시간순 여정 계산과 증거 스냅샷

**파일:** `domain/recommendation/evaluation/RouteEvidenceAssembler.java`, `JourneyEvidence.java`, `RouteTimelineEvaluator.java`, `BusWaitingService.java`, `SubwayTimingService.java`, `SubwayTimetable.java`, `RoadImpactEvidence.java`. `JourneyEvaluationService.java`, `EvaluationSnapshot.java`는 추가 제안이다.

1. 평가 시작에 asOf와 마스터·시간표·활성 baseline·모델 버전을 고정한다. Redis의 각 원천시각/validUntil과 poll status를 같이 읽는다.
2. 접근 도보 종료시각을 승차지 도착시각 a로 둔다. 버스는 `mkTm + exps1/2`로 만든 fresh 차량 ETA 중 a 이후 탑승 가능한 차량을 고른다. 만차·우회·운행 종료 코드를 검사한다.
3. 버스 이동은 검증된 모든 edge 예측 또는 검증된 한 가지 전체 step fallback을 사용한다. 각 edge 진입시각에서 serviceDate/hourSlot을 다시 만든다.
4. 지하철은 운행일·방향·정차패턴이 맞는 시간표 trip을 찾는다. DATA가 제공한 EXACT 운행 crosswalk가 있는 경우만 실시간 열차 ETA를 연결한다. 하차역 예측이 없으면 정적 이동시간과 ESTIMATED 한계를 기록한다.
5. 하차시각+환승 WALK로 다음 승강장 도착을 계산하고 놓친 차량은 다음 확인 가능한 운행으로 바꾼다. 없는 세 번째 버스나 막차 이후 당일 열차를 만들지 않는다.
6. 통제는 namespace·edge occurrence·방향·차종·통과시간 겹침·FULL 근거를 모두 검사한다. 부분 통제에 고정 지연분을 붙이지 않는다.
7. 마지막 도보 종료를 ETA로 둔다. duration은 ETA-departAt, leg 시간 합과 일치해야 한다.

**계산기 출력은 내부 DTO 추가안:** `candidateId, departAt, estimatedArrivalAt, durationSeconds, usable, timeline, missingInputs, fallbackReasons, causeRefs, snapshotRef`. 공개 API 이름은 공통 v2 DTO로 변환한다. usability·품질 부족을 큰 ETA 숫자 하나로 대체하지 않는다.

**예제:** 승강장 도착 08:32, 첫 차량 08:31, 둘째 08:36이면 대기 240초다. 둘째 탑승 때문에 08:45 환승을 놓치면 다음 연결 차량으로 뒷부분 전체를 다시 계산한다.

**완료 시험:** 원천 stale/순서역전/다른 운행일·방향, 시간표 25시, 앞 지연에 따른 통제 겹침 변화, 단일 snapshot 재현. DATA 공급이 없으면 정보 부족과 fallback이 정상적으로 반환돼야 한다.

## 6. BM04 — ML 데이터 점검·학습·서빙

**먼저 읽을 계약:** [ML 데이터 결정](ml-data-decision.md), [ML HTTP](ml-serving-http.md). 지하철과 일반 도보는 이번 ML 학습 대상이 아니다. 버스 시간대별 평균과 개별 차량 실측 소요시간의 정답을 혼합하지 않는다.

**파일:** `ml-serving/schemas.py`, `predictor.py`, `serving_runtime.py`, `app.py`, `reference_cache.py`; 추가 환경 입력을 실제 채택할 경우 `environment_schema.py`, `environment_features.py`, `environment_labels.py`, `tools/prepare_environment_training.py`, `tools/train_environment_model.py`. 도구가 있다는 이유로 원본/정답이 준비됐다고 판단하지 않는다.

1. DATA에서 원본 출처·SHA·행 단위·날짜 범위·ID·소요시간 정의·공개시각·결측률을 받는다. 정답 원천 미확보면 학습 완료 작업을 진행한 것으로 표시하지 않는다.
2. 모델별 입력 목록을 raw/파생/정답으로 나눈다. 미래 실제 날씨·사후 통계 등 예측 당시 알 수 없는 값을 입력에서 제외한다. 모든 신규 필드는 학습과 서빙 양쪽에서 확보 가능한지 확인한다.
3. 기존 결정대로 직선거리를 제거할 때 학습·서빙 전처리를 같이 바꾸고 재학습한다. 원천 없는 feature를 예측 때만 0으로 채우지 않는다.
4. 날짜 순서로 학습 70%/최종 테스트 30%를 나눈다. 학습 내부 검증으로 설정을 고르고 테스트 30%는 모델 선택에 쓰지 않는다. 예측 target별 표본 수·지원 ID·시간대·결측별 오차를 보고한다.
5. 학습 작업을 수행할 때만 기존 제약인 서버 GPU 0, CPU 최대 16, DuckDB 금지를 적용한다. 이 문서는 학습 실행이나 GPU 작업 지시가 아니다.
6. bundle에 modelVersion, target, featureSchemaVersion, source manifest, 지원 날짜/ID, 평가 결과를 묶는다. `tools/verify_bundle.py`로 로딩·입출력 일치를 확인한다.
7. `POST /v1/bus/predictions:batch` 입력은 실제 schema의 provider namespace/route/from/to/occurrence/service_date/hour_slot을 사용한다. segment_key로 응답을 연결하고 모델 버전·지원 범위·production flags를 검사한다.
8. BE `BusPreviewClient`/`MlRoutePreview`의 캐시는 모델+ID+occurrence+serviceDate+hourSlot+feature version 기준이다. 탐색 도중 모델 버전이 바뀌면 하나의 결과에 혼합하지 않는다.
9. 일부 edge 누락, 음수/NaN, 미지원 날짜, 승인되지 않은 모델은 PREVIEW/UNAVAILABLE와 fallback 사유를 반환한다. `production_ready=false` 제한을 조건문 삭제만으로 풀지 않는다.

**완료 결과:** feature dictionary, DATA 원천과 연결한 lineage, 정답 정의, 시간 분리 평가 보고서, batch 정상/부분/오류 fixture, 승인 범위. 미확보 데이터가 있으면 그 목록과 제거/결측 정책을 함께 제출한다.

## 7. BM05 — P 우선 평가와 제한된 최신 출발 탐색

**파일:** 추가 `ConditionalDeparturePolicy.java`, 기존 `DepartureSuggestionService.java`, `ScheduleEvaluationWorkflow.java`. 모든 탐색은 BM03의 동일 계산기를 호출한다.

```text
원래 P가 실행 가능하면 선택 경로와 대안을 P에서 평가
이용 가능하고 ETA < D인 후보가 있으면 P 유지(필요 시 경로 변경 제안)
같거나 늦으면 T = D - 600초
탐색 구간 = [asOf, min(P,T)] (별도 출발 가능 하한이 있으면 반영)
시간표/환승 연결로 출발 후보를 만들고 전체 정방향 계산으로 ETA <= T 검증
미래 버스 운행이 불명확하면 제한 격자 탐색, 결과 범위·해상도 표시
예산 끝이면 SEARCH_INCOMPLETE, 전체 불가능으로 단정하지 않음
```

P가 과거이면 effectiveDepartAt=asOf이며 원래 P는 보존한다. T 불가/D 전 가능은 MARGIN_UNAVAILABLE, 검증 범위 모두 ETA>=D이면 ON_TIME_UNLIKELY와 가장 빠른 ETA·지각초를 표시한다. 전체 데이터 부족은 DATA_INSUFFICIENT다. 이분 탐색은 단조성을 검증한 구간에만 적용한다.

**완료 시험:** 08:59:59 유지/09:00:00 조정, T equality 성공, 600초 중복 가산 없음, P 대안 유지, asOf 이후 하한, 예산 소진, 과거 설명값과 실행 추천 구별.

## 8. BM06 — 정기 실행·사건 wake·동시성

**파일:** `ScheduleMonitoringPolicy.java`, `ScheduleEvaluationScheduler.java`, `ScheduleEvaluationWakeup.java`, `ScheduleEvaluationStore.java`, `adapter/SegmentStatusListener.java`.

1. 등록 즉시 최초 실행하고 D-60,-50,-40,-30,-25,-20,-15,-10,-9…0분의 tick을 생성한다. 늦은 등록은 과거 tick을 재생하지 않고 즉시 계산 후 다음 미래 tick으로 간다.
2. due 작업을 lease로 claim하고 inputRevision/eventRevision/asOf를 고정한다. 외부 I/O는 transaction 밖에서 수행한다.
3. DATA의 변경 이벤트를 중복 제거하고 `route_segment_edge → route_segment → selected route → UPCOMING schedule`로 영향 일정을 찾는다. 선택+최신 제안 감시는 공통 계획의 범위만 사용한다.
4. 이벤트 처리 기록과 eventRevision 증가/wake를 같이 커밋한다. 계산 중 wake는 lease를 훔치지 않고 후속 평가를 예약한다.
5. complete는 lease 소유권·입력 revision·일정/이동 상태를 검사한다. 사건 revision이 바뀌면 오래된 결과 수락을 막고 즉시 최신 평가를 예약한다.
6. 429/5xx/timeout은 유한 backoff, 입력/인증 오류는 명시 오류로 남긴다. D 이후와 출발 확인 이후에는 출발 전 루프를 종료한다.

**완료 시험:** 두 worker 동시 claim, lease 만료 뒤 늦은 완료, 수정/취소/출발 경쟁, 이벤트 재전송, Redis 값 없음, D 기준 경계, 외부 호출 제한 복구.

## 9. BM07 — 결과 저장·수락·원인 알림

**파일:** `ScheduleEvaluationStore.java`, 추가 `RecommendationChangeAnalyzer.java`, `EvaluationNotificationDispatcher.java`, `domain/notification/service/NotificationService.java`, `NotificationCommand.java`, `SuppressionStore.java`; 새 outbox migration은 공통 계획 8.3절.

1. 최종 v2 결과·추천 근거·다음 tick·알림 outbox를 한 트랜잭션에 저장한다. 원본 응답/키를 사용자 DTO에 넣지 않는다.
2. accept(evaluationId)는 최신 revision·유효기간·productionEligible·실행 가능한 출발·소유권을 재검증한다. 성공할 때만 P/선택 경로를 바꾸고 직접 입력 이력을 보존한다. 오래된 결과는 409로 FE에 재조회시킨다.
3. 이전/현재 decision, 출발 변화, 경로, 이용 가능성, cause revision을 비교한다. 원인 없는 ETA 증가는 ‘원인 확인 중’이며 교통량 증가라고 추측하지 않는다.
4. 같은 사건 revision/의미의 변화는 dedupKey로 합친다. 임계값은 공통 정책 설정으로 관리하고 매분 평가 ID만으로 새 알림을 만들지 않는다.
5. dispatcher가 lease 후 transaction 밖에서 전송한다. 성공→SENT, 일시 실패→RETRY, 만료/출발/옛 입력→CANCELED. 성공 ack 이전 crash의 중복 가능성은 notificationId로 FE도 억제한다.

**완료 시험:** 중복 수락, 만료/preview 수락 차단, 공식 FULL 원인, 만료 사건을 해제로 오판하지 않음, FCM 실패/부분 성공/재시도, 출발 후 조기 출발 알림 취소.

## 10. BM08 — 이동 중 상태와 FE 연결

`/departure`를 감시 중단에서 NOT_STARTED→IN_PROGRESS 전이로 확장한다. 추가 제안 `JourneyStateService.java`, `JourneyPositionRequest.java`는 actualDepartAt을 최초에만 저장하고 중복 요청에 같은 상태를 반환한다. FE 위치는 좌표·accuracy·observedAt·sequence를 검증한다. 위치만으로 열차/버스 ID를 추정하지 않는다.

탑승 상태가 확인되면 남은 정차/환승부터, 하차·보행이 확인되면 현재 지점부터 재탐색한다. 정확한 현재 위치가 없으면 확인 필요 상태를 반환한다. ArrivalService의 도착 보고와 ARRIVED·감시 종료를 원자 연결한다. 실제 개인 탑승/환승 정답은 별도 수집 동의 없이는 생성하지 않는다.

## 11. 인계·검증·삭제 조건

- FE 전달: 공통 v2 schema/fixture, 최신 조회·재평가·수락·출발·위치/도착 계약, 상태별 버튼 허용조건, 오류 코드와 nullable 의미.
- DATA 전달: 필요한 scope·기간·우선순위 demand, consumer 필드/단위/namespace, 미매핑 샘플, source별 freshness 요구. 사용자 이름/일정 제목을 demand에 넣지 않는다.
- DATA에서 수령: source 확보 현황, raw/normalized fixture, artifact SHA/coverage, Redis·Kafka 계약과 지연/TTL, 실제 정답 및 누락 보고.
- 실행 코드 검증 시 Backend는 저장소 `pom.xml`과 Maven 래퍼 기준으로 관련 테스트를 실행한다. ML은 `ml-serving`의 pytest와 bundle 검증을 사용한다. 문서의 예전 Gradle 표현을 실행 기준으로 사용하지 않는다.
- 중복 solver/Client 역산 제거는 호출부·기존 추천 조회 호환·v2 연동 검증 후 진행한다. 기존 migration/이력/미추적 환승 파일을 삭제하지 않는다.
- PR에는 source 확보 상태, 변경 파일/메서드, 단위·공식, 정상/실패 fixture, 실행 결과, 운영 제한, 다음 파트 전달물을 적는다.
