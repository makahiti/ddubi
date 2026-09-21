# 버스 이력 독립 모델 v13 계약 및 검증 기록

## 상태와 범위

**실험 완료 / SHADOW 후보 / 운영 미적용**.

이 브랜치는 최신 `origin/develop`의 `355c3ce`에서 시작하여 `112f950`의 신규 독립 모듈만 파일 단위로 분리했다. 전체 커밋을 cherry-pick하지 않았다.
현재 서비스의 v12 배치 호출·적용 모드·결측 진단·BUS step 전체 fallback은 변경하지 않는다.
v13은 앱에서 import/초기화하지 않으며 `/v2` 엔드포인트, Backend 연결, OpenAPI 변경, 환경변수·마운트·배포 설정이 없다.
기존 실험 모델·평가·번들을 보존하며 `production_ready=false`를 유지한다.

GPU 0 학습은 LightGBM CUDA illegal memory access로 중단되었다. 같은 서버의 CPU 최대 16개로 `20260918-v13-static-cpu` 학습·평가·번들 생성을 완료했다.
과거 브랜치의 HTTP/Java 실험 검증은 현재 서비스 연동 완료를 의미하지 않으며 이번 분리 브랜치에는 포함하지 않는다.

이 변경의 대상은 `SEOUL_BUS` 인접 정류장 사이 **시간대별 평균 이동시간(초)**다.
버스 대기, 지하철 시간표/실시간 계산, 환승, 도보, 전체 경로의 실제 도착시간을 학습하지 않는다.
지하철 ML 모델 부재가 버스 모델의 과거 Parquet 조회를 발생시키는 것은 아니다.

## 입력과 데이터 출처

| 구분 | 위치 / 출처 | 사용하는 값 | 사용하지 않는 값 / 한계 |
|---|---|---|---|
| 기존 정규화 학습 표본 | GPU `/home/j-j15e202/data/normalized/20260908-v12/model_features/*.parquet` | `service_date`, `provider_route_id`, `provider_from_stop_id`, `provider_to_stop_id`, `from_stop_sequence`, `hour_slot`, `target_seconds` | 기존 lag·수요·좌표 컬럼은 읽지 않음. 기존 샘플링/정규화 산출물 재사용이며 원본 전량 학습이 아님 |
| 정답 원천 | 기존 v12의 서울 OA-21217 버스 구간 시간 자료 | `target_seconds`: 시간대별 평균 초 | 개별 차량 통행 실측과 섞지 않음. 원천 공개시각/과거 노선 변경을 새로 입증한 것은 아님 |
| 공식 현재 마스터 | `backend/transit-master/bus/metropolitan-bus-v6.json.gz`, 버전 `METRO-BUS-20260917-SEOUL-0902` | 서울 노선의 `routeId`, 정류장 `stopId`, `sequence`, `direction` | GBIS/인천은 v13 지원 대상 아님. 최신 마스터를 과거 시점에도 유효했다고 주장하지 않음 |
| 공휴일 | 학습 환경의 `holidays.KR` 결과를 `input_spec.json`에 날짜 목록으로 고정 | 해당 날짜의 공휴일 여부 | 서빙 중 외부 달력 조회 없음. 기술적 지원일 밖은 거절 |
| 요청 | Backend의 검증된 canonical BUS edge | 서울 provider ID, 노선 내 순번, 앞 구간 시간을 누적한 KST 진입 날짜/시각 | canonical 문자열을 잘라 provider ID를 만들지 않음 |

GPU에서 확인한 기존 표본은 1,476개 날짜 파일, 100,027,638행이다. 파일 범위는
2022-01-03~2026-08-23이다. 이 수치는 새 학습의 품질 필터 적용 전 파일 메타데이터 합계다.
최종 사용 행 수·제외 수·파일별 SHA는 새 `source_usage.json`으로 확정해야 한다.

문서에 언급된 server2 MySQL 10,020,903행과 주간/월간 후보 데이터는 이번 학습 입력에
추가하지 않았다. 확보·중복 제거·평균/실측 정답 의미가 검증되지 않은 데이터를
기존 정답에 합치지 않는다. 이 문서는 해당 서버 데이터까지 검증 완료했다는 뜻이 아니다.

## 새 특성 및 학습 방법

공통 변환은 `ml-serving/static_bus_features.py`다. 총 10개 특성을 순서대로 고정한다.

```text
provider_route_id, provider_from_stop_id, provider_to_stop_id,
from_stop_sequence, hour_slot, spark_dow, is_weekend,
month, day_of_year, is_public_holiday
```

ID vocab은 학습 구간에서만 생성한다. 미등록 ID는 평가 행렬에서 0으로 변환하여
일반화 오차를 측정할 수 있지만, 실제 서빙은 공식 현재 인접 조합과 학습 관측의 교집합만 허용한다.
좌표·직선거리·날씨·행사·lag·승하차·운행횟수는 모델 입력에 없다.
정류장 좌표를 빼는 것은 금지된 직선거리와 혼동해서가 아니라 과거 좌표 시점 대응을
검증하지 않은 첫 request-only 모델의 명시적 선택이다.

기존 v12의 입력 명세는 ID 3개 + 수치 32개다. 수치별 missing indicator까지 만든
실제 모델 행렬은 67열이다. 새 모델에서 유지하는 10개 외에 다음 25개를 학습/서빙 양쪽에서 제외한다.

```text
from_latitude, from_longitude, to_latitude, to_longitude, coordinate_matched,
history_recent_seconds, history_median_4obs, history_mean_4obs,
history_median_8obs, history_mean_8obs, history_std_8obs,
history_count_4obs, history_count_8obs, history_trend_seconds,
from_board_daily_hour_lag2month, from_alight_daily_hour_lag2month,
to_board_daily_hour_lag2month, to_alight_daily_hour_lag2month,
passenger_recent, passenger_mean_4obs, passenger_std_4obs,
passenger_count_4obs, passenger_trend, boarding_lag77d, bus_runs_lag77d
```

제공 문서의 `missing_feature_count=15` 요청 원문은 확보되지 않아 정확한 15개 이름을
역으로 특정할 수 없다. 별도 기존 golden 입력(노선 `100100001`, 정류장
`100000004→101000001`, 순번 23, 2026-09-18 09시)을 현재 로컬 v12로 재현한 결과는
147.55초, history 8건, 결측 **4개**였다. 결측은 위 목록의 `*_daily_hour_lag2month`
4개이며 월별 승하차 자료의 매칭/공개시각 가용성 조건을 만족하지 못한 입력이다.
이 별도 결과를 문서의 15개 결측 사례를 재현했다고 표현하지 않는다.
해당 한 번의 로컬 cold 예측은 약 1.96초였으며 통계적 성능 벤치마크가 아니다.

`tools/train_static_bus.py`의 과정:

1. 필요한 정답/요청 컬럼만 읽고 파일 SHA와 원본 행 수를 기록한다.
2. ID 형식, 순번·시각 범위, 날짜 파일명 일치, 양수·유한 정답을 검증한다.
3. 같은 날짜·노선·정류장 쌍·순번·시간대의 상충 정답은 해당 키 전체 제외한다.
   같은 정답 중복은 한 건으로 줄인다. 큰 지연을 1,800초에서 임의로 자르지 않는다.
4. 날짜순 앞 70%를 학습, 뒤 30%를 최종 평가로 분리하고 실제 행 비율을 기록한다.
   학습 구간 내부 앞 80%/뒤 20%에서 early stopping 반복 수만 선택한다.
5. 결정한 반복 수로 학습 70% 전체를 다시 학습한다. 최종 test로 모델을 선택하지 않는다.
6. 전체 평균, 노선 평균, 인접 edge·시간대 평균을 학습 구간에서만 만든 비교 기준으로 사용한다.
   이 고정 통계는 새 LightGBM 입력이 아니므로 target encoding 누수가 없다.
7. MAE/RMSE/P95 절대오차/bias/20초 이내 비율, 시간대별·노선별·미등록 ID 오차를 기록한다.
8. 현재 마스터의 인접 순번과 학습 관측 조합을 조인하여 서빙용 `edges.parquet`을 생성한다.

기존 v12도 같은 과거 평가 기간을 본 모델이므로 새 test는 완전히 새로 확보한 미래 평가라고
표현하지 않는다. 기존 v12와 정확히 같은 행 비교, 학습 표본 수별 오차, 실제 카카오 경로의
edge 커버리지·경로 합산 정확도·지연 성능 비교는 후속 실자료 검증 항목이다.

## 모델 묶음

| 파일 | 역할 |
|---|---|
| `model.txt` | LightGBM 공식 텍스트 모델. Python pickle 실행 없이 CPU 추론으로 읽음 |
| `input_spec.json`, `static_bus_features.py` | vocab·고정 공휴일·동일 학습/예측 변환 |
| `feature_schema.json`, `preprocessing.json` | 특성 순서·버전·제외 항목·시간 기준 |
| `edges.parquet` | 현재 공식 인접 조합, 방향, 학습 관측 수·최초/최종 관측일 |
| `fixed_statistics.parquet` | 학습 구간에서 만든 edge/시간 평균 비교 통계. 요청 처리 때 읽지 않음 |
| `serving_manifest.json` | 모델/특성/데이터/마스터 버전, 기술적 지원 날짜, SHADOW 승인 상태 |
| `metrics.json`, `coverage.json`, `source_usage.json` | 실제 평가, 현재 서울 마스터 커버리지, 입력 파일 SHA·품질·분할 |
| `bundle.json` | 필수 산출물 SHA-256. 외부 파일 신뢰 인증이 아니라 묶음 일관성 검사 |
| `test_predictions.parquet` | 평가 정답/예측 재검증용. 서빙 로딩에는 불필요 |

실행 경로는 GPU `j-moo/experiments/runtime/static-bus-20260918`, 결과는
`j-moo/experiments/runs/20260918-v13-static`(GPU 실패 기록), 재실행 결과는
`j-moo/experiments/runs/20260918-v13-static-cpu`다. 각 로그는 같은 runs 아래 `.log` 파일이다.
GPU 0 오류 이후 CPU 최대 16개를 명시적으로 선택한 실행이며 GPU 학습 완료로 기록하지 않는다.
기존 v12 디렉터리나 활성 모델 포인터를 교체하지 않는다.

## 독립 실험 실행 경계

| 파일 | 역할과 호출 방식 |
|---|---|
| `static_bus_features.py` | 학습과 오프라인 추론의 공통 10개 특성 변환. 기존 번들의 코드 SHA 재현을 위해 그대로 보존 |
| `static_bus_predictor.py` | 명시적으로 생성한 `StaticBusPredictor(bundle_path)`만 모델을 로딩. `predict(BatchRequest)`를 직접 호출 |
| `static_bus_schema.py` | 실험 출력 검증용 `StaticBatchResponse`. 현재 HTTP/OpenAPI 계약에 등록하지 않음 |
| `tools/train_static_bus.py` | 기존 정규화 자료로 별도 실험 폴더에 학습 산출물 생성 |
| `tools/finalize_static_bus.py` | 동일 행 v12 비교, golden, SHA 목록 및 새 ZIP 생성 |
| `tools/install_static_bundle.py` | 지정 ZIP SHA와 경로 안전성·golden 검증 후 새 폴더에만 설치. 활성 포인터 변경 없음 |
| `tools/evaluate_static_bus.py` | 저장된 매핑 결과와 고정 마스터 버전으로 적용 가능 비율과 추론 시간 평가 |
| `tools/audit_v13_lineage.py` | 원본 표본과 전체 정규화→학습 입력 비교. 원본과 모델은 읽기 전용 |

도구별 필수 인수는 `python tools/<파일명>.py --help`로 확인한다. 실행 환경은 기존 `ml-serving/requirements.txt`를 사용한다. 모델·데이터 경로는 사용자가 보유한 실제 경로를 명시하며 자동 다운로드·서버 등록은 하지 않는다.
학습·계보 검사는 대용량 원본이 있는 실험 환경에서만 명시적으로 실행한다. 이번 분리는 재학습이나 원본 재처리를 실행하지 않는다.

독립 예측은 기존 요청 자료형 `schemas.BatchRequest`와 안전한 경로 검사 `predictor.checked_path`를 읽기 전용으로 재사용한다. 두 기존 파일을 변경하지 않으며 v12 Predictor 인스턴스를 생성하거나 과거 reference를 조회하지 않는다.
출력은 `approval=SHADOW`, `production_ready=false`로 고정한다. `TEST_APPLY` 인수·출력은 이 분리 모듈에서 지원하지 않는다.
`training_sample_count`는 인접 조합의 학습 행 수이며 v12의 `history_sample_count`(조회된 과거 관측 수)와 다른 값이다.
공식 현재 인접 조합과 학습 관측의 교집합만 성공으로 반환하며 날짜·namespace·인접 조합 검증 실패는 `UNAVAILABLE`이다.
실험 실패값이 현재 Backend의 fallback을 직접 실행한다는 의미는 아니다. 5분 TTL은 실험 결과 재사용 제한이며 실시간 교통 반영을 뜻하지 않는다.

## 후속 연동 조건

계보 PASS와 성능 개선·여정 검증 전 운영 후보 승격을 금지한다. 이후 최신 develop에 맞춰 별도 `BusStaticClient`와 `/v2` SHADOW 경로를 설계한다.
기존 `BusPreviewClient`를 덮어쓰거나 v1/v2 전환 설정으로 교체하지 않는다. Backend TEST_APPLY, 서버 마운트·배포·활성화는 별도 작업이다.

## 검증 범위

기존 v12 테스트 파일은 수정하지 않는다. 신규 테스트는 특성 순서·날짜, 인접 조합 거절, SHA 변조, 온라인 파일 미조회, SHADOW 출력, 계보 검사기의 결측/숫자 불일치를 독립 검증한다.
과거 `112f950`의 Backend 22개/HTTP 연동 시험과 ML 63개 결과를 이 브랜치의 검증 결과로 사용하지 않는다. 현재 분리 검증 결과는 아래 별도 절에 기록한다.

## 2026-09-18 실제 학습 결과

- 학습 69,882,276행, 테스트 30,145,362행, 최종 595 iterations. GPU CUDA 오류 후 같은 서버의 CPU 최대 16개로 완료했다.
- 전체 테스트 MAE 37.378초, RMSE 187.888초, 절대오차 P95 96.595초, 20초 이내 비율 48.452%다.
- 기존 v12와 키·정답이 일치하는 30,004,903행에서 MAE는 v12 약 25.379초, v13 약 37.377초다.
  **과거 조회 의존성 제거는 달성했지만 정확도는 악화되었으므로 운영 승격 근거가 아니다.**
- 현재 서울 버스 마스터 인접 구간 40,959개 중 학습에서 관측된 동일 키는 34,862개(85.114%)다.
  이는 카카오 후보 전체 적용률이나 실제 여정 정확도가 아니다. 과거 노선 구조의 시점별 정합성도 미검증이다.
- 학습 서버 결과 디렉터리에 `metrics.json`, `comparison_v12.json`, `coverage.json`,
  `training_runtime.json`, `golden.json` 및 SHA가 포함된 번들이 있다.
  활성 모델은 교체하지 않았다. 기본 SHADOW 및 `production_ready=false`를 유지한다.

### 로컬 실모델 검증

#### 원천별 0 처리 확인 (2026-09-18)

- 후속 실제 계보 검사: [v13 계보 검증 결과](v13-lineage-audit-20260918.md).
  판정은 **PARTIAL**이며 원본 9개 날짜와 전체 하류 1,476개 날짜는 일치했다.
  원본 전 기간 검사가 완료되기 전 운영 후보 승격을 금지한다. 기존 모델·산출물은 보존한다.

- 현재 `tools/extend_reference_bundle.py` 정규화는 OA-21217에 `> 0`, 나머지 세 원천에
  `>= 0`을 사용한다. OA-21218/21219/21220의 실제 0은 보존된다.
- 과거 `112f950`에서 원천별 0 처리 테스트 총 7개가 통과했다. 이번 분리에서는 기존 `test_reference_update.py` 수정분을 가져오지 않는다. 상세 이력은 계보 문서를 따른다.
- v13 실제 실행 소스 `training_source.py`는 OA-21217의 `target_seconds > 0`만 학습 정답으로
  채택한다. OA-21218/21219/21220 컬럼은 읽지 않아 세 원천의 0 처리에 따른 직접 영향은 없다.
- 이 확인은 현재 정규화 코드와 v13 입력 처리에 대한 것이다. 재사용한 과거 `model_features`를
  생성한 전체 원천→집계 과정이 검증되었다는 의미는 아니다. 이미 집계에 포함된 0의 영향은
  최종 양수 정답 필터만으로 판별하거나 복구할 수 없다.
- 따라서 이번 규칙만으로 즉시 재학습해야 할 결함은 확인되지 않았다. 기존 SHADOW 상태를 유지한다.
  과거 정규화 오류가 발견되면 원본 보존 자료부터 원천별 정규화 → 관련 집계/특성 생성 →
  같은 날짜 분할로 재학습·재평가 → 번들 재생성 순서로 처리한다. OA-21218/21219/21220만의
  오류라면 해당 입력을 사용하는 v12에 영향이 있고, 이를 사용하지 않는 v13은 직접 재학습 대상이 아니다.

- 설치: `artifacts/ml-serving/20260918-v13-static-cpu`.
- ZIP SHA256: `8ea6055a116c48b3a17b5802036931a2e47696bbfebee5c645b3c251b5611abd`.
- 보존 번들의 서버 golden 10개와 로컬 독립 추론 결과가 일치했던 기록이다. 과거 Java/HTTP 연결 코드는 이 브랜치에 없다.
- 기존 1~20 카카오 예시의 저장된 매핑 결과에서 서울 버스 대안 273개 중 218개(79.85%)는
  모든 edge 예측이 가능했다. 나머지 55개는 오프라인 평가에서 전체 step 대체 불가로 판정했다. 실제 서비스에 v13을 적용한 결과가 아니다.
- 서로 다른 서울 edge 1,008개 중 839개(83.23%)가 지원된다. 경기/인천 대안 450개 및
  매핑 미확정 대안 45개는 이번 서울 모델 평가 대상이 아니다.
- 단일 edge warm 추론 30회: P50 30.27ms, P95 40.76ms. HTTP·이전 도보·대기·지하철을
  포함한 전체 추천 지연시간이 아니며, 기존 매핑을 다시 수행한 결과도 아니다.
- 상세 결과: `data/static-bus-v13/corpus-evaluation.json`.
- 전체 테스트의 고정 edge×hour 평균 baseline MAE는 34.526초로 새 모델보다 작다.
  새 모델은 미관측 ID 오차가 크고 비양수 예측도 있어 서빙에서 해당 값을 거절한다.
  운영 승격 전 정확도 개선과 실제 여정 정답 기반 검증이 필요하다.

## 최신 develop 기반 분리 검증 (2026-09-18)

- 기준: `origin/develop` `355c3ce`, 브랜치: `feature/dt/S15P21E202-74-v13-shadow-isolated`.
- 기존 ML 테스트: **49개 통과**. 보존된 v12 실제 번들을 `ML_TEST_BUNDLE_DIR`로 지정해 golden 대조까지 통과했다. 초기 모델 없는 체크아웃의 1개 skip을 해소했다. 기존 테스트 파일은 변경하지 않았다.
- 신규 독립 테스트: **11개 통과**(모델·스키마 8개, 계보 2개, 앱 격리 1개). 합계 **60개 통과**.
- 기존 Backend `BusPreviewClientTest`, `MlApplyModeTest`, `MlRoutePreviewTest`: **24개 통과**. 배치 중복 제거·결측 진단·모드별 적용·BUS step 전체 fallback을 포함한다. Backend 전체 테스트 실행 결과로 확대 해석하지 않는다.
- 독립 도구 5개의 `--help` 기동 확인. 재학습·원천 전체 재처리는 실행하지 않았다.
- 보존 v13 번들의 golden 10건 직접 추론: 최대 오차 **0초**, SHADOW, production_ready=false.
- 기존 앱은 ML 환경변수 없이 기동, `/health/live` 200. 모델 미설치 시 `/health/ready` 503은 기존 동작이다. `/v2/bus/health` 404, 기존 API 경로만 유지하며 v13 모듈 자동 import도 없다.
- develop 대비 기존 추적 파일 변경 0개. 신규 실험 파일만 추가하므로 app.py·schemas.py·OpenAPI·Backend·환경변수·배포 설정은 기준 커밋과 동일하다.
- 기존 작업 폴더의 dt/dev 병합 충돌은 보존했다. 이 브랜치의 별도 작업 폴더는 `data/worktrees/v13-shadow-isolated`다.
