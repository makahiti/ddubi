# 버스 역사 평균 기반 계산 fallback

## 연결 범위와 안전 경계
- 공통 `JourneyEvaluationService.compare`에 연결하여 경로 비교와 조건부 출발 일정 평가가 같은 계산을 사용한다.
- 검증된 ML BUS step 계산이 실패할 때만 해당 BUS step 전체를 역사 평균으로 다시 계산한다. 일부 ML edge와 일부 역사 edge를 섞지 않는다.
- 역사 edge 하나라도 부족하면 그 BUS step 전체를 기존 API 시간으로 복귀시킨다.
- 기존 `baseline_serving`과 활성 baseline 포인터를 변경하지 않는다. `productionReady=false`, 역사자료 자체의 `realtimeApplied=false`를 유지한다.
- 평균은 확률·백분위·실시간 예측이 아니다. FE는 과거 평균 기반 추정으로 표시한다.

## 전체 적재 및 운영 설정
- `ddubi.bus-reference.data-version`: 공개 참고 조회와 동일한 불변 버전.
- `ddubi.bus-reference.fallback.enabled=false`: 기본 비활성. 전체 적재·합계 검증 후 운영자가 명시적으로 켠다.
- `expected-rows=0`, `expected-chunks=0`: export 전체 합계와 동일하게 설정해야 한다. 0이면 적용 불가.
- 위 2개와 아래 정책은 모두 `ddubi.bus-reference.fallback.*` 하위 속성이다.
- `max-age-days=90`, `min-samples=3`, `min-dates=3`, `max-future-days=7`, `sample-kind=HOURLY_SOURCE_MEAN`.
- 표본 종류는 실행 설정으로 하나만 선택한다. ACTUAL_TRAVERSAL_MEAN도 지원하지만 한 계산 안에서 종류를 섞지 않는다.
- DB 실제 행·청크 수를 앱 준비 시 검증한다. 성공은 해당 프로세스 수명 동안 캐시하며, 실패는 최소 60초 후 재확인한다. 추천마다 1천만 행 COUNT를 반복하지 않는다.
- 검증 후 해당 dataVersion에 새 청크를 추가하지 않는다. 수정·추가는 새 dataVersion과 서비스 재시작을 사용한다. 내부 업로드 권한은 신뢰된 배포 작업에만 부여한다.

## 초기 정책의 자료 근거
- 2026-09-17 KST 기준 로컬 유효자료: 월간 6/1~7/3, 주간 8/17~23, 실제 이력 8/30~9/1.
- 월간 요일별 마지막 관측은 현재 약 76~82일 전이므로 최대 90일은 이 자료를 포함하는 초기 정책이다. 신선한 실시간 자료라는 뜻은 아니다.
- 요일·시간대별 관측 날짜 최소 3일을 요구한다. 주간 한 묶음은 같은 요일당 1일이므로 단독으로 적용되지 않을 수 있다. 이력 3일도 같은 요일에 3일을 제공하지 않는다.
- 실제 관측이면 sampleCount는 운행 수, 시간대 원천 평균이면 원천 날짜별 평균값 수다. 서로 바꾸어 해석하지 않는다.
- 7일 이후 출발 탐색은 API fallback이다. 원본이 7일을 예측한다는 뜻이 아니라 역사 평균 외삽을 제한하는 운영 정책이다.

## 정확키·기간·수치 가드
- 현재 DB 활성 마스터와 메모리 마스터가 요청 mapping.masterDataVersion에 일치해야 한다. 조회 후에도 재확인한다.
- 서울 canonical BUS ID, route/from/to ID, 인접 순서, edge 순서와 연결성을 확인한다.
- 각 edge 진입시각을 KST로 변환하여 일요일=1 요일과 시각을 조회한다. 앞 edge의 실제 적용 초를 다음 진입시각에 반영한다.
- 잔여시간으로 인해 진입 상·하한이 다른 시간대/날짜에 걸리면 적용하지 않는다.
- 원천 관측 마지막 날짜는 평가일보다 이전이고 90일 이내여야 한다. 원천 가용시각을 현재시각으로 만들어 채우지 않는다.
- 같은 sampleKind의 충분한 후보 중 관측 종료일이 가장 최근인 한 행만 선택한다. 최신일에 서로 다른 행이 여러 개면 모호 상태로 거절한다. 다른 기간이나 출처의 평균을 합산하지 않는다.
- 구간 평균은 기존 적재 계약을 만족하고 0초 초과·3,600초 이하여야 한다. RIDE 합은 86,400초 이하. 각 edge 초는 올림한다.
- 한 평가 세션의 정확키 조회 캐시는 최대 256개이며 기존 여정의 시간 예산도 공유한다.

## 시간 분해와 한계
- 현재 KakaoRouteMapper는 BUS `step.properties.time`을 RouteSegment 시간으로, 전체 `totalTime`과 step 합 차이를 잔여시간으로 저장한다.
- RouteEvidenceAssembler는 BUS를 RIDE로 모델링하고 잔여시간을 끝에 한 번만 보존한다. step 합이 totalTime을 넘으면 전체 API fallback으로 변경한다.
- 역사 평균 교체는 기존 ML 교체와 같은 RIDE 경계다. 별도 실시간 WAIT는 기존 `ddubi.bus-waiting.decomposition-verified`와 도착정보 검증이 된 경우만 추가한다.
- 외부 `step.time`에 정차·대기 시간이 얼마나 포함되는지는 코드만으로 확정할 수 없다. 이 때문에 전체 결과를 운영 검증 완료 또는 도착 보장으로 승격하지 않는다. 대기 부족 사유도 보존한다.

## 응답
- 적용 step source와 V2 segment durationSource: `BUS_HISTORICAL_REFERENCE`.
- Candidate quality: `HISTORICAL_REFERENCE` 또는 `MIXED_HISTORICAL_REFERENCE`.
- legacy 저장 persistence.source에도 해당 Candidate quality가 전달된다.
- 이유: `step-N:HISTORICAL_REFERENCE_APPLIED`, `...WEATHER_UNKNOWN`, `...VERSION:<dataVersion>`, `...SAMPLE_KIND:<kind>`.
- 같은 step에 MIN_SAMPLE_COUNT, MIN_OBSERVED_DATES, OLDEST_OBSERVATION_END, NEWEST_OBSERVATION_END, ENTRY_UNCERTAINTY_SECONDS를 함께 기록한다. 표본 수는 구간별 최소값이며 서로 다른 구간의 표본을 합산하지 않는다.
- 실패 이유는 `HISTORICAL_REFERENCE_` 접두어의 DATASET_INCOMPLETE, MASTER_VERSION_MISMATCH, ENTRY_HOUR_UNCERTAIN, SAMPLES_OR_FRESHNESS_INSUFFICIENT 등이며 API fallback을 유지한다.
- 자료 적용이 productionReady나 ML 적용 수를 올리지 않는다. 유효한 실시간 WAIT를 별도로 실제 적용한 경우에만 기존 실시간 표시가 생길 수 있다.

## gate 관측 (2026-09-17 추가)
- 설정 활성화, gate 통과, 실제 적용은 서로 다른 상태다. gate는 기동 시 `warmGate`와 첫 평가에서만 전체 COUNT를 실행하고 결과를 프로세스 수명 동안 보존한다.
- `BusHistoricalReferenceService.gateStatus()`는 설정(enabled, dataVersion, expectedRows/Chunks, 정책)과 마지막 gate 결과만 반환한다. `gateState`는 NOT_EVALUATED/PASSED/FAILED, `gateReason`은 TOTALS_MISMATCH, VERSION_MISMATCH, `STATUS_UNAVAILABLE:<예외 클래스명>`이며 `observedRows/observedChunks`, `gateCheckedAt`, `policyValid`를 함께 준다. 비밀값·경로·예외 메시지는 담지 않는다. 이 조회는 COUNT를 다시 실행하지 않는다.
- 노출 경로: 인증 사용자용 `GET /api/v1/routes/data-status`의 `busHistoricalFallback`, 내부 키용 `GET /internal/bus-references/{version}/gate`(`requestedVersion`, `matchesConfiguredVersion` 포함). 기동 로그에는 `버스 과거 평균 gate 통과/불일치/확인 실패` 한 줄이 남는다.
- gate PASSED는 적재 수량이 설정과 같다는 뜻이며 어떤 경로에 실제 적용됐다는 뜻이 아니다. 실제 적용은 평가 결과의 `BUS_HISTORICAL_REFERENCE` source와 `HISTORICAL_REFERENCE_*` 사유로만 확인한다.

## 진단 구분과 예산 분리 (2026-09-18 추가, 로컬 검증만 완료)

응답 `quality.fallbackReasons`(후보 reason)에서 ML 실패와 과거 자료 부족을 같은 코드로 합치지 않는다.

| 상황 | step 코드 |
|---|---|
| ML 적용(운영 계약) | `step-N:ML_APPLIED` + `ML_MODEL_VERSION:<v>` |
| ML 시험 적용(테스트 환경, 이력 있는 구간만) | `step-N:ML_TEST_APPLIED` |
| ML 기록만(SHADOW) | `step-N:ML_SHADOW_RECORDED`, `step-N:ML_NOT_APPLIED:SHADOW_MODE` |
| ML 미적용 | `step-N:ML_NOT_APPLIED:<ML_BUDGET_EXHAUSTED|ML_TIMEOUT|ML_HTTP_ERROR|ML_INVALID_RESPONSE|ML_PRODUCTION_CONTRACT_NOT_MET|…>` |
| 과거 평균 적용 | `step-N:HISTORICAL_REFERENCE_APPLIED` (+ 버전·표본 기록 코드) |
| 과거 평균 미적용 | `step-N:HISTORICAL_REFERENCE_<원인>` 와 함께 `step-N:HISTORICAL_REFERENCE_NOT_APPLIED:<원인>` (예: `EXACT_KEY_UNAVAILABLE`=자료 없음, `BUDGET_EXHAUSTED`=시간 부족) |
| 경로 API 원본 값 사용 | `step-N:API_FALLBACK` |

- `ML_BUDGET_EXHAUSTED`·`HISTORICAL_REFERENCE_BUDGET_EXHAUSTED`(시간 부족)와 `HISTORICAL_REFERENCE_EXACT_KEY_UNAVAILABLE`(자료 없음)은 회귀 테스트로 구분을 고정했다(`BusHistoricalReferenceServiceTest.missingReferenceDataAndExhaustedBudgetAreDistinctDiagnostics`, `MlApplyModeTest.exhaustedMlBudgetIsReportedAsBudgetNotAsMissingData…`).
- 예산: 트리거별 세션 예산(첫 계산 30초 / 자동 갱신 15초 / 수동 10초, `DDUBI_RECOMMENDATION_BUDGET_*_MILLIS`) 중 ML 비율(`DDUBI_RECOMMENDATION_ML_BUDGET_SHARE`, 기본 0.6)을 넘긴 뒤에는 ML을 더 부르지 않고 과거 평균 계산에 시간을 남긴다. ML은 평가 시작 시 한 배치로 선조회해 33개 후보를 순차 호출하던 구조를 없앴다(뒤 후보가 예산을 잃지 않음).
- 앱: 일반 화면은 번역 문구만("계산 시간이 부족해 ML 예측 미적용" ≠ "과거 자료 적용 조건이 맞지 않아 기본 경로시간 유지"), 개발자 진단(디버그 빌드 또는 더보기 > 개발자 옵션)에서만 위 원본 코드·모델 버전·자료 버전을 그대로 보여준다.
