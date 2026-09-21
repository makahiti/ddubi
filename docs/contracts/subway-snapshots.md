# 지하철 Redis Snapshot 계약

## 목적

Backend가 Redis 전체를 검색하지 않고 정확한 키 하나로 역의 도착 열차 또는 호선의 열차
위치를 한 번에 읽게 한다. 기존 열차별 `transit:latest:subway-*` 키는 호환성과 개별 진단을
위해 그대로 유지한다.

## 키

| 조회 목적 | Redis 키 | 값 |
| --- | --- | --- |
| 역별 도착정보 | `transit:snapshot:subway-arrivals:{providerLineId}:{providerStationId}` | JSON string |
| 노선별 열차위치 | `transit:snapshot:subway-positions:{providerLineId}` | JSON string |

조회자는 알고 있는 호선·역 ID로 `GET`한다. 운영 경로에서 `KEYS` 또는 `SCAN`으로 Snapshot을
찾지 않는다. 이 키의 ID는 서울 API provider ID이며 canonical 역·구간 매핑 완료를 뜻하지 않는다.

## 공통 JSON

```json
{
  "schemaVersion": "subway-snapshot-v1",
  "snapshotType": "SUBWAY_ARRIVALS_BY_STATION",
  "scope": {"lineId": "1002", "stationId": "0201"},
  "observedAt": "2026-09-21T10:00:00.000+09:00",
  "collectedAt": "2026-09-21T10:00:05.000+09:00",
  "validUntil": "2026-09-21T10:02:00.000+09:00",
  "freshnessSeconds": 120,
  "retentionSeconds": 1080,
  "itemCount": 1,
  "source": "spark.realtime_latest",
  "items": []
}
```

- `observedAt`은 포함된 열차 중 가장 최신 관측시각이다.
- `validUntil`은 포함된 항목 중 가장 늦은 유효 종료시각이다. 소비자는 루트 값만 믿지 말고
  각 항목의 `validUntil`을 현재 요청시각과 비교하여 만료 항목을 제외한다.
- 현재 상태를 고정 문자열로 저장하지 않는다. 조회 시각이 항목의 `validUntil` 이전이면 `FRESH`,
  이후면 `STALE`로 계산한다.
- TTL은 장애 분석을 위한 보관 상한이지 실시간 유효시간이 아니다.
- 키 부재, JSON 오류, 지원하지 않는 `schemaVersion`, 유효한 항목 0개는 모두 `UNKNOWN`이다.
  빈 목록을 정상 운행 또는 운행 열차 없음의 증거로 사용하지 않는다.

## 역별 도착 항목

`snapshotType`은 `SUBWAY_ARRIVALS_BY_STATION`이다. 항목은 도착예정 초와 열차번호 순으로
정렬하며 최대 128개를 저장한다.

```json
{
  "arrivalSeconds": 80,
  "trainId": "T1",
  "arrivalStatusCode": "99",
  "arrivalMessage": "전역 출발",
  "previousStationMessage": "을지로입구",
  "direction": "내선",
  "destination": "성수",
  "trainType": "일반",
  "sourceGeneratedAt": "2026-09-21 10:00:00",
  "occurredAt": "2026-09-21T10:00:00.000+09:00",
  "collectedAt": "2026-09-21T10:00:05.000+09:00",
  "eventId": "event-id",
  "validUntil": "2026-09-21T10:02:00.000+09:00"
}
```

## 노선별 위치 항목

`snapshotType`은 `SUBWAY_POSITIONS_BY_LINE`이며 최대 512개를 저장한다.

```json
{
  "trainId": "T1",
  "stationId": "0201",
  "stationName": "시청",
  "direction": "내선",
  "destination": "성수",
  "trainStatusCode": "0",
  "sourceGeneratedAt": "2026-09-21 10:00:00",
  "occurredAt": "2026-09-21T10:00:00.000+09:00",
  "collectedAt": "2026-09-21T10:00:05.000+09:00",
  "eventId": "event-id",
  "validUntil": "2026-09-21T10:02:00.000+09:00"
}
```

## 쓰기 규칙

Spark는 같은 micro-batch에서 provider key별 최신 이벤트를 고른 뒤 Snapshot 행을 만든다.
Redis Lua 연산은 기존의 아직 유효한 항목과 새 항목을 `trainId`별로 원자 병합한다.
`occurredAt`, `collectedAt` 순으로 더 최신인 항목만 같은 열차를 교체하므로 늦게 도착한 과거
이벤트가 현재값이나 TTL을 되돌리지 않는다. 이 방식은 Kafka partition 또는 Spark trigger가
한 API 수집 회차를 여러 조각으로 나누는 경우에도 다른 열차를 지우지 않는다.

전용 Subway Silver HDFS 적재는 이 조회 계약의 범위가 아니다. 원본 Kafka/HDFS 보존 정책과
무관하게 서비스 조회는 위 Redis Snapshot을 사용한다.
