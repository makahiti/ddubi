# 카카오 대중교통 경로 응답 계약

## 호출 규격

- `GET https://dapi.kakao.com/v2/routing/publictraffic`
- 헤더: `Authorization: KakaoAK ${KAKAO_REST_API_KEY}`
- 필수 좌표: `start_x`, `start_y`, `end_x`, `end_y`
- 좌표 순서: 경도(x), 위도(y), WGS84
- API 키는 환경변수로만 주입하고 원문 응답이나 키를 사용자 응답·로그에 노출하지 않는다.

백엔드에서는 다음 API로 호출한다.

```http
POST /api/v1/routes/search
Content-Type: application/json
Authorization: Bearer {accessToken}

{
  "startLongitude": 127.11119217,
  "startLatitude": 37.39477123,
  "endLongitude": 127.12628814,
  "endLatitude": 37.41993056
}
```

도메인 API이므로 인증 적용 대상이다. JWT 인증 필터가 완성되기 전에는 통합 실행 시 401이
정상이며, Controller 단위 테스트나 Service 테스트로 경로 검색 흐름을 검증한다.

## 2026-08-31 실제 응답 분석

실제 성공 응답은 `status=OK`였고 후보 15개(`BUS` 9, `SUBWAY` 1,
`BUS_AND_SUBWAY` 5), 이동 구간 38개(`BUS` 18, `SUBWAY` 10,
`WALKING` 10)를 포함했다.

- `route.properties.totalTime`: 후보 전체 시간(초). 구간 `time` 합으로 다시 계산하지 않는다.
- `route.properties.totalDistance`: 후보 전체 거리(m). 구간 `distance` 합으로 다시 계산하지 않는다.
- 전체값과 구간 합의 차이는 환승·대기·접근 시간 등 응답에 독립 step으로 표현되지 않은 값이다.
  모델의 `unassignedDurationSeconds`, `unassignedDistanceMeters`에 보존한다.
- `fare`는 단일 요금이면 `value`, 범위면 `min/max` 형태다. 내부에서는 둘 다 최소/최대 요금으로 정규화한다.
- 카카오의 후보 배열 순서는 최단 시간순이 아니므로 `providerCandidateIndex`를 보존한다.
- `path.points`의 한 점은 `[경도, 위도]`다.

## 내부 모델 매핑

| 카카오 필드 | 내부 모델 | 기준 |
| --- | --- | --- |
| `routes[]` | `RouteCandidate` | 배열 위치를 `providerCandidateIndex`로 저장 |
| `steps[].properties.type=BUS` | `RouteSegment(BUS)` | 버스 구간 |
| `steps[].properties.type=SUBWAY` | `RouteSegment(SUBWAY)` | 지하철 구간 |
| `steps[].properties.type=WALKING` | `RouteSegment(WALK)` | 도보 구간, `segmentId=null` |
| `stops[0]`, `stops[last]` | `fromName`, `toName` | 정류장/역 이름 |
| `vehicles[].name` | `providerRouteKey` | 안정 ID가 아닌 매핑 근거 문자열 |
| `path.points` | `geometry` | `Coordinate(longitude, latitude)` |

API/도메인 모델은 초와 m를 사용한다. 현재 DB의 `base_duration_min`에 저장하는 단계에서
올림 또는 반올림 정책을 별도로 확정해야 하며, 이번 티켓에서는 정보 손실을 막기 위해 초를 유지한다.

## 실패·빈 응답 처리

| 상황 | 내부 에러 코드 | HTTP |
| --- | --- | --- |
| `NO_RESULTS`, `STARTNODES_NULL`, `ENDNODES_NULL` | `ROUTE_NOT_AVAILABLE` | 422 |
| `EQUAL_POINTS`, `INVALID_REQUEST` | `INVALID_REQUEST` | 400 |
| HTTP 429 또는 카카오 오류 `code=-10` | `RATE_LIMITED` | 429 |
| `OK`지만 `routes`가 없거나 빈 배열 | `ROUTE_NOT_AVAILABLE` | 422 |
| 타임아웃, 네트워크 오류, 기타 4xx/5xx, 깨진 필수 필드 | `ROUTE_PROVIDER_UNAVAILABLE` | 503 |

## `segment_id` 매핑 결론

현재 카카오맵 대중교통 REST 응답은 정류장 이름과 차량 이름은 주지만 안정적인 정류장·노선 ID는
주지 않는다. 따라서 `BUS:{노선명}:{정류장명}`처럼 이름을 이어 붙여 즉석에서 ID를 만들면
이름 변경·동명이 정류장 때문에 파이프라인 키가 깨질 수 있다.

1. `WALK`는 기존 계약대로 `segment_id=null`이다.
2. `BUS`, `SUBWAY`는 `SegmentIdResolver`에만 매핑을 위임한다.
3. 공통 ID 계약과 외부 정류장/노선 매핑 테이블이 확정되기 전 기본 구현은 빈 값을 반환한다.
4. 수집기·Spark·Redis·백엔드가 같은 ID 계약을 확정한 뒤 Resolver 구현만 교체한다.

`V2__allow_kakao_route_provider.sql`에서 기존 ODSAY 데이터 호환성을 유지하면서
`route_request.provider`에 `KAKAO`를 추가하고 신규 기본값을 카카오로 변경한다.

현재 `POST /api/v1/routes/search`는 검색 결과를 자동 저장하지 않는 무상태 API다.
일정 생성·재계산 흐름에서 원문 보관이 필요할 때만 Repository를 호출하도록 별도 구현한다.
