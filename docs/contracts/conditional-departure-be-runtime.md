# 조건부 출발 BE 구현·연동 안내

기준 작업: Jira `S15P21E202-74`, `feature/backend/S15P21E202-74-conditional-departure` → `dt/dev`, MR !146.
기준 코드는 `53c2c95`, 최초 구현은 `7c8875d`이며 이후 같은 MR의 리뷰 수정 커밋을 함께 적용한다.
요구사항 기준은 `docs/MVP_plan/conditional-departure-{be-ml,integration,team-guide}.md`다. 해당 문서 안의 이전 `docs/handoffs` 링크 대신 실제 `MVP_plan` 파일을 사용한다.

## 실행되는 흐름

1. 신규 일정 HTTP 요청은 사용자 P와 D를 모두 요구하고 `P<D`, 미래 D, 서울 좌표를 검사한다. 기존 P=null 행은 자동 역산하지 않고 `DEPARTURE_REQUIRED`다.
2. `ScheduleService`가 입력 원본·현재 계획·revision을 저장한다. 입력 변경과 기존 outbox 취소는 같은 트랜잭션이다. commit 이후 1개 worker/대기열 20개의 wake를 요청한다. 큐 포화·재시작은 DB due 조회로 복구한다.
3. `ScheduleEvaluationStore.claim`이 일정 행→평가 행 순서로 잠그고 120초 lease와 입력/사건 revision을 고정한다. 외부 I/O는 트랜잭션 밖에서 한다.
4. 기존 카카오 대중교통 client와 mapper가 후보를 만들고 BUS/SUBWAY를 마스터에 매핑한다. 기존 WALK, 순번, 원본 provider 총시간과 미분해 비용을 보존한다.
5. `WalkingEnrichmentService`가 선택/상위 후보부터 검증된 접근·이탈 WALK만 교체한다. 중간 환승 WALK는 보존한다. raw 후보 캐시와 보강된 계산 후보를 분리하여 재평가마다 시간을 중복 가산하지 않는다.
6. `JourneyEvaluationService`가 고정 증거와 평가 session으로 P의 모든 후보를 평가한다. `ConditionalDeparturePolicy`가 `ETA<D`면 유지하고, 그 외에는 `T=D-600초`를 만든다. ETA가 없으면 0초로 보정하지 않는다.
7. `ConditionalDepartureEvaluator`가 `[asOf,min(P,T)]`의 시간표/원천/정각 경계와 제한 격자를 늦은 순으로 재평가한다. 출발마다 대기·차량·환승·ML hourSlot을 다시 계산한다. `ETA<=T`가 성공이며, 예산 소진은 `SEARCH_INCOMPLETE`다. 연속시간 전역 최적을 보장하지 않는다.
8. 입력 revision, 사건 revision, lease token/만료, 이동 상태를 complete 직전에 다시 검사한다. route/segment/edge, recommendation, 결과 JSON과 outbox를 원자 저장한다. 사용자의 선택 경로를 자동으로 제안 경로로 바꾸지 않는다.
9. outbox는 claim→현재 revision/lease 재검사→notificationId 영속화→트랜잭션 밖 FCM→ack 순서다. 실패는 최대 5회 backoff, stale/출발/취소는 CANCELED다. crash 후 같은 notificationId로 재전송될 수 있으므로 FE도 해당 ID로 중복 제거한다.

## FE가 사용할 요청·응답

| API | 의미/주의 |
|---|---|
| `POST /api/v1/routes/evaluate` | 등록 전 조회. 아래 request 예제를 사용한다. schedule/route/recommendation/outbox 행을 만들지 않는다. 인증 사용자당 고정 분 창 6회, 초과 429, Redis 제한 확인 실패 503 |
| `POST /api/v1/schedules/{id}/recommendations` | 저장된 P/D로 평가. 기본 v2, `?version=1`은 이전 Outcome 형식 |
| `GET /api/v1/schedules/{id}/recommendations/latest` | 기본 v2. READY/STALE는 전체 평가 DTO. STOPPED는 마지막 평가가 있으면 `frozen=true`와 출발 시각 재계산 결과를 함께 반환하고, 없으면 가벼운 상태 응답 |
| `POST /api/v1/schedules/{id}/recommendations/accept` | `{"evaluationId":"서버가 준 값"}`. PREVIEW는 409, 출발시각/경로를 Client에서 보내 적용하지 않음 |
| `POST /api/v1/schedules/{id}/departure` | 최초 actualDepartAt 보존, IN_PROGRESS 전환, 출발 전 감시 중단. 선택된 저장 경로 하나를 실제 출발시각으로 재평가해 ETA를 평가 JSON에 저장. 중복 요청은 최초 시각과 결과를 유지 |

출발 후 `latest`의 `actualDepartAt`은 실제 출발시각이다. `departureEstimate.status=READY`이면 최상위 `estimatedArrivalAt`·`durationSeconds`와 선택 후보의 같은 값은 그 시각 기준 재계산값이다. 버스·지하철 대기와 환승을 다시 평가하므로 출발 전 소요시간을 실제 출발시각에 단순 가산하지 않는다. `departureEstimate`가 없거나 `UNAVAILABLE`이면 최상위 ETA·소요시간은 `null`이며, 이전 제안의 도착시각을 현재 예상으로 표시하지 않는다. 날짜 없는 시각 문자열이 아니라 전체 KST 업무시각으로 전달한다. 출발 사실은 외부 계산 전에 저장되므로 재계산 실패가 출발 기록을 되돌리지 않는다.

출발 후 사용자 안내(`/recommendations/latest/guide`)도 재계산 결과가 있으면 실제 출발·도착 시각으로 서버 문장을 만든다. 출발 전 평가 ID로 캐시된 GMS 문장을 재사용하거나 GMS에 시간을 다시 계산시키지 않는다. 새 구간별 시간이 없으므로 출발 전 구간 시간도 새 안내에 복제하지 않는다.

일정 응답에 `userInputDepartAt`, `planSource`, `actualDepartAt`, `journeyState`, `inputRevision`이 추가됐다. 기존 계획만 있는 행의 source는 `LEGACY_UNKNOWN`으로 남긴다.

기계 계약은 `schedule-evaluation-v2.schema.json`, 예제는 `examples/conditional-departure/`다. success 예제는 **ApiResponse의 data 부분**이다. HTTP에서는 `success/data/timestamp/requestId` envelope를 사용한다. 오류 envelope는 `errors.json`을 참고한다. 고정 날짜 합성 예제이므로 실제 호출 때는 미래 P/D로 바꾼다.

- KEEP: `targetArriveAt=D`, buffer 0, `meetsAdjustedTarget=null`이다. 내부 순수 정책의 target=null과 외부 표시 계약을 혼동하지 않는다.
- 조정: `targetArriveAt=D-600초`, buffer 600이다. Client가 ETA나 출발시각에 10분을 다시 가산/차감하면 안 된다.
- 도보 역할: `ACCESS_WALK`, `TRANSFER_WALK`, `EGRESS_WALK`, `RIDE`. 대중교통 승하차가 없는 WALK는 `ACCESS_WALK`로 정규화하고 `WALK_NO_TRANSIT_ENDPOINT`로 별도 호출을 생략한다.
- 도보 적용 여부는 호출 성공뿐 아니라 최종 타임라인에 교체 시간이 적용됐는지 확인한다. `costCoverage`는 비용 분해 범위이지 예측 정확도 보장이 아니다.
- v1 저장 JSON은 v1로 계속 읽는다. conditional 결과가 없는 구형 JSON을 v2 READY로 가장하지 않고 v2에서는 PENDING으로 반환한다.
- `quality.preview=true`, `productionEligible=false`가 현재 기본 실행 결과다. 미래 production 승인을 이 MR이 대신하지 않는다.

## 입력 출처와 실제 소비 경계

데이터가 이미 확보됐다고 가정하지 않는다. 아래 각 항목은 수집/적재·버전 검증 후에만 적용한다.

| 입력 | 출처·전달 위치 | 소비 필드/단위 및 미확보 동작 |
|---|---|---|
| P/D·좌표 | 사용자 일정 요청 | P/D는 시각, 좌표는 WGS84 경도/위도. 읽기 평가 API는 오프셋 있는 ISO 시각. 기존 일정 API의 KST LocalDateTime 계약 유지 |
| 대중교통 후보 | 기존 KakaoPublicTransitClient → KakaoRouteMapper | BUS/SUBWAY/WALK, stop names, path geometry, 초 단위 duration, 총시간·잔여시간. provider stop에 없는 x/y를 가정하지 않음 |
| 정적 canonical 매핑 | 기존 서울/수도권 마스터 resolver | `status`, `masterDataVersion`, `mappingPolicyVersion`, `candidateCount`, 방향·순번·인접 edge. 이름이나 provider ID를 canonical ID로 새로 만들지 않음 |
| 구간 상태 | DT → Redis `segment:status:{segmentId}` | `segmentId/status/computedAt/validUntil/source/causes[]`. 공식 스키마 `events/segment-status.schema.json`. stale/결측/잘못된 ID는 이유를 남기고 미적용. delaySeconds를 ETA에 단순 더하지 않음 |
| 버스 도착 | 서울 버스 도착 원천 → DT 정규화 → `transit:latest:bus-arrival:{providerRouteId}:{providerStopId}` | `occurredAt/collectedAt/validUntil/eventId`, `observation.sourceGeneratedAt`, `arrivalTimeBasis=SEOUL_EXPS_SECONDS_V1`, 1·2번 차량 ID/도착초/만차/종점/우회. mkTm+exps를 사용. 임의의 3번째 차량이나 배차 H를 만들지 않음 |
| 지하철 운행표/실시간 | 검증된 `SubwayTimetable` artifact와 DT subway arrival/position Redis 키 | canonical 역·호선과 EXACT 실시간 train crosswalk, serviceDate, 방향, 정차 순서, 운행표 version/validity, 발생/수집/만료 시각. 일치하지 않으면 시간표/기존 API fallback과 이유 표시 |
| 도로 사건 | DT road mapping artifact + `road:incidents:{namespace}:{linkId}` hash | artifact SHA256/masterVersion/namespace/direction/occurrence/적용 기간, 사건 eventId/collectedAt/startsAt/expectedEndsAt. raw 사건은 잠재 영향으로 표시. FULL/EXACT 증거 없는 사건을 임의 BLOCKED/지연초로 승격하지 않음 |
| 버스 ML | 기존 `/v1/bus/predictions:batch`, ml-serving runtime | provider namespace/노선·정류장 pair/순번/service_date/hour_slot. 모델 version, 지원 날짜, computed_at, mean_seconds, missing/history/data_insufficient. 부분 edge 실패는 전체 step API fallback. 모델 버전 혼합은 평가 전체 무효화 |
| 도보 endpoint 좌표 | **DT가 별도로 제공해야 하는** 공식 정류장/역 출입구 artifact | 아래 MasterPoint 필드. EXACT 매핑과 path endpoint, 방향·연속 순번이 모두 검증돼야 사용. 역 중심은 출입구로 바꾸지 않음 |
| 별도 도보시간 | 카카오맵 공식 `GET /v2/routing/walk` | `KakaoWalkingRouteClient`가 `status=OK`, 정수 `totalTime`(초), `totalDistance`(미터)를 검증. 비활성/403/429/timeout/빈 응답은 기존 WALK 유지 |

좌표 artifact의 합성 형식:

```json
[
  {"nodeId":"2","masterVersion":"master","coordinate":{"longitude":127.001,"latitude":37.501},"meaning":"BUS_STOP","source":"OFFICIAL_FIXTURE"}
]
```

`nodeId`는 정적 canonical node ID이며 실시간 stop ID가 아니다. `meaning`은 BUS_STOP 또는 STATION_ENTRANCE만 보강 가능하다. STATION_CENTER는 fallback이다. 실제 데이터에는 공식 출처 식별자와 활성 마스터 version을 제공해야 한다. 같은 node/version에 여러 좌표가 있으면 임의 선택하지 않는다.

DT는 `redis-keys.yaml`, `events/*.schema.json`, 기존 transit/time-table 계약과 동일한 키·단위·ID를 제공한다. crosswalk 및 feature snapshot version이 응답에 없는 현 ML 경계는 `missingInputs`로 공개한다. 기존 baseline_serving을 읽지 않은 평가가 baseline version을 사용했다고 주장하지 않는다.

## 설정과 운영 제한

| 설정 | 기본값 | 의미 |
|---|---|---|
| `ddubi.recommendation.search-max-evaluations` | 256 | 후보×출발시각 평가 수 제한 |
| `ddubi.recommendation.search-grid-seconds` | 60 | 제한 격자 초 |
| `ddubi.recommendation.search-max-millis` | 5000 | 탐색·ML session 예산. 진행 중 HTTP 한 건은 설정 timeout까지 더 걸릴 수 있음 |
| `ddubi.recommendation.ml-preview-timeout-ms` | 5000 | ML HTTP 한 건 상한, 모델 cache는 평가 session 안에서만 유지 |
| `ddubi.recommendation.commit-wakeup-enabled` | true | commit 후 빠른 평가. test profile은 false |
| `ddubi.recommendation.readonly-per-minute` | 6 | 사용자별 조회 API 비용 제한 |
| `ddubi.recommendation.auto-notify-enabled` | false | 자동 알림 |
| `ddubi.recommendation.shadow-preview-notify-enabled` | false | 검증용 알림 허용. 켜면 `[검증용]` 본문 |
| `ddubi.walking.enabled` | false | 공식 카카오맵 도보 사용. 키/좌표/비용 검증까지 통과해야 적용 |
| `ddubi.walking.endpoint-max-meters` | 0 | 0이면 검증 비활성. corpus로 합의한 값만 설정 |
| `ddubi.walking.master-coordinate-path` | 빈 값 | DT transit-coordinate-v1 객체 또는 이전 MasterPoint 배열. 32 MiB 상한 |
| `ddubi.bus-waiting.decomposition-verified` | false | 이동/대기 비용 분해 검증 후 켬 |
| `ddubi.subway-timing.decomposition-verified` | false | 지하철 이동/대기 비용 분해 검증 후 켬 |

고정 ML 입력은 session 안에서 중복 호출하지 않는다. 현재 Python 계약에 feature snapshot version이 없어 평가를 넘어서는 공유 ML cache는 추가하지 않았다. 다음 edge 진입 시간이 앞 edge 예측에 의존하므로 기존 batch endpoint에 한 edge씩 순차 호출한다. 독립 edge 다건 최적화와 feature version 고정은 후속 계약 범위다.

평가 결과 기본 유효시간은 asOf+60초이며 구간 상태/실제 도보 응답/다음 평가가 더 빨리 만료되면 그 시각으로 줄인다. 결과 숫자는 PREVIEW이며 차량 탑승 가능성·실시간 품질까지 운영 승인됐다는 의미가 아니다.

## DB 적용과 검증

- V7: 일정 원본 계획/출처/이동 상태/revision, 평가 validity/accepted ID, 독립 outbox 추가. 기존 V1~V6 수정 없음.
- V8: `failure_count`, `failed_input_revision`, `retry_exhausted`. 외부 실패는 같은 입력에서 최대 5회, 30/60/120/240/480초 대기. 사건/입력 변경 시 예산 재개. stale 완료는 즉시 재평가 대상이며 외부 장애 횟수로 세지 않음.
- 이전 스키마에 V7→V8을 순서대로 적용한다. 적용 뒤 파일을 수정하거나 운영 데이터를 초기화하는 rollback을 사용하지 않는다.
- MySQL 8·Redis 7 Testcontainers에서 실제 migration, revision 변경 중 완료 거부, lease 만료, event revision, 출발 멱등성, outbox 실패/재시도/중복/취소를 검증한다.
- HTTP 테스트는 인증, 잘못된 P/D, 429/503, nullable 필드 보존과 조회 전용 DB 무변경을 확인한다.
- 단위 테스트는 D-1초/D/T 경계, 불연속 시간표, 밀집 경계와 격자 순서, 모델 교체, 도보 방향/좌표/비용 중복/만료를 검증한다.

```text
cd backend
mvnw.cmd test

cd ../ml-serving
python -m pytest -q

cd ..
python docs/contracts/validate-conditional-departure.py
```

JSON 검증은 Python `jsonschema==4.26.0`이 필요하다. Java `ScheduleEvaluationV2ContractTest`가 `backend/target/conditional-departure-fixtures/`에 생성한 DTO와 저장 예제를 비교한다. 실제 HTTP 응답도 같은 schema로 검사한다.

2026-09-16 로컬 검증 결과:

| 검증 | 결과 |
|---|---|
| Backend 전체 `mvnw.cmd -q test` | 총 470개, 성공 467개, skipped 3개, 실패/오류 0개. Java 17·MySQL 8·Redis 7 사용 |
| ML `pytest -q` | 성공 45개, skipped 1개. GPU 모델·참조·golden 자료 미설치로 해당 실모델 검사 생략 |
| JSON Schema + Java DTO 비교 | 고정 응답 13개와 실제 HTTP 산출물 1개 통과 |
| 기존 계약 문법 | contracts YAML 및 events JSON 파싱 통과 |
| Git patch | diff whitespace 검사 통과 |

## 아직 별도 확인이 필요한 항목과 담당

| 담당 | 필요한 작업 | 현재 동작 |
|---|---|---|
| BE/운영 담당 | 공식 REST API 키의 실제 호출 권한/쿼터, endpoint 거리 corpus 확인 | adapter 구현 완료, 기본 OFF와 검증된 endpoint만 사용 |
| DT | 공식 endpoint 좌표·버전·의미, EXACT crosswalk, 검증된 운행표/실시간/도로 artifact 제공 | 결측·stale·모호한 매핑은 미적용/품질 저하 |
| ML/DT | 지원 날짜·학습 ID·feature snapshot manifest, 승인 데이터와 운영 모델 승인 | PREVIEW 표시·저장, 수락 차단 |
| FE | 기본 v2와 상태/새 nullable 필드/알림 notificationId 소비, Client 자체 역산 제거 | 이 MR에서 Flutter 변경 없음 |
| PM·전 파트 | FE/BE/DT 동일 fixture의 화면 E2E, 실제 제공자 corpus, 후보 수·worker 수·외부 호출을 고정한 p95 부하 시험 | 로컬 합성/DB/HTTP 회귀 검증과 구분. 운영 성능·전체 팀 연동 완료로 간주하지 않음 |

실데이터가 필요한 `BusPreviewLiveModelTest`, `CapturedTransitFlowIntegrationTest`, `MappingCorpusReplayTest`는 해당 URL/corpus 설정 없이 자동 활성화하지 않는다. CI 통과·리뷰 승인 후 MR을 병합한다.

## 2026-09-17 MR 재검토와 수정

MR !146 (`d9a2bac` 기준)의 GitLab 병합 검사는 draft 외 충돌이 없었다. 대상은 현재 `dt/dev`이며 임의로 바꾸지 않았다. !147(`37f2160f`)의 FE 파서, !148의 DT exporter/schema/fixture, !141의 알림 변경 파일을 대조했다. FE/DT 제품 코드는 수정하지 않았다. !141은 기존 알림 command builder를 그대로 사용할 수 있다.

### 공개 계약

- 실제 적용된 도보는 `KAKAO_WALKING_API`, 원본 step은 `PUBLIC_TRANSIT_STEP`이다. 전체시간 fallback은 `PROVIDER_TOTAL_FALLBACK`이고 개별 구간 시간은 null이다. ML/지하철 시간표 적용 시 `ML_PREVIEW`/`TIMETABLE`과 실제 구간 시간을 반환한다.
- `endpointEvidence`는 nullable 객체다. 검증 여부, 공식 출처, master/mapping/policy 버전, 비교 거리, 좌표 의미, 판단과 미검증 사유를 보존한다. API 403이어도 좌표 검증 성공 근거를 문자열 오류로 덮지 않는다.
- `WALK_ONLY`는 공개 enum에서 제거했다. 과거 저장 JSON의 이 enum·출처명·문자열 endpointEvidence도 읽되 객체로 변환하고 재검증한 것으로 표시하지 않는다.
- `causes`는 `{eventId, source, segmentIds, effect, confirmed}`다. `BLOCKED`는 확인된 통제, `POTENTIAL_IMPACT`는 잠재 영향이다. 같은 사건의 여러 구간을 묶고 중복 시간 가산은 하지 않는다. 최신 FE !147은 eventId/segmentIds/effect/confirmed를 읽는다. reasonCode/description/revision은 BE가 확인한 원천이 없으므로 임의 생성하지 않는다.
- `examples/conditional-departure/details/`의 성공·403 fallback·provider total·confirmed cause 예제는 실제 Java DTO에서 생성한다. JSON Schema는 과거 문자열 evidence와 잘못된 enum을 거부한다.
- FCM payload에 `evaluationId`, `decision`을 추가했다. 새 평가 완료 시 이전 대기/전송 lease를 취소하고, 미전송인 동일 의미 알림만 최신 평가로 교체한다. 발송 완료와 소진된 재시도는 다시 열지 않는다.

### 공식 도보 설정

공식 근거: https://developers.kakao.com/docs/ko/kakaomap/rest-api#walking (2026-09-17 확인).

`GET https://dapi.kakao.com/v2/routing/walk`에 `start_x/start_y/end_x/end_y`, `input_coord=output_coord=WGS84`, `route_mode=SHORTEST`를 전송한다. 기존 `KAKAO_REST_API_KEY`를 KakaoAK 헤더로 재사용한다. 과거 계획서의 affiliate endpoint와 service/priority/default_speed는 사용하지 않는다.

추가 설정은 `ddubi.walking.timeout-millis=1500`(연결/읽기 각각), `cache-seconds=60`(외부 응답을 최초 평가 안에서 검증하는 TTL, provider 보장 아님)이다. `DDUBI_WALKING_ENABLED`, `DDUBI_WALKING_ENDPOINT_MAX_METERS`, `DDUBI_WALKING_MASTER_COORDINATE_PATH` 환경변수로 켠다. 승인되지 않은 거리 임계값을 코드에서 자동 선택하지 않는다. 좌표 반올림 없는 session cache로 최초 평가의 동일 요청을 재사용하며, 후보 수에 따른 호출 횟수 상한은 두지 않는다. 성공한 도보시간과 보강 후보는 `schedule_evaluation.candidates_json`에 저장하고 일정의 `arriveBy`까지 재사용한다. 일정 입력 revision이 같으면 자동·수동 재평가에서 도보 API를 다시 호출하지 않는다. 외부 429·timeout·오류는 기존 WALK fallback 사유로 보존한다.

403·429·timeout·5xx·비정상 status·빈/깨진 응답은 기존 WALK로 돌아간다. 429는 Retry-After(초/HTTP date)까지 이후 호출을 생략하고 즉시 재시도하지 않는다. `ddubi.walking.requests{result}`와 actuator `kakaoWalkingRouteClient` health 상세에 상태를 제공한다. 선택적 도보 장애가 전체 대중교통 readiness를 내리지 않는다. 실제 유료 API 호출과 운영 활성화는 이 변경의 테스트에 포함하지 않았다.

### DT 소비 결과와 필요한 보완

- !148 `transit-coordinate-v1` 객체를 직접 읽는다. schema/좌표계, masterVersion, EXACT, 노선/namespace, 방향, sourceRef의 공식 sequence, 유효기간, path 거리와 유일성을 검사한다.
- DT `BUS_STOP:{namespace}:{id}`와 BE canonical 구성요소는 기존 CanonicalSegmentIdFactory로 명시적으로 변환한다. `SUBWAY_STATION:{id}`는 정적 역 ID와 확인된 providerLineId로 대조한다. 이름 기반 연결은 하지 않는다.
- SEQUENCE_FORWARD는 원천 상·하행 코드가 아니므로 단일 검증 버스 대안의 원본 stop.direction을 확인한다. 방향/순번이 없거나 중심점뿐이면 호출하지 않는다. DT에는 이 필드와 역 출입구 좌표 보강이 필요하다.
- !148의 실제 Spark bus_stream_sink는 providerIds/observation/occurredAt을 만든다. 제공된 redis-bus-arrival.json 예제는 평면 구조이고 stopOrder도 없어 현재 안전한 소비를 통과하지 못한다. 해당 원본 fixture를 Backend 테스트에 고정하고 `BUS_WAIT_IDENTITY_OR_UNIT_UNVERIFIED`로 거부함을 검증했다. DT는 실제 sink와 일치하는 stopOrder 포함 fixture를 제공해야 한다.
- copied fixture는 `backend/src/test/resources/conditional-departure-dt/`에 원본 그대로 보존했다. 운영 데이터가 아니다.

### BE/ML 작업서 재점검

| 범위 | 결과 / 확인 근거 |
|---|---|
| P/D 정책, 탐색 | 기존 순수 정책·불연속 출발 재평가 유지. 초기 평가가 예산을 이미 초과한 경우에도 SEARCH_INCOMPLETE 보존 |
| 공통 여정 계산 | JourneyEvaluationService/세션/전체 BUS step fallback/모델 버전 혼합 차단 유지. 공개 구간 시간·출처를 실제 타임라인에 맞춤 |
| 도보 | 실제 공식 adapter, DT 좌표 소비, 객체 근거, 역할 정규화, 비용 교체, 세션 예산·재사용 구현 |
| 저장·수락·v1 | V7/V8 그대로 유지. 새 migration 불필요. input/event revision·lease·validity·PREVIEW 수락 차단과 구형 JSON 호환 유지 |
| 알림 | payload 평가 식별자 보완, 같은 revision의 구형 outbox 차단. 기본 자동알림 OFF 유지 |
| ML runtime | 고정 requirements로 45 passed/1 skipped. FastAPI 현재 runtime의 422 설명에 맞게 공개 OpenAPI 두 설명 수정 |
| 데이터·운영 승인 | ML/DT 미확보 버전을 추측하지 않음. 지원 기간/production_ready 검증과 PREVIEW 유지. 실제 키·DT 원천·PM 화면 E2E·부하 p95는 별도 운영 검증 |

기존 ML 배치 endpoint는 여정 edge 진입시각이 순차 의존하므로 session cache를 공유하며 순차 호출한다. 모델/feature version의 실제 운영 승인, independent edge 배치 최적화와 원천 corpus 확보는 완료로 주장하지 않는다.

### 2026-09-17 검증

- JDK 17에서 로컬 실행 가능한 BE 테스트 260개: 실패/오류 0, 조건부 skip 2개. Docker가 필요한 MySQL/Redis 통합 테스트는 GitLab backend-test에서 확인한다.
- 고정 개발 의존성으로 ML 테스트 45 passed, 1 skipped. 실제 모델 자료가 필요한 테스트는 활성화하지 않았다.
- 공개 응답 fixture 13개와 실제 Java DTO 상세 fixture 4개가 JSON Schema 검증을 통과했다. 잘못된 도보 enum·문자열 evidence의 거부도 확인했다.
- DT !148은 작업 중 dt/dev에 병합되었다. 최신 대상 브랜치를 반영하여 기존 MR !146에서 검증한다.

## 평가 유효성과 재평가 이력 보존 (2026-09-17)

- 제한 격자의 출발 후보는 최신 시각부터 평가한다. 예산 내에서 목표에 도착하는 첫 해를 찾으면 `ADVANCE_DEPARTURE` 또는 `DEPART_NOW`로 완료한다. 그보다 이른 후보를 메모리 제한으로 제거한 사실만으로 `SEARCH_INCOMPLETE`로 바꾸지 않는다. 실제 시간·호출 예산 초과나 해를 찾지 못한 잘린 탐색은 계속 미완료다.
- 버스 `Estimate`와 공통 `WaitPrediction`에 원천 `validUntil`을 보존한다. 지하철 `Estimate.validUntil`은 선택 열차의 시간표와 실제 적용한 도착·위치 증거 중 가장 이른 만료시각이다. 조회했지만 폐기한 도착정보는 시간표 fallback의 유효시간을 제한하지 않는다.
- 최초 평가에서 받은 대중교통 후보와 도보시간은 같은 일정 입력 revision 동안 고정한다. 후보의 fetchedAt 경과, 지각·통제 전환은 외부 경로 재조회 조건이 아니며 저장 후보들의 실시간 소요시간과 이용 가능성만 다시 비교한다. 출발지·목적지·일정 시각 변경으로 revision이 바뀌면 캐시를 폐기하고 새 후보를 한 번 조회한다.
- 최종 유효시간은 기존 구간 상태·일정 종료까지 보존한 도보·다음 평가시각 제한에 더해, 최초 비교와 제안 비교에서 `applied=true`인 대기·지하철 예측 만료시각도 넘지 않는다. 이 값은 응답, 평가 DB, outbox에서 공유한다. 구형 저장 JSON의 적용 예측에 만료시각이 없으면 해당 평가의 `asOf`로 만료시키며 기본 60초를 부여하지 않는다. 이는 저장 JSON의 추가 필드이며 공개 v2 DTO 필드 변경은 아니다.
- 일정 응답의 `causes`에는 실제 사용자 선택 후보의 영향도 포함한다. 선택 경로가 차단되어 다른 정상 후보를 제안해도 원인을 유지하고, 선택·원래 최선·제안 후보와 무관한 다른 후보의 사건은 섞지 않는다. 읽기 전용 평가에는 사용자 선택 후보를 임의로 만들지 않는다.
- 동일 dedup의 미발송 outbox가 새 평가로 교체되면, 재시도 준비 트랜잭션에서 기존 `notificationId`를 유지하며 알림 제목·본문·종류·추천 참조를 최신 명령과 맞춘다. 이미 `SENT`인 이력과 읽음 시각은 변경하지 않는다.
- DB 컬럼이나 마이그레이션은 추가하지 않는다. FE 파싱·표시 변경은 이 보완의 범위에 포함하지 않는다.
