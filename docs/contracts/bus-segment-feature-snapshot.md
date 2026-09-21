# 버스 구간 현재 Feature Snapshot 계약

## 목적

ML이 한 경로의 버스 인접 구간을 예측할 때 날씨·도로·도시·버스 Redis Key를 다시 조합하지 않는다.
DT가 구간별 완성 Snapshot을 만들고, ML은 경로의 Snapshot Key 목록을 Redis `MGET` 한 번으로 읽는다.

이 계약은 **현재 상태**만 다룬다. 미래 예상 통과시각별 예보 Snapshot은 같은 Key에 저장하지 않는다.

## Key

```text
ml:feature:bus-segment:{featureVersion}:{masterVersion}:{segmentId}
```

예시:

```text
ml:feature:bus-segment:bus-segment-feature-v1:metropolitan-bus-v6:BUS:100100609:123456:123789
```

- `featureVersion`: JSON 필드·단위·결측 의미가 바뀌면 올린다.
- `masterVersion`: Backend·Collector·Spark가 검증한 동일 버스 마스터 버전이다.
- `segmentId`: canonical 인접 버스 구간 ID다. 제공자 ID를 새로 이어 붙이지 않는다.
- 경로 전체를 한 Key에 넣지 않는다. 경로 조합은 너무 많고 한 구간 갱신이 전체 경로 Key를 무효화하기 때문이다.

## 값과 시각

값은 `bus-segment-online-feature.schema.json`을 따른다.

- `snapshotKind=CURRENT`: 현재 상태 전용임을 표시한다.
- `asOf`: 원천을 선택한 현재 기준시각이다.
- `computedAt`: Snapshot 조립 완료시각이다.
- `validUntil`: 가장 먼저 만료되는 사용 원천과 캐시 상한 중 빠른 시각이다.
- `missingFeatures`: `features`에서 값이 `null`인 필드와 정확히 같아야 한다.
- `provenance`: 사용한 원천 Key·eventId·관측/수집/만료시각과 매핑 버전을 보존한다.

TTL은 메모리 보관 상한일 뿐이다. ML은 TTL이 남아 있어도 `asOf >= validUntil`이면 `STALE`로 처리한다.
잘못된 버전·구간 ID·필드 집합·시각의 값은 원문을 반환하지 않고 `INVALID`로 처리한다.

## 단위와 결측

| 필드 묶음 | 단위 |
|---|---|
| `*Seconds` | 초 |
| `roadSpeedKph` | km/h |
| `temperatureC` | 섭씨 |
| `humidityPercent`, `roadCoverageRatio` | 각각 0~100, 0~1 |
| `precipitationMm`, `snowDepthCm` | mm, cm |
| `windSpeedMps` | m/s |
| `*Count`, 인구 | 건, 명 |

원천이 없거나 오래됐거나 매핑이 불확실하면 `0`을 만들지 않고 `null`로 둔다. 현재 조립하지 않는
최근 구간 통계·사고/통제/집회·적설·주변 유동인구도 같은 규칙을 사용한다.

## ML 배치 조회

`POST /v1/bus/features:batch`는 `masterVersion`, 현재 `asOf`, 최대 1,000개의 고유 `segmentIds`를 받는다.
구현은 모든 Snapshot Key를 만든 뒤 Redis `MGET`을 정확히 한 번 호출하고 입력 순서대로 반환한다.

구간별 상태:

- `AVAILABLE`: 신원·스키마·시각 검증을 통과한 Snapshot. `snapshot`을 반환한다.
- `MISSING`: Key가 없다.
- `STALE`: `validUntil`이 지났다.
- `INVALID`: 버전·구간·필드·시각 계약이 맞지 않는다.

`MISSING`, `STALE`, `INVALID`는 `snapshot=null`이며 ML/Backend가 기존 정적 예측 또는 명시적인
데이터 부족 처리로 전환한다. 이 API가 새 Feature를 기존 v12 모델에 자동 입력하지는 않는다.

## 현재 Snapshot 쓰기

기존 `POST /v1/bus/features:latest` 조립기는 `asOf`, `passageAt`, 실행시각이 모두 현재 30초 범위일
때만 위 Key를 `SETEX`한다. 과거 Replay와 미래 통과시각 요청은 결과를 반환할 수 있지만 CURRENT
Snapshot Key를 덮어쓰지 않는다. 운영에서 지속적으로 채우려면 같은 조립기를 현재시각으로 호출하는
생산 작업을 연결하며, 미래 예보가 필요하면 별도 time-bucket Feature 버전과 Key 계약을 만든다.

## 운영 전 확인

1. 활성 날씨·도로·도시 매핑의 버스 crosswalk SHA가 ML 설정과 같은지 확인한다.
2. 현재시각 조립 후 Key의 TTL, `validUntil`, `missingFeatures`를 확인한다.
3. 실제 경로의 여러 구간을 배치 API로 읽고 Redis 명령이 MGET 한 번인지 확인한다.
4. 만료·결측·잘못된 masterVersion에서 기존 예측 fallback이 유지되는지 확인한다.

