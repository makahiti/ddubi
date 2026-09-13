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

도메인 API이므로 JWT 인증 적용 대상이다. 인증이 없는 요청은 401이며,
인증된 사용자의 일정 등록·평가·조회는 `CapturedTransitFlowIntegrationTest`로 검증한다.

## 2026-08-31 실제 응답 분석

2026-09-10 재검증: 보존된 응답 20개/후보 288개 중 287개에서 총시간이 step 합보다 컸다.
차이는 0~1,809초였으며 대기·접근·환승을 각각 식별하는 필드는 없었다.
[공식 문서](https://developers.kakao.com/docs/ko/kakaomap/rest-api)도 totalTime/time을 소요시간으로
설명하므로 이 차이를 대기시간이라고 확정하거나 새 대기를 가산할 수 없다.
두 `decomposition-verified` 설정은 false로 유지한다.
재현 도구와 결과는 [실제 응답 검증](../../backend/tools/transit-timing/README.md)을 따른다.

실제 성공 응답은 `status=OK`였고 후보 15개(`BUS` 9, `SUBWAY` 1,
`BUS_AND_SUBWAY` 5), 이동 구간 38개(`BUS` 18, `SUBWAY` 10,
`WALKING` 10)를 포함했다.

- `route.properties.totalTime`: 후보 전체 시간(초). 구간 `time` 합으로 다시 계산하지 않는다.
- `route.properties.totalDistance`: 후보 전체 거리(m). 구간 `distance` 합으로 다시 계산하지 않는다.
- 전체값과 구간 합의 차이는 구성 요소를 확정할 수 없는 잔여값이다. 대기·접근·환승 중 무엇인지 단정하지 않는다.
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
2. `BUS`는 수도권 마스터, `SUBWAY`는 서울 지하철 마스터에 대조한다. 버스는 차량 대안별로 이름·순서·승하차 좌표·중간 정류장·공식 형상을 확인한다.
3. 유일하게 확정된 `EXACT`만 승차~하차 사이의 공식 인접 정류장·역 구간을 반환한다.
4. `PARTIAL`, `AMBIGUOUS`, `UNMAPPED`는 임의 후보를 고르지 않고 실패 이유를 보존한다.
5. `EXACT`의 각 공식 인접 구간에는 `BUS:{routeId}:{fromStopId}:{toStopId}` 또는
   `SUB:{staticSubwayId}:{staticFromStationId}:{staticToStationId}` 형식의 ID를 만든다.
   지하철 실시간 `subwayId/statnId`는 canonical ID에 섞지 않고 별도 crosswalk로 제공한다.
6. BUS 캐시는 전체 원문 차량명·정류장열·전체 경로 좌표와 마스터/정책/검토일을 전용 테이블에 저장한다. SUBWAY는 이름·역 순서·지하철 마스터/별칭 정책을 기존 캐시에 저장한다. EXACT만 재사용하고 수동 `REJECTED`는 차단한다.
7. 지하철 별칭은 `transit_alias.verified=true`인 canonical ID 후보를 사용한다. 버스 수동 대응은 수도권 artifact의 검토일·전체 요청 해시·출처 경로 범위에 한정하며 과거 전역 BUS 별칭은 사용하지 않는다.
8. BUS `vehicles[]`는 독립 `busAlternatives[]`로 보존한다. 여러 차량 또는 여러 출처 후보는 상위 PARTIAL로 남기고 선택한 VERIFIED 경로의 edge만 사용한다.

지하철은 하나의 호선·방향에도 본선, 지선, 단축운행이 존재하므로 현재 운행시각표의 일반 열차
역 순서를 `service_pattern_id`별로 보존한다. 서로 다른 패턴이 동일한 승차~하차 역 경로를
제공하면 하나로 보지만, 실제 중간역 경로가 다르면 `AMBIGUOUS`로 남긴다. 급행 정차역만
이어 붙이지 않는다. 단, 시간표는 물리 topology의 최종 진실이 아니므로 공식 노선-역순서
자료 또는 수동 정답 corpus로 검증하기 전에는 운영 정확도가 입증된 것으로 보지 않는다.

기존 마스터 스키마는 V1~V3를 유지하고 수도권 버스 저장소·캐시는 새 V4로 추가한다. 기존 DB를 초기화하거나 과거 마이그레이션을 변경하지 않는다.

현재 `POST /api/v1/routes/search`는 사용자 경로 자체를 저장하지 않지만, 대중교통 step의
지하철 판정은 `provider_segment_mapping`, 버스 판정은 `metropolitan_bus_mapping_cache`에 캐시한다. 일정·후보 저장은 별도 Repository 책임이다.
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
crosswalk 레지스트리가 아니다. Collector/Spark는 같은 버전과 SHA의 별도 crosswalk artifact를
기동 시 읽고 BUS 관측을 독립 검증한다. 대응표 배포와 실시간 소요시간 연산의 운영 검증은 별개다.
# 수도권 버스 매핑 확장 (2026-09-10)

BUS 매핑은 기본적으로 수도권 마스터만 사용한다. 활성 마스터가 없으면 명시적으로 미구성 상태를 반환한다.
기존 `mapping` 결과에 `busAlternatives[]`를 추가하여 차량별 ID 확인과 공간 검증 상태를
분리한다. 공식 출처 ID를 공용 `segment_id`로 임의 변환하지 않으며 여러 차량 대안의
edge를 합치지 않는다. 단일 검증 대안/출처는 서울·경기·인천 모두 canonical-adjacent-v2의
EXACT/segments를 발급한다. 지하철과 도보의 기존 의미는 유지한다.

필드·캐시·검토일 정책은 [수도권 버스 매핑 계약](metropolitan-bus-mapping.md),
적재·활성화 방법은 [마스터 적용 안내](../../backend/transit-master/README.md)를 따른다.

응답의 `busEvaluations`와 추가 BUS 품질 집계, ML fallback, 선택 경로 저장의 내부 호출 계약은
[수도권 구간 연결 계약](metropolitan-segment-integration.md)을 따른다.
