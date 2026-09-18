# ML 참조 자료 결측 확정 — 공급처 원천 결측 11건 (A안)

- 결정일: 2026-09-18 (Asia/Seoul)
- 대상 묶음: `20260908-v12`, 운영 마운트 `20260908-v12-ref-20260917-through-1010`
- 지원기간: 2026-09-10 ~ 2026-10-10
- 기계 판독본: [ml-reference-gaps-20260918.json](ml-reference-gaps-20260918.json)
- **선행 계약**: [transit-data-refresh.md](transit-data-refresh.md) — 묶음 갱신 절차, `missing_files` 11개 명시, 원천별 `0` 규칙의 정본
- 관련: [ml-retraining-v12.md](ml-retraining-v12.md), [ml-serving-http.md](ml-serving-http.md), [ml-data-decision.md](ml-data-decision.md)
- 후속 개선안(미실행): [ml-history-lag-redesign-plan.md](ml-history-lag-redesign-plan.md)

> 이 문서는 `transit-data-refresh.md`를 대체하지 않는다. 그 문서가 갱신 절차와 규칙의 정본이고,
> 이 문서는 **결측 11건이 공급처 원천 결측임을 재다운로드로 확정한 검증 기록**과 날짜별 영향 분석을 더한다.

## 1. 결정

운영 bundle의 누락 참조 파일 **11개를 `공급처 원천 결측`으로 확정**한다.
재수집·재정규화·재포장으로 복구할 수 없다. 이번 릴리스는 결측을 **그대로 인정하고 기록**한다.

**누락 날짜를 앞뒤 날짜 복사, 평균, 보간, 요일 대체 등 어떤 방법으로도 생성하지 않는다.**
관측이 없는 것과 값이 0인 것을 구분하는 기존 원칙을 그대로 유지한다.

## 2. 확정 근거

### 2.1 공식 원천 재다운로드 (2026-09-18)

| 항목 | 파일 1 | 파일 2 |
|---|---|---|
| 파일명 | `tpss_route_section_speedh_202607.zip` | `tpss_route_section_speedh_202606.zip` |
| 다운로드 URL | `https://datafile.seoul.go.kr/bigfile/iot/inf/nio_download.do?&useCache=false` (POST `infId=OA-21217`, `seq=2607`, `infSeq=3`) | 같은 URL, `seq=2606` |
| 다운로드 시각 | 2026-09-17T17:03:56Z / 2026-09-18 02:03:56 KST | 2026-09-17T17:07:33Z / 2026-09-18 02:07:33 KST |
| 크기 | 117,488,969 바이트 | 124,257,598 바이트 |
| SHA-256 | `6834b0b5880e133018aa8c7d0c3023a4fd9a7e188b244a807ad8a2d7bff57c56` | `e7abff08222b6992e1e126bccb7ea74457b1852b3dc93cd5981bd933297c3fb2` |
| 보관 위치 | `/home/j-j15e202/ybk/incoming/20260918-oa21217-recheck/` (GPU, 신규 격리 디렉터리) | 같음 |

로그인·인증·CAPTCHA·유료 요청은 없었다. 공식 배포 경로만 사용했고 미러는 쓰지 않았다.

### 2.2 기존 스냅샷과 동일

`202606.zip`의 SHA-256이 2026-09-04 스냅샷
(`data/extracted/DDUBI_ALL_LINK_DATA_20260903/OA-21217/`)과 **바이트 단위로 같다**.
`202607.zip` 내부 4개 주간 CSV도 스냅샷과 SHA가 모두 같다.

**공급처는 이미 공개한 파일을 사후에 고치지 않는다.** 2주 간격 재확인에서 변경 0건이다.

`2026.06.29-07.05` 주는 ZIP 내부 수정시각이 2026-07-21 14:22:50으로 다른 주(해당 주 다음 월요일 00:18)와 다르다.
그 주만 한 번 다시 처리됐는데도 결측 3일은 채워지지 않았다.

### 2.3 원천 CSV의 날짜별 행 수

정상일은 하루 약 77,000~84,000행이다. 결측일은 **0행**이다.

| 원천 주간 CSV | 포함 날짜 | 결측 |
|---|---|---|
| `…speedh_2026.05.11-05.17.csv` | 11·12·13·14·15·17 | **05-16** |
| `…speedh_2026.05.18-05.24.csv` | 19·20·21·22·23·24 | **05-18** |
| `…speedh_2026.06.15-06.21.csv` | 16·17·18·19·20·21 | **06-15** |
| `…speedh_2026.06.22-06.28.csv` | 22·23·24·25·26·27 | **06-28** |
| `…speedh_2026.06.29-07.05.csv` | 29·30·02·03 | **07-01·07-04·07-05** |
| `…a18route_section_2026.06.15-06.21.csv` | 15·17·18·19·20·21 | **06-16** |
| `…a18route_section_2026.06.29-07.05.csv` | 29·30·02·03·04·05 | **07-01** |
| `…hpasngr_2026.06.29-07.05.csv` | 29·30·02·03·04·05 | **07-01** |
| `…hturn_2026.07.13-07.19.csv` | 13·14·15·16·18·19 | **07-17** |

결측은 데이터셋마다 다르다. 예로 06-15는 OA-21217에는 없고 OA-21218에는 있으며, 06-16은 그 반대다.
요일 패턴도 없다(토·월·월·일·수·토·일·화·금). 우리 쪽 처리 누락이 아니라 공급처의 데이터셋별 일별 결락이다.

### 2.4 정규화가 빠뜨린 것이 아님

`bus_daily_quality.json`은 원소 1,476개이고 생성된 parquet도 1,476개로 1:1이다.
결측 7개 날짜는 이 목록에 등장하지 않는다. 정규화 단계가 해당 날짜를 **입력으로 받은 적이 없다**.
`bus_byday_staging`, `quarantine`에도 해당 날짜가 없다.

## 3. 결측 11건과 영향

| # | 참조 경로 | 데이터셋 | 영향 서비스 날짜 (2026-09-10 ~ 10-10) | 영향 특성 |
|---:|---|---|---|---|
| 1 | `bus_daily/2026-05-16.parquet` | OA-21217 | 09-12, 09-19 | 이력 표본 1개 감소 |
| 2 | `bus_daily/2026-05-18.parquet` | OA-21217 | 09-14, 09-21 | 이력 표본 1개 감소 |
| 3 | `bus_daily/2026-06-15.parquet` | OA-21217 | 09-14, 09-21, 09-28, 10-05 | 이력 표본 1개 감소 |
| 4 | `bus_daily/2026-06-28.parquet` | OA-21217 | 09-13, 09-20, 09-27, 10-04 | 이력 표본 1개 감소 |
| 5 | `bus_daily/2026-07-01.parquet` | OA-21217 | 09-16, 09-23, 09-30, 10-07 | 이력 표본 1개 감소 |
| 6 | `bus_daily/2026-07-04.parquet` | OA-21217 | 09-19, 09-26, 10-03, 10-10 | 이력 표본 1개 감소 |
| 7 | `bus_daily/2026-07-05.parquet` | OA-21217 | 09-20, 09-27, 10-04 | 이력 표본 1개 감소 |
| 8 | `aux_daily/OA-21218/2026-06-16.parquet` | OA-21218 | 09-15, 09-22 | 승객 표본 4개 중 1개 |
| 9 | `aux_daily/OA-21218/2026-07-01.parquet` | OA-21218 | 09-16, 09-23, 09-30, 10-07 | 승객 표본 4개 중 1개 |
| 10 | `aux_daily/OA-21219/2026-07-01.parquet` | OA-21219 | 09-16 | `boarding_lag77d` 전면 결측 |
| 11 | `aux_daily/OA-21220/2026-07-17.parquet` | OA-21220 | 10-02 | `bus_runs_lag77d` 전면 결측 |

### 영향 요약

- 31일 중 **17일**에서 `history_sample_count` 상한이 8 미만이 된다.
  - 상한 6: 09-14, 09-19, 09-20, 09-21, 09-27, 10-04 (6일)
  - 상한 7: 09-12, 09-13, 09-16, 09-23, 09-26, 09-28, 09-30, 10-03, 10-05, 10-07, 10-10 (11일)
- 나머지 **14일은 영향 없음**.
- `boarding_lag77d`는 09-16에만, `bus_runs_lag77d`는 10-02에만 전면 결측이다.

이 결측은 **이력 상한을 낮출 뿐**이며, 별도로 존재하는 `history_sample_count=0` 문제
(해당 노선·시간대에 원천 관측 자체가 없는 경우)와는 원인이 다르다. 두 가지를 섞어 설명하지 않는다.

## 4. 2026-09-18 테스트일 — 영향 없음

서비스일 2026-09-18이 참조하는 지연 날짜는 다음과 같다.

| 원천 | 지연(일) | 참조 날짜 |
|---|---|---|
| `bus_daily` | 77, 84, 91, 98, 105, 112, 119, 126 | 07-03, 06-26, 06-19, 06-12, 06-05, 05-29, 05-22, 05-15 |
| `aux_daily/OA-21218` | 77, 84, 91, 98 | 07-03, 06-26, 06-19, 06-12 |
| `aux_daily/OA-21219` | 77 | 07-03 |
| `aux_daily/OA-21220` | 77 | 07-03 |

위 11개 결측 날짜(05-16, 05-18, 06-15, 06-16, 06-28, 07-01, 07-04, 07-05, 07-17) 중
**어느 것도 이 목록에 포함되지 않는다.**

실측 확인: route `100100014` / from `100000022` / to `100000104` / order 33 / 2026-09-18 10시 요청에서
8개 지연 날짜 전부 정확히 1행씩 매칭되어 `history_sample_count = 8`이 반환됐다.

**결론: 2026-09-18 테스트는 이번 11개 결측의 영향을 받지 않는다.**

## 5. API·진단 노출 점검 (요구사항 3)

### 5.1 노출되는 항목 — 확인 완료

`GET /health/ready` 실제 응답(2026-09-18 확인):

```
status, model_version, model_type, target, production_ready, bundle_production_ready,
supported_from, supported_until, reference_status, reference_days_remaining,
missing_reference_file_count
```

`missing_reference_file_count = 11`이 실제로 노출된다. `reference_status = CURRENT`, `reference_days_remaining = 22`.

예측 응답의 `prediction` 객체는 다음을 모두 반환한다.

- `history_sample_count` — 실제 유효 이력 수 (0~8)
- `missing_feature_count` — 결측 수치 특성 개수
- `missing_features` — 결측 특성 **이름 배열**
- `data_insufficient` — 현재 구현상 `history_sample_count == 0`과 동치

### 5.2 배포본과 작업 브랜치의 차이

Backend는 **배포본(`cdd7c47`)과 작업 브랜치(`a9b380c`)의 동작이 다르다.** 구분해 기록한다.

| 항목 | 배포본 `cdd7c47` | 작업 브랜치 `a9b380c` |
|---|---|---|
| `missing_features` 이름 보존 | **버림** (`missingFeatureCount`만) | **보존** (최대 32개, 초과 시 `missingFeaturesTruncated=true`) |
| `training_ids_verified` 보존 | 없음 | 보존 |
| ML 적용 모드 | 구분 없음 | `SHADOW` / `TEST_APPLY` / `PRODUCTION`, 기본 `SHADOW` |
| 배치 호출 | edge별 순차 | 배치 |

작업 브랜치의 `missing_features`는 **진단 전용**이며 일반 사용자 화면에 원시 특성명을 내보내지 않는다.
`TEST_APPLY`는 `application-test.yml`이 허용 값을 함께 줄 때만 켜지고, 운영 환경변수 하나로는 켜지지 않는다.

### 5.3 저장소 ↔ 배포본 동기화 상태 — 차이 없음

작업 브랜치 worktree의 `ml-serving/`과 배포 컨테이너 `/app`의 SHA-256을 대조했다(2026-09-18 확인).

| 파일 | 결과 |
|---|---|
| `app.py` | **동일** `3ad2b19a…` |
| `predictor.py` | **동일** `162249e6…` |
| `.env.example` | **동일** `ebcf5788…` |
| `README.md` | **동일** `ca613f8d…` |
| 나머지 `.py`, `Dockerfile`, `requirements.txt` | **동일** |

**동기화할 것이 없다.**

새 health 필드는 `predictor.py: metadata()`가 아니라 `app.py: reference_health()`에서 만든다.
`missing_reference_file_count`는 묶음 `bundle.json`의 `missing_files` 길이이고,
`reference_status`는 서울 기준 오늘 날짜와 `supported_from`/`supported_until`을 비교해
`NOT_STARTED` / `CURRENT` / `EXPIRED`로 계산한다. `CURRENT`가 아니면 readiness가 503이다.

> 주 checkout(`feature/frontend/S15P21E202-46-conditional-departure-fe`)의 `ml-serving/` 사본은
> 더 낮은 버전이다. 다른 브랜치이므로 정상이며 동기화 대상이 아니다.

## 6. fallback 계약 점검 (요구사항 4) — 계약과 일치

작업 브랜치 `a9b380c`의 `BusPreviewClient.Estimate.applicationFailure()`:

```java
if (Boolean.TRUE.equals(dataInsufficient)) return "ML_HISTORY_INSUFFICIENT";
// 서버가 data_insufficient를 표시하지 않았어도 표본 0이면 계산에 쓰지 않는다
if (seconds != null && historySampleCount != null && historySampleCount == 0) return "ML_HISTORY_INSUFFICIENT";
if (seconds == null) return reason;
if (!Double.isFinite(seconds) || seconds < 0) return "ML_INVALID_DURATION";
return null;
```

배포본 `cdd7c47`에는 두 번째 조건이 없다. 작업 브랜치에서 **이중 안전장치로 강화**됐다.

`JourneyEvaluationService`는 edge 반복 중 `applicationFailure()`가 null이 아니면
`complete = false`로 두고 즉시 중단한다. `complete`가 false면 그 BUS step은 ML을 적용하지 않고
원본 이동시간으로 fallback한다. 일부 edge만 ML로 바꾸는 경로는 없다.

**즉 이력이 부족하면(`history_sample_count = 0`) 해당 BUS step 전체가 fallback된다.**
현재 서비스 계약과 일치하며 이번 결측 확정으로 바꿀 것이 없다.

모델이 결측을 학습 중앙값으로 대체해 숫자를 만들 수 있으나, 그 숫자는 적용 자격과 분리된다.
`production_ready=false`인 현재 v12는 PRODUCTION 적용 대상이 아니다.

## 7. 정규화 계약 — `0`의 의미는 원천마다 다르다 (요구사항 6)

**원천 네 곳 모두 시간대 열에 빈칸이 없다. 값이 없으면 `0`이 들어온다.
그러나 `0`의 의미는 원천마다 다르므로 한 규칙으로 묶으면 안 된다.**

| 원천 | 지표 | `0`의 의미 | 정규화 |
|---|---|---|---|
| **OA-21217** | 구간 운행시간(초) | **관측 부재** | **결측(NaN)으로 분리** |
| OA-21218 | 재차인원 | 실제 0명 | **유효 관측으로 보존** |
| OA-21219 | 승차 승객 수 | 실제 0명 | **유효 관측으로 보존** |
| OA-21220 | 버스 운행횟수 | 실제 0회 | **유효 관측으로 보존** |

근거 — `ml-serving/tools/extend_reference_bundle.py:46`

```python
valid = number.gt(0) & number.le(86400) if source == SOURCES[0] else number.ge(0) & np.isfinite(number)
```

`SOURCES[0]`이 OA-21217이다. 이 원천만 `> 0` 조건이고 나머지는 `>= 0`이다.
같은 규칙이 [transit-data-refresh.md](transit-data-refresh.md)에도 이미 명시돼 있다.

### 7.1 OA-21217의 `0` — 관측 부재

측정값(2026-09-18 확인, OA-21217 6월 주간 CSV):

| 날짜 | 시간대 칸 | 값 있음 | `0` 값 | 빈칸 |
|---|---:|---:|---:|---:|
| 2026-06-16 | 2,018,424 | 66.6% | **33.4%** | 0.0% |
| 2026-06-29 | 1,997,448 | 65.9% | **34.1%** | 0.0% |
| 2026-07-03 | 1,994,520 | 67.1% | **32.9%** | 0.0% |

정규화 코드 `ddubi_rebuild_v12.py: travel_chunk()`가 이를 처리한다.

```python
nums = frame[hcols].apply(pd.to_numeric, errors='coerce')
ok = nums.gt(0) & nums.le(86400)
for i, h in enumerate(HOURS):
    d[h] = nums.iloc[:, i].where(ok.iloc[:, i]).astype('float32')
```

**계약으로 고정한다.**

- OA-21217의 `0`은 **실제 운행시간 0초가 아니라 해당 시간대 관측 부재**를 뜻한다.
- 정규화는 OA-21217의 `0`을 결측(NaN)으로 분리한다. 0을 관측값으로 보존하지 않는다.
- **OA-21218·21219·21220의 `0`은 유효 관측이다.** 결측으로 바꾸지 않는다.
- 결측을 0으로 채우지 않는다. 0을 결측으로 오인해 삭제하지도 않는다.
- `zero_cells` 수치는 품질 보고에 별도 기록한다.
- 86,400초(하루) 초과만 불가능 범위로 격리하며, 1,800초 초과를 임의 삭제하지 않는다.

원천 0값 비율(약 33~34%)은 서비스일 2026-09-18 기준 edge-hour `history_sample_count = 0`
비율(24시간 기준 30.1%, 05~23시 기준 16.7%)의 직접 원인이다.

## 8. 이번 릴리스에서 하지 않은 것

- 결측일 생성(복사·평균·보간·요일 대체) — **금지 유지**
- 정규화 재실행, parquet 생성
- bundle 재생성·교체
- 모델 재학습
- 서버 배포, DB 변경
- 기존 GPU 원본·정규화 파일 수정 (타임스탬프 무변경 확인: extracted 2026-09-04 08:18, normalized 2026-09-08)

## 9. 남은 과제

| 과제 | 상태 |
|---|---|
| 저장소 `ml-serving/` 사본 동기화 | **해당 없음** — 작업 브랜치가 배포본과 SHA 동일 (5.3 참조) |
| Backend가 `missing_features` 이름을 진단에 보존 | 작업 브랜치 `a9b380c`에서 **구현 완료**, 배포 전 |
| ML 적용 모드 분리(SHADOW/TEST_APPLY/PRODUCTION) | 작업 브랜치 `a9b380c`에서 **구현 완료**, 배포 전 |
| 지연일수 설계 개선 | 설계·평가 계획만 작성 → [ml-history-lag-redesign-plan.md](ml-history-lag-redesign-plan.md) |
| 2026-10-10 이후 참조 창 연장 | 신규 ZIP(`09-17~10-17`) 준비됨. 다만 결측 구멍은 동일하며 교체 시점 미정 |

### 9.1 선택 회귀 테스트 후보 (구현하지 않음)

`ml-serving`은 이미 `tests/test_reference_update.py`가 `NOT_STARTED`/`CURRENT`/`EXPIRED`와
`reference_status`·`missing_reference_file_count`를 검증한다. 아래는 **추가 후보일 뿐이며 이번 릴리스에서 구현하지 않는다.**

- `local_bundle()` 경로 이탈 거절: `../` 포함, 중첩 경로, 깨진 JSON에서 `BUNDLE_PATH_INVALID`
- `ML_BUNDLE_DIR`이 설정되면 `active-bundle.json` 포인터를 무시하는지
- `supported_until` 당일 경계: `reference_days_remaining == 0`이면서 아직 `CURRENT`인지

## 10. 이 문서의 검증 기준 시점

- GPU 원천·정규화 확인: 2026-09-18 KST
- 배포 서버 `/health/ready` 확인: 2026-09-18 02:14 KST
- 배포 Backend 커밋: `cdd7c47` (컨테이너 기동 2026-09-17T13:54:48Z)
- 작업 브랜치 커밋: `a9b380c`

Backend 동작을 인용할 때는 **어느 커밋 기준인지 반드시 함께 적는다.** 두 시점의 동작이 다르다.
