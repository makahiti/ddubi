# 일정 이동 변수 조회 계약

일정 상세 화면은 다음 API로 현재 선택 경로와 목적지에 해당하는 최신 이동 변수를 조회한다.

```http
GET /api/v1/schedules/{scheduleId}/conditions
Authorization: Bearer <access-token>
```

## 응답 원칙

- 네 항목은 항상 존재하며 각 항목의 `available`로 사용 가능 여부를 구분한다.
- Redis 자료가 없거나 `validUntil`이 지났거나 파싱에 실패하면 `available=false`와 `reason`을 반환한다.
- 결측을 `0`, `맑음`, `정상`, `지연 없음`으로 대체하지 않는다.
- 루트 `generatedAt`은 API 생성시각이고 `observedAt`은 사용 가능한 자료 중 가장 최근 관측시각이다.
- 항목별 `observedAt`과 `validUntil`이 실제 자료의 시각이다.
- 한 자료의 Redis 조회 실패가 다른 항목까지 실패시키지 않는다.

## 선택 범위

| 항목 | 선택 기준 | Redis 계약 |
|---|---|---|
| 날씨 | 일정 목적지 WGS84 좌표를 기상청 5km 격자로 변환 | `weather:latest:{nx}:{ny}` |
| 혼잡도 | 선택 경로의 BUS canonical segment를 활성 도시영역 대응표로 변환 | `ml:mapping:bus-city:active:{masterVersion}` → `city:latest:area:{providerAreaId}` |
| 도로 통제 | 선택 경로의 BUS canonical segment를 검증된 TOPIS 링크로 변환 | `ml:mapping:bus-road:active:{masterVersion}` → `road:incidents:{namespace}:{linkId}` |
| 지하철 | 선택 경로의 SUBWAY 노선명과 활성 공지의 노선명이 일치 | `transit:latest:subway-alert:{providerAlertId}` |

지하철 공지는 현재 호선별 인덱스가 없어 제한된 Redis `SCAN`을 사용한다. 데이터 파이프라인이 호선별 snapshot/index를 제공하면 이 조회를 단일 key GET으로 교체한다.

## 응답 예시

```json
{
  "success": true,
  "data": {
    "schemaVersion": "movement-conditions-v1",
    "scheduleId": 27,
    "generatedAt": "2026-09-22T02:00:00Z",
    "observedAt": "2026-09-22T01:58:00Z",
    "weather": {
      "available": true,
      "nx": 60,
      "ny": 127,
      "districts": ["종로구", "중구"],
      "temperatureC": 20.9,
      "precipitationMm": 0.0,
      "observedAt": "2026-09-22T01:00:00Z",
      "validUntil": "2026-09-22T03:00:00Z",
      "source": "kma.ultra-srt-ncst"
    },
    "congestion": {
      "available": true,
      "areaId": "POI001",
      "areaName": "시청",
      "level": "BUSY",
      "score": 3,
      "observedAt": "2026-09-22T01:55:00Z",
      "validUntil": "2026-09-22T02:15:00Z",
      "source": "seoul.citydata"
    },
    "roadBlock": {
      "available": true,
      "count": 1,
      "summary": "봉은교 양방향 전면통제",
      "incidents": [{"incidentId": "1071788", "incidentType": "A04", "message": "봉은교 양방향 전면통제"}],
      "source": "seoul.topis"
    },
    "subwayDelay": {
      "available": false,
      "reason": "NO_MATCHING_ACTIVE_ALERT",
      "count": 0,
      "routeLines": ["2호선"],
      "alerts": []
    }
  }
}
```

`NO_MATCHING_ACTIVE_ALERT`는 “지연 없음”을 뜻하지 않는다. 현재 선택 호선에 연결할 수 있는 유효한 공지를 확인하지 못했다는 뜻이다.
