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


## 혼잡도의 유효시각 예외 (2026-09-23)

도시데이터는 5분마다 수집하고 관측 유효시간은 15분이다. 정상 수집 중에는 `validUntil` 안의
최신값을 사용한다. 수집 장애로 최신값이 하나도 없을 때만
`ddubi.movement-conditions.congestion-max-age-hours`(기본 12시간) 안의 마지막 관측을 쓰되
응답에 `stale: true` 와 `observedAt` 을 함께 준다. 앱은 "HH:MM 기준 · 최신 아님" 을 덧붙인다.

옛 값을 지금 값처럼 보여 주지 않는다는 원칙은 표기로 지킨다. 상한을 넘거나 관측 시각이 미래면
쓰지 않고 `CONGESTION_UNAVAILABLE_OR_STALE` 로 남긴다. 날씨에는 이 예외가 없다.

## 도로 사고 설명과 지하철 공지의 유효시각 (2026-09-24)

수집 자료의 `validUntil` 이 두 곳에서 "지금도 유효한가" 를 말해 주지 못했다. 둘 다 `occurredAt + 10분` 으로 저장된다.

- **도로 사고 상세**(`traffic:latest:incident:{id}`): `occurredAt` 이 사고 **시작** 시각이라 몇 달째 이어지는 공사는
  설명이 늘 만료였다. 그래서 도로 칸에 실제 내용 대신 "선택 경로에 도로 돌발정보가 확인됐어요" 만 나갔다. 사고가 지금
  있는지는 사고 목록(`road:incidents:*`, 사고 스냅샷)으로 따로 확인하므로, 상세는 `validUntil` 이 남았거나
  `collectedAt` 이 1시간 안이면 설명으로 쓴다. 추천 안내의 원인 설명도 같다.
- **지하철 공지**: 공지에는 끝나는 시각이 없다. 올린 시각(`occurredAt`)부터 2시간 동안 유효로 보되, 같은 호선(제목에
  역이 있으면 같은 역)에 "정상운행·운행재개" 공지가 뒤에 오면 그때 끝낸다. 그런 공지 자체는 따로 보여주지 않는다.
  예전 규칙(올린 뒤 10분)으로는 2026-09-22 2호선 이대역 무정차(20:27 공지, 22:19 운행재개) 동안 20:37 부터
  "확인된 공지 없음" 이었다. 응답의 공지별 `validUntil` 은 올린 시각 + 2시간이다. 수집기가 공지 목록 전체(몇 년 치)를
  매번 다시 받으므로 `collectedAt` 으로는 현재 여부를 판단하지 않는다. 한 번에 읽는 공지 수 상한은 3,000 건이다.

`NO_MATCHING_ACTIVE_ALERT`는 “지연 없음”을 뜻하지 않는다. 현재 선택 호선에 연결할 수 있는 유효한 공지를 확인하지 못했다는 뜻이다.
