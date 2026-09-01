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
| `service` | `SegmentIdResolver` | 외부 구간과 Spark·Redis 공통 ID를 연결할 자리 |

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

## 5. 현재 아직 비어 있는 부분

카카오 공개 대중교통 응답에는 안정적인 정류장·노선 ID가 없습니다. 그래서 이름을 조합해 임의의
`segmentId`를 만들지 않았습니다. 팀에서 수집기·Spark·Redis의 공통 ID 규칙을 확정하면
`SegmentIdResolver` 구현만 교체하면 됩니다.
