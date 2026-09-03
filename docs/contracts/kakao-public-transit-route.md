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

1. `WALK`는 기존 계약대로 `segment_id=null`이며 서울 교통 마스터 매핑 대상이 아니다.
2. `BUS`, `SUBWAY`는 이름과 통과 순서를 활성 서울 마스터 버전에 대조한다. BUS는 여기에
   path 첫·끝과 공식 승·하차 정류장의 절대거리 및 승차→하차 방향 배정 검증까지 필수로 적용한다.
3. 유일하게 확정된 `EXACT`만 승차~하차 사이의 공식 인접 정류장·역 구간을 반환한다.
4. `PARTIAL`, `AMBIGUOUS`, `UNMAPPED`는 임의 후보를 고르지 않고 실패 이유를 보존한다.
5. `EXACT`의 각 공식 인접 구간에는 `BUS:{routeId}:{fromStopId}:{toStopId}` 또는
   `SUB:{staticSubwayId}:{staticFromStationId}:{staticToStationId}` 형식의 ID를 만든다.
   지하철 실시간 `subwayId/statnId`는 canonical ID에 섞지 않고 별도 crosswalk로 제공한다.
6. 실제 등장한 카카오 구간은 교통수단·이름·전체 통과 순서와 BUS path 양 끝 좌표의
   SHA-256, 활성 마스터 버전, 좌표 허용 반경·별칭을 포함한 매핑 정책 fingerprint로
   캐시한다. EXACT만 재사용하며 수동 `REJECTED`는 재계산하지 않고 차단한다.
7. 별칭은 `transit_alias.verified=true`인 행만 적용하며, 문자열을 공식 이름으로 바꾸지 않고
   `canonical_id` 후보 집합을 직접 제한한다. 환승역·동명 정류장처럼 같은 별칭이 여러
   노선별 ID를 가리키면 현재 노선/호선 topology 안에서만 후보를 남긴다.
8. BUS `vehicles[]`가 서로 다른 노선 대안을 뜻하면 단일 segment로 축소하지 않는다. 현재
   MVP 응답 모델에서는 `AMBIGUOUS` 또는 마스터 일부 누락 시 `PARTIAL`로 남긴다.

지하철은 하나의 호선·방향에도 본선, 지선, 단축운행이 존재하므로 현재 운행시각표의 일반 열차
역 순서를 `service_pattern_id`별로 보존한다. 서로 다른 패턴이 동일한 승차~하차 역 경로를
제공하면 하나로 보지만, 실제 중간역 경로가 다르면 `AMBIGUOUS`로 남긴다. 급행 정차역만
이어 붙이지 않는다. 단, 시간표는 물리 topology의 최종 진실이 아니므로 공식 노선-역순서
자료 또는 수동 정답 corpus로 검증하기 전에는 운영 정확도가 입증된 것으로 보지 않는다.

개발 초기 마이그레이션 정책에 따라 `V1__init.sql`에 기존 ODSAY 호환성과 KAKAO 기본값,
서울 교통 마스터 테이블을 함께 정의한다. 기존 V1 적용 DB는 초기화한 뒤 다시 생성해야 한다.

현재 `POST /api/v1/routes/search`는 사용자 경로 자체를 저장하지 않지만, 대중교통 step의
매핑 판정은 `provider_segment_mapping`에 캐시한다. 일정·후보 저장은 별도 Repository 책임이다.
향후 저장 시 provider step은 `route_segment`, 그 안의 모든 공식 인접 구간은
`route_segment_edge`에 1:N으로 저장한다. 여러 정거장을 지나는 정상 step의 edge를 JSON에만
남기거나 상위 `segment_id=NULL`만 저장하면 영향 일정 역조회에서 누락되므로 허용하지 않는다.

## 현재 검증 수준과 완료 조건

현재 테스트는 정규화·좌표 반경과 방향·분기·다중 vehicles·다중 ID 별칭·캐시 정책 변경과
수동 거절·정적/실시간 ID 분리를 검증한다.
이는 알고리즘 회귀를 막는 단위 검증이지 실제 카카오 구간의 정답 검증은 아니다. 다음 지표가
버전 고정 golden corpus에서 측정되기 전에는 “전체 매핑 완료”라고 표현하지 않는다.

- 수동 정답 대비 `EXACT precision`(가장 우선), `EXACT coverage`
- `PARTIAL`, `AMBIGUOUS`, `UNMAPPED` 비율과 실패 이유별 건수
- BUS path endpoint와 공식 정류장 사이 거리 분포 및 허용 반경 초과율
- 서울/비서울, 순환·지선·동명 정류장·다중 vehicles별 결과
- 지하철 topology EXACT와 실시간 crosswalk `NONE/PARTIAL/FULL` 비율

또한 `provider_segment_mapping`은 카카오 요청 판정 캐시이지 Collector/Spark용 공용
crosswalk 레지스트리가 아니다. Collector/Spark가 같은 버전의 canonical topology와 실시간
provider ID 대응표를 받는 인증 API·versioned artifact·compacted topic 중 하나가 별도 확정되어야
end-to-end 실시간 보정이 성립한다.
