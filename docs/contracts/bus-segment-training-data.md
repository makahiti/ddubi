# 버스 인접 구간 이동시간 학습 데이터 계약 v1

> 상태: 설계 계약. 이 문서는 수집·배포·재학습 완료를 의미하지 않는다.
>
> 첫 목표: 버스가 인접한 정류장 `A → B`를 이동하는 데 걸리는 실제 초를 예측한다.

## 1. 가장 먼저 구분할 것

수집 데이터를 나누는 5개 카테고리와 모델에 넣는 최종 한 행은 서로 다른 개념이다.

```text
수집·보관 카테고리(Silver)
├─ bus
├─ subway
├─ traffic
├─ city
└─ weather

첫 버스 모델이 실제로 사용하는 것(Gold)
├─ bus       : 구간·노선·운행 상태와 실제 이동시간 Label
├─ traffic   : 도로 속도·통행시간·사고·통제
├─ city      : 매핑되는 지역의 혼잡·유동인구(선택)
├─ weather   : 구간 격자의 날씨
└─ calendar  : 날짜에서 계산한 시간·요일·휴일
```

`subway` 데이터는 계속 Silver에 보관하지만, 인접 버스 구간 이동시간 모델 v1에는 넣지 않는다.
추후 실제 개선 효과가 확인되면 새로운 Feature 버전에서 추가한다.

## 2. 한 행의 의미

Gold 한 행은 **차량 한 대가 인접 구간 하나를 실제로 통과한 한 사례**다.

```text
어디       segmentId = BUS:노선:A:B
언제 예측  asOf = A 출발 시점
무엇을 입력 asOf까지 알 수 있었던 최신 Feature
무엇이 정답 B 도착시각 - A 출발시각
```

예시:

```text
08:10:00 차량 V123이 A 출발
08:12:35 차량 V123이 B 도착

Feature : 08:10:00까지 알 수 있었던 날씨·도로·도시·운행 상태
Label   : 155초
```

차량 ID는 출발과 도착을 연결하는 추적용 메타데이터다. 차량 번호 자체는 모델 Feature로 넣지 않는다.
외부 API의 도착예정 초, 시간표, ML 예측값도 실제 Label로 사용하지 않는다.

## 3. 결합 기준

모든 데이터는 다음 두 축으로 결합한다.

```text
공간 축: segmentId + masterVersion
시간 축: asOf + passageAt
```

- `asOf`: 예측을 실행했다고 가정하는 시각. 이 시각 이후 공개·수집된 정보는 사용하지 않는다.
- `passageAt`: 차량이 해당 구간에 진입할 시각. 바로 다음 A → B를 예측할 때는 `asOf`와 같다.
- 실황은 `observedAt`, `publishedAt`, `collectedAt`이 모두 `asOf` 조건을 만족해야 한다.
- 예보는 `asOf` 이전에 발표된 revision 중 `passageAt`에 적용되는 것만 사용한다.
- 값이 최신이라도 `validUntil <= passageAt`이면 사용하지 않는다.

실시간 예측은 Redis 최신값을 읽고, 과거 학습 행 생성은 HDFS Silver 이력을 읽는다. 읽는 저장소만
다르고 공간 매핑·시점 선택·결측 처리 함수와 결과 컬럼은 같아야 한다.

## 4. 카테고리별 필요한 데이터

### 4.1 구간 식별·마스터 — 필수

| 컬럼 | 용도 | 모델 입력 |
|---|---|---|
| `segmentId` | 방향이 있는 인접 구간 | 범주형 입력 |
| `masterVersion` | 당시 사용한 구간 마스터 | 검증·재현용 |
| `providerNamespace` | 서울·경기·인천 ID 구분 | 범주형 입력 후보 |
| `providerRouteId` | 노선 구분 | 범주형 입력 |
| `fromStopId`, `toStopId` | 인접 정류장 검증 | 범주형 입력 후보 |
| `fromStopSequence` | 노선 내 구간 위치 | 수치 입력 |
| `vehicleTraceId` | 같은 차량의 출발·도착 연결용 가명 ID | 모델 입력 제외 |

직선거리는 사용자가 제외하기로 한 기존 결정을 유지한다. 도로 링크 경로가 검증된 경우 도로의
통행시간을 사용하며, 정류장 좌표의 직선거리로 대신하지 않는다.

### 4.2 시간·달력 — 필수

| 컬럼 | 설명 |
|---|---|
| `serviceDate` | Asia/Seoul 운행일 |
| `hourSlot` | 0~23시 |
| `minuteOfHour` | 0~59분 |
| `dayOfWeek` | 1=일요일, 7=토요일 |
| `isWeekend` | 주말 여부 |
| `isHoliday` | 한국 공휴일 여부 |

이 값은 API에서 받지 않고 `passageAt`과 버전이 고정된 공휴일 달력에서 계산한다.

### 4.3 버스 운행 상태 — 핵심, 확보 가능한 값부터

| 컬럼 | 설명 | 비고 |
|---|---|---|
| `busCongestionCode` | 차량 혼잡 코드 | 실제 제공·의미 검증 시 사용 |
| `busOperationStateCode` | 운행·정차 상태 | 코드표 버전 필요 |
| `recentSegmentMedianSeconds` | 같은 구간의 최근 검증 실측 중앙값 | 미래 Label 포함 금지 |
| `recentSegmentSampleCount` | 위 통계의 표본 수 | 0과 미수집 구분 |

원본 `providerVehicleId`는 동일 차량의 A 출발과 B 도착을 연결하는 데만 사용한다. Gold에는 운행일
범위의 가명 `vehicleTraceId`를 남기고 학습 Feature에서는 제외한다.
“첫 번째 도착 버스/두 번째 도착 버스” 순서와 외부 도착예정 초는 구간 이동시간 v1에서 제외한다.

### 4.4 도로·사고·통제 — 핵심

| 컬럼 | 설명 |
|---|---|
| `roadSpeedKph` | 구간과 방향이 검증된 도로 링크의 속도 |
| `roadTravelSeconds` | 해당 도로 링크들의 통행시간 |
| `roadCoverageRatio` | 버스 구간 중 검증 도로 링크가 덮는 비율 |
| `accidentCount` | 구간 통과시간과 겹치는 유효 사고 수 |
| `closureCount` | 유효 통제 수 |
| `assemblyCount` | 검증된 영향 집회 수 |
| `protestCount` | 검증된 영향 시위 수 |

사건 피드가 전체 목록임을 보장할 때만 0건을 사용한다. 피드 불완전·매핑 실패는 `null`이다.
도로 반대 방향의 관측을 사용하지 않는다.

### 4.5 날씨 — 권장

| 컬럼 | 설명 |
|---|---|
| `temperatureC` | 기온 °C |
| `humidityPercent` | 습도 0~100% |
| `precipitationMm` | 강수량 mm |
| `snowDepthCm` | 적설 cm |
| `windSpeedMps` | 풍속 m/s |

`segmentId → 검증 기상 격자(nx, ny)` 대응표를 사용한다. 수도권 구간인데 현재 수집 범위에 해당
격자가 없으면 서울의 가장 가까운 격자를 억지로 붙이지 않고 결측으로 남긴다.

### 4.6 도시 혼잡·유동인구 — 선택

| 컬럼 | 설명 |
|---|---|
| `cityCongestionLevel` | 장소 혼잡 코드 |
| `areaPopulationMin`, `areaPopulationMax` | 장소 추정 인구 범위 |
| `nearbyPopulation` | 검증된 인접 센서의 유동인구 집계 |

서울 실시간 도시데이터는 제공 장소가 제한되어 있으므로 모든 구간의 필수 Feature로 만들지 않는다.
검증된 영역·센서 연결이 없으면 결측으로 남긴다.

## 5. Label — 학습을 가능하게 하는 정답

| 컬럼 | 설명 |
|---|---|
| `labelEventId` | 실제 통과 사실 ID |
| `startedAt` | A 출발 또는 구간 진입 실제 시각 |
| `completedAt` | B 도착 또는 구간 이탈 실제 시각 |
| `availableAt` | 이 정답을 시스템이 알 수 있게 된 시각 |
| `durationSeconds` | `completedAt - startedAt`, 0보다 큰 초 |
| `labelSource` | 실측 원천 |
| `labelQuality` | `VERIFIED` 또는 `ESTIMATED` |
| `accuracySeconds` | 추정 Label이면 시간 오차 상한 |

첫 운영 학습에는 `VERIFIED`만 사용한다. 위치 폴링으로 만든 `ESTIMATED`는 별도 실험에서만 사용한다.
현재 8시간 버스 위치 수집과 도착예정값은 이 Label을 만들 수 없으므로, 실측 원천 확보 전에는
Feature 이력을 쌓을 수는 있어도 새 지도학습을 완료했다고 할 수 없다.

## 6. 결측 규칙

결측은 0으로 바꾸지 않는다.

```text
precipitationMm = 0     실제 무강수
precipitationMm = null  날씨를 알 수 없음

accidentCount = 0       완전한 피드에서 사고가 없음
accidentCount = null    피드가 불완전하거나 구간 매핑이 없음
```

`missingFeatures`에 결측 컬럼 이름을 함께 기록한다. 핵심 ID나 Label이 결측이면 Gold에 넣지 않고
Rejected로 보낸다. 선택 Feature가 결측이면 행은 유지하고 모델의 고정 결측 처리 규칙을 적용한다.

## 7. Silver와 Gold의 역할

```text
HDFS Silver
├─ 각 카테고리의 정제된 전체 이력
├─ 나중에 전처리 규칙을 바꿔 다시 계산할 수 있음
└─ 아직 하나의 학습 행이 아님

HDFS Gold
├─ 모델 입력 컬럼이 고정된 한 DataFrame
├─ Feature는 asOf 시점으로 결합
├─ 실제 Label이 붙은 행만 학습 가능
└─ featureVersion별 불변 Snapshot
```

권장 경로:

```text
/ddubi/silver/bus/
/ddubi/silver/traffic/
/ddubi/silver/city/
/ddubi/silver/weather/
/ddubi/silver/segment-traversal/

/ddubi/gold/bus-segment-travel-time/
└─ featureVersion=bus-segment-feature-v1/
   └─ version=<생성 버전>/
```

## 8. 실시간과 학습의 동일성

아래 함수의 의미를 스트리밍과 배치에서 공유한다.

```text
build_segment_features(segmentId, masterVersion, asOf, passageAt)
```

```text
실시간: Redis 최신 관측 → build_segment_features → predict
과거  : HDFS Silver 이력 → build_segment_features → Label 결합 → Gold
```

두 경로는 `bus-segment-feature-v1`의 컬럼 이름·타입·단위·순서·결측 처리가 같아야 한다.
실시간 예측에 실제 사용한 Feature Snapshot과 모델 버전도 HDFS에 보존하면 과거 재현과 성능 비교가
쉬워진다.

### 8.1 Redis 온라인 Feature

카테고리별 최신 Redis 키만으로도 이론상 예측할 수 있지만, 예측 요청마다 날씨·도로·도시 키와 대응표를
각각 조회하고 결합하면 ML Serving에 전처리 책임이 중복된다. Spark가 한 번 결합한 구간 단위 Snapshot을
별도 키로 발행한다.

```text
Key
ml:feature:bus-segment:{featureVersion}:{masterVersion}:{segmentId}

Value
segmentId + masterVersion + asOf + validUntil
+ bus/traffic/weather/city 최신 Feature
+ missingFeatures + provenance
```

- 작성자: Spark
- 독자: 실시간 ML 입력 조립기
- 형식: `bus-segment-online-feature.schema.json`
- TTL: 최대 900초. 단, `validUntil`이 먼저 지나면 즉시 stale로 취급한다.
- 갱신: 더 최신 `computedAt`만 저장하는 원자적 비교 후 갱신한다.
- 장애·결측: 오래된 값을 정상값처럼 사용하지 않는다. 모델의 명시적 결측 처리 또는 기준선 fallback을 쓴다.

시간·달력 Feature는 요청마다 달라지므로 Redis에 고정 저장하지 않고 `predictionTime/passageAt`에서 계산한다.
Label은 미래에 확인되는 정답이므로 Redis 온라인 Feature에 절대 넣지 않는다.

```text
Spring → ML 입력 조립기: segmentId, masterVersion, predictionTime, passageAt
ML 입력 조립기 → Redis: 구간별 Online Feature MGET
ML 입력 조립기: 시간·요일·휴일 추가 + 스키마/TTL/validUntil 검증
ML 모델: predict
```

현재 `ml-serving` HTTP 구현은 Redis 자격증명이나 직접 조회를 요구하지 않는다. 따라서 위 흐름을 실제로
사용하려면 다음 단계에서 `ml-serving 직접 조회` 또는 `별도 Feature Gateway 조회` 중 하나를 확정하고
구현·통합 테스트해야 한다. 계약이 생겼다는 사실을 배포 완료로 해석하지 않는다.

## 9. 구현 순서

1. 이 계약의 필수 ID·시간·단위와 기계 스키마를 고정한다.
2. `segmentId → 날씨 격자·도로 링크·도시 영역` 대응표를 버전별로 만든다.
3. 카테고리별 정규화 결과를 HDFS Silver에 전체 이력으로 저장한다.
4. 같은 전처리 함수로 구간 단위 Redis Online Feature를 만들고 최신 시각 비교·TTL을 적용한다.
5. 실제 A 출발·B 도착을 연결하는 Label 생산자를 확보한다.
6. Spark Batch가 Label을 기준으로 과거 Feature를 시점 결합해 Gold를 만든다.
7. 날짜 경계로 train/validation/test를 분리하고 기존 기준선·모델과 비교한다.

처음 구현할 최소 Feature는 `구간·노선 + 시간·요일·휴일 + roadSpeedKph + 날씨 + 실제
durationSeconds`다. 도시 데이터와 사건 수, 버스 혼잡은 매핑률·결측률을 확인한 뒤 단계적으로 추가한다.

## 10. 현재 모델과의 관계

현재 `20260908-v12`는 `hourly_mean_bus_segment_seconds`를 반환하는 프리뷰 모델이다. 이 문서의
개별 `ACTUAL_TRAVERSAL` Label 계약과 같은 모델이라고 간주하지 않는다. 개별 실측을 시간대별 평균으로
집계해 기존 목표와 맞출지, 개별 통과시간을 직접 예측할지는 후보 모델 비교에서 명시적으로 선택하고
서로 다른 target/featureVersion으로 관리한다.
