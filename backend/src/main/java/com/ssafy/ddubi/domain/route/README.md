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

## 5. 서울 공식 마스터 매핑

BUS/SUBWAY 구간은 카카오가 준 노선명, 승·하차명과 통과 순서를 공식 마스터에 대조합니다.
BUS는 path 양 끝이 공식 승·하차 정류장의 설정 반경 안에 드는지도 확인합니다. 좌표가 없거나
공식 좌표가 없으면 이름이 같아도 `PARTIAL`이며, 결과가 하나로 확정된 `EXACT`만 실제 인접
정류장·역 목록을 반환합니다.
`PARTIAL`, `AMBIGUOUS`, `UNMAPPED`는 임의 후보를 선택하지 않고 실패 이유를 응답에 남깁니다.

현재 저장소에는 매칭 알고리즘, 실제 경로 변환 연결과 MySQL 읽기 어댑터가 들어 있습니다.
마스터 데이터는 다음 순서로 교체합니다.

1. `seoul_transit_master_version`에 새 버전을 `STAGING`으로 생성합니다.
2. 같은 `data_version`으로 버스·지하철 마스터를 적재하고 필수값·참조·중복 순번·최소 경로를 검증합니다.
3. 새 버전을 `ACTIVE`로 바꾸고 이전 버전을 `RETIRED`로 바꿉니다.
4. `pipeline_config`의 `seoul_transit_master_active_version` 값을 새 버전으로 교체합니다.
5. 적재 Runner가 커밋 후 Matcher의 메모리 인덱스를 새 스냅샷으로 원자 교체합니다.

활성 포인터가 없거나 잘못된 버전을 가리키면 마스터를 섞어 읽지 않고 모든 대중교통
step이 `UNMAPPED`로 반환됩니다.

로컬 공식 파일을 실제 DB에 적재할 때는 Backend를 다음 환경변수와 함께 한 번 기동합니다.

```text
DDUBI_TRANSIT_MASTER_IMPORT_ENABLED=true
DDUBI_TRANSIT_MASTER_DATA_VERSION=2026-08-04_2026-06-16
DDUBI_TRANSIT_MASTER_BUS_WORKBOOK=<서울시버스노선별정류소정보 XLSX 절대경로>
DDUBI_TRANSIT_MASTER_SUBWAY_TIMETABLE=<서울 도시철도 열차운행시각표 CSV 절대경로>
DDUBI_TRANSIT_MASTER_SUBWAY_REALTIME_STATIONS=<정적 코드와 statnId 대응 JSON 경로, 선택>
DDUBI_TRANSIT_MASTER_ALIASES=<검증된 카카오 별칭 JSON 경로, 선택>
```

실시간 대응 JSON의 각 행은 `lineNumber`, `subwayId`, `staticStationCode`, `externalCode`,
`realtimeStationId`를 사용합니다. 대응 파일이 없으면 정적 코드를 보존하고 실시간 ID를
추측해 만들지 않습니다.

별칭 JSON은 `provider`, `mode`, `aliasType`, `aliasName`, `canonicalId`, `canonicalName`,
`verified` 필드를 사용합니다. 적재기는 `canonicalId`에서 공식 이름을 다시 조회해 입력
`canonicalName`과 일치하는지 확인합니다. Matcher는 이름 치환 결과가 아니라 검증된
`aliasName → canonicalId 후보 집합`을 직접 사용하며 `verified=true`인 행만 읽습니다.
환승역이나 동명 정류장처럼 같은 별칭이 노선별 ID 여러 개를 가리킬 수 있으며, Matcher는
현재 노선/호선 topology 안에 존재하는 ID만 남깁니다. 후보가 둘 이상이면 임의 선택하지 않습니다.

일반 기동에서는 `DDUBI_TRANSIT_MASTER_IMPORT_ENABLED=false`를 유지합니다. 같은 버전은
중복 실행하지 않으며, 새 파일은 새 `data-version`으로 적재합니다. 지하철은 급행 정차역을
물리적 인접역으로 오인하지 않도록 일반 열차만 사용하고, 본선·지선·단축운행을 서로 다른
서비스 패턴으로 저장합니다.

현재 적재기는 내부 일관성을 검사하지만, 원본이 통째로 잘렸는지 확인하는 기대 행 수·SHA-256
manifest는 아직 입력받지 않습니다. 운영 자동 활성화 전에는 공식 배포 파일의 체크섬과 기대
건수를 별도 검증해야 합니다.

별칭은 `transit_alias`에 넣고 검증된 행만 `verified=true`로 설정합니다. 실제 카카오 구간의
판정 결과는 BUS endpoint 좌표, 활성 마스터 버전, 좌표 허용 반경·별칭을 포함한 정책
fingerprint를 키로 `provider_segment_mapping`에 저장합니다. EXACT만 재사용합니다.
`active=false`는 해당 캐시 재사용만 중단하고, 사람이 오판으로 확정한 `REJECTED`는
재계산하지 않고 `UNMAPPED/MANUALLY_REJECTED`로 차단합니다.

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
상태는 아닙니다. 특히 BUS 다중 vehicles는 하나를 고르지 않고 `AMBIGUOUS`, 일부 대안만
마스터에 있으면 `PARTIAL`로 반환합니다. 지하철은 topology가 EXACT여도 실시간 대응표가 없으면
canonical segment는 유지하되 `realtimeCoverage=NONE`으로 baseline fallback 대상이 됩니다.

Collector/Spark는 서비스 MySQL을 직접 읽지 않습니다. 따라서 같은 segment ID로 실시간 데이터를
처리하려면 활성 마스터의 canonical topology와 provider crosswalk를 versioned artifact, 인증된
내부 조회 API 또는 compacted Kafka topic으로 배포하는 후속 계약이 반드시 필요합니다.
