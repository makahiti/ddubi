# route 코드 읽는 순서

이 폴더는 **출발지와 도착지를 받아 카카오에서 대중교통 경로를 가져오는 기능**입니다.
처음에는 아래 번호 순서대로 읽으면 됩니다.

## 1. 전체 흐름

```text
프론트가 좌표를 보냄
        ↓
RouteSearchController          우리 백엔드 API 입구
        ↓
RouteSearchService             요청을 내부 검색 조건으로 변경
        ↓
KakaoPublicTransitClient       카카오 서버에 HTTP 요청
        ↓
KakaoPublicTransitResponse     카카오 JSON을 Java 객체로 읽음
        ↓
KakaoRouteMapper               카카오 객체를 우리 경로 모델로 변경
        ↓
RouteCandidate                 후보 경로 하나
        ↓
RouteSegment                   후보를 구성하는 버스·지하철·도보 구간
        ↓
RouteSearchResponse            프론트에 공개할 JSON 생성
```

## 2. 파일별 역할

| 폴더 | 파일 | 쉬운 설명 |
| --- | --- | --- |
| `controller` | `RouteSearchController` | 프론트 요청을 처음 받는 문 |
| `dto` | `RouteSearchRequest` | 프론트가 보내야 하는 좌표 JSON |
| `dto` | `RouteSearchResponse` | 프론트에 돌려주는 경로 JSON |
| `service` | `RouteSearchService` | Controller와 카카오 Client를 연결하는 중간 담당자 |
| `client` | `PublicTransitRouteClient` | 길찾기 업체가 지켜야 하는 공통 약속 |
| `client` | `KakaoPublicTransitClient` | 인증 헤더와 좌표를 넣어 카카오 API를 실제 호출 |
| `client/dto` | `KakaoPublicTransitResponse` | 카카오 응답 JSON 모양을 그대로 표현 |
| `service` | `KakaoRouteMapper` | 카카오 응답을 우리 모델로 번역 |
| `model` | `RouteCandidate` | 사용자가 선택할 수 있는 후보 경로 하나 |
| `model` | `RouteSegment` | 후보 안의 버스·지하철·도보 구간 하나 |
| `mapping` | `CanonicalSegmentIdFactory` | 공식 인접 구간을 Spark·Redis 공통 ID로 직렬화 |
| `mapping` | `ProviderSegmentMappingStore` | 실제 등장한 카카오 구간의 버전별 판정 캐시 |
| `service` | `TransitMappingResolver` | 카카오 이름·순서·좌표를 서울 공식 마스터에 대조하는 경계 |
| `service` | `MasterDataTransitMappingResolver` | 버스·지하철 Matcher를 실제 검색 흐름에서 실행하는 구현 |
| `mapping` | `SeoulTransitMasterDataProvider` | MySQL·파일 등 마스터 저장 위치를 감추는 읽기 계약 |

## 3. 헷갈리기 쉬운 용어

- `RouteCandidate`: “이 길로 갈 수 있어요”라고 보여주는 전체 후보 하나입니다.
- `RouteSegment`: 후보 안에 들어 있는 작은 이동 조각입니다.
  예를 들어 `지하철 → 도보 → 버스`라면 Segment가 3개입니다.
- `providerCandidateIndex`: 카카오가 보내준 후보 배열의 원래 순번입니다.
- `providerRouteKey`: 버스 번호나 지하철 노선 이름입니다. 우리 공통 ID는 아닙니다.
- `segmentId`: 수집기·Spark·Redis·백엔드가 함께 사용할 공통 구간 ID입니다.
- `unassignedDurationSeconds`: 전체 시간에는 포함되지만 별도 Segment로 나오지 않은 대기·환승 등의 시간입니다.

## 4. 왜 바로 MySQL에 저장하지 않나요?

현재 `/api/v1/routes/search`는 경로를 **조회만 하는 API**입니다. 검색할 때마다 저장하면 필요 없는
경로가 계속 쌓이고 API 호출 기록도 많아집니다. 일정 시작 전 재계산처럼 정말 필요한 순간에만
별도의 저장 기능이 `route_request`, `route`, `route_segment`를 저장하도록 분리할 예정입니다.

## 5. 수도권 버스·서울 지하철 마스터 매핑

BUS는 `MetropolitanBusMatcher`와 전용 마스터·캐시를 항상 사용합니다. 차량 대안마다
공식 노선·정류장 ID 확인과 공간 검증을 구분하여 `mapping.busAlternatives`로 반환합니다.
활성 마스터가 없으면 `UNMAPPED/MASTER_DATA_NOT_CONFIGURED`이며 과거 서울 버스 코드로 우회하지 않습니다.
통합 파일과 관리 적재 방법은 `backend/transit-master/README.md`, 응답 계약은
`docs/contracts/metropolitan-bus-mapping.md`를 따릅니다.

SUBWAY는 기존 서울 지하철 테이블과 활성 포인터를 사용합니다. 지하철 관리 실행에서는
시간표 CSV를 검증하고 STAGING 적재를 마친 뒤 ACTIVE 포인터를 원자적으로 교체합니다.
버스 데이터와 별도 버전이므로 한쪽 마스터의 미구성·갱신이 다른 쪽의 매핑을 비우지 않습니다.

```text
DDUBI_TRANSIT_MASTER_IMPORT_ENABLED=true
DDUBI_TRANSIT_MASTER_DATA_VERSION=<새 지하철 버전>
DDUBI_TRANSIT_MASTER_SUBWAY_TIMETABLE=<서울 도시철도 열차운행시각표 CSV 절대경로>
DDUBI_TRANSIT_MASTER_SUBWAY_REALTIME_STATIONS=<정적 코드와 statnId 대응 JSON 경로, 선택>
DDUBI_TRANSIT_MASTER_ALIASES=<검증된 지하철 별칭 JSON 경로, 선택>
```

실시간 대응 JSON의 `lineNumber`, `subwayId`, `staticStationCode`, `externalCode`,
`realtimeStationId`를 검증합니다. 파일이 없으면 정적 코드만 보존하고 실시간 ID를 추측하지 않습니다.
지하철 별칭은 `mode=SUBWAY`, `aliasType=LINE|STATION`만 허용하며 canonical ID와 이름을
새 마스터에서 역조회합니다. 버스 별칭은 수도권 artifact의 검토 범위를 사용합니다.

관리 기동 후 `DDUBI_TRANSIT_MASTER_IMPORT_ENABLED=false`로 되돌립니다. 지하철의 급행은
인접역으로 오인하지 않도록 제외하고, 본선·지선·단축운행은 독립 서비스 패턴으로 보존합니다.
지하철 파일의 기대 행 수·체크섬 검증은 별도 운영 절차입니다. 지하철 캐시는 마스터·별칭
정책별 EXACT만 재사용하며 수동 REJECTED는 재계산하지 않습니다.

## 6. segment_id 사용 위치

카카오 공개 대중교통 응답에는 안정적인 정류장·노선 ID가 없으므로 이름 자체를 ID로 사용하지 않습니다.
`EXACT`의 공식 인접 구간은 `BUS:{routeId}:{from}:{to}` 또는
`SUB:{staticSubwayId}:{staticFrom}:{staticTo}`를 발급합니다. 지하철 실시간 ID는 별도 필드이며
`realtimeCoverage=NONE|PARTIAL|FULL`로 사용 가능 범위를 확인합니다. 여러 정거장을 지나는
카카오 step은 단일 구간이 아니므로 상위 `segmentId`는 비우고
`mapping.segments[].segmentId`를 사용합니다. 저장 구현은 `route_segment_edge`에 이 목록을
항상 1:N으로 기록해야 합니다.

## 7. 지금 무엇까지 믿을 수 있나요?

현재 코드는 잘못된 `EXACT`를 줄이기 위한 구조와 회귀 테스트까지 갖춘 상태입니다. 하지만
실제 카카오 응답에 사람이 정답 ID를 붙인 golden corpus가 없으므로 운영 정확도가 입증된
상태는 아닙니다. BUS 다중 vehicles는 대안별 상태를 보존하고 상위 `PARTIAL`로 반환합니다. 지하철은 topology가 EXACT여도 실시간 대응표가 없으면
canonical segment는 유지하되 `realtimeCoverage=NONE`으로 baseline fallback 대상이 됩니다.

Collector/Spark는 서비스 MySQL을 직접 읽지 않습니다. 버전 고정 crosswalk artifact와 SHA를
동일하게 배포하여 BUS 관측을 검증합니다. 경기·인천의 ID 구성요소에는 출처 접두사가 붙습니다.
설정과 저장/예측 경계는 `docs/contracts/metropolitan-segment-integration.md`를 따릅니다.
