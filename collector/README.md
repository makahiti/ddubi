# DDUBI 실시간 데이터 Collector

서울시·공공데이터포털·기상청 API를 주기적으로 호출하고, 응답을 공통 JSON 이벤트로
변환해 Kafka에 발행하는 서비스다. Collector는 외부 API 수집과 Kafka 발행까지만 담당한다.
Kafka 이후 원본 장기 보관은 Archiver/HDFS가, 서비스용 최신 상태 처리는 Spark/Redis가 담당한다.

```text
외부 실시간 API
    ↓
Collector: 호출 · 응답 검증 · 공통 이벤트 변환
    ↓
Kafka: transport.raw / city.raw
    ├─ Archiver → HDFS
    └─ Spark → Redis
```

## 수집 중인 데이터

| 분류 | `COLLECTOR_SOURCE` | 수집 내용 | 기본 범위 | 기본 주기 | 하루 계획 호출 | Kafka 토픽 |
|---|---|---|---:|---:|---:|---|
| 버스 도착 | `bus_arrival` | 정류장별 첫 번째·두 번째 버스 도착예정 | 노선 100개 | 8시간 | 300 | `transport.raw` |
| 버스 위치 | `bus_location` | 노선에서 운행 중인 차량 위치 | 노선 100개 | 8시간 | 300 | `transport.raw` |
| 지하철 도착 | `subway_arrival` | 서울 전체 역의 열차 도착예정 | 전체 역 | 6분 | 최대 480 | `transport.raw` |
| 지하철 위치 | `subway_location` | 현재 운행 중인 열차 위치 | 16개 호선 | 1시간 | 384 | `transport.raw` |
| 지하철 알림 | `subway_alert` | 지연·무정차·운행 변경 알림 | 최근 1,000행 | 15분 | 96 | `transport.raw` |
| 실시간 도시 | `city_realtime` | 인구·교통·주차·따릉이·기상·행사 | 장소 121개 | 6시간 | 484 | `city.raw` |
| 교통 돌발 | `traffic_incident` | 사고·공사·도로 통제 | 서울시 전역 | 15분 | 보통 96 | `city.raw` |
| TOPIS 공지 | `topis_notice` | 교통통제·버스 우회·교통 공지 | 최근 1,000행 | 15분 | 96 | `city.raw` |
| 날씨 | `weather` | 기온·강수·습도·바람 등 초단기실황 | 서울 격자 14개 | 10분 | 2,016 | `city.raw` |

버스는 전체 723개 노선 ID를 `config/bus_route_ids.json`에 보존하지만, 호출 한도를 위해
기본적으로 앞의 100개만 수집한다. 도착과 위치를 합해 하루 약 600회 호출한다.

API별 요청·응답 필드와 데이터 의미는
[실시간 데이터 수집 가이드](../docs/data-collection-guide.md)에서 확인한다.

## 환경변수

실제 키는 `.env`에만 저장하고 Git에 커밋하지 않는다. `.env.example`에는 변수명과 설명만
유지한다.

### API 키

| 환경변수 | 사용 수집기 | 발급처 |
|---|---|---|
| `BUS_ARRIVAL_API_KEY` | 버스 도착 | 공공데이터포털 |
| `BUS_LOCATION_API_KEY` | 버스 위치 | 공공데이터포털 |
| `SUBWAY_API_KEY` | 지하철 도착·위치 | 서울 열린데이터광장 실시간 지하철 인증키 |
| `SUBWAY_ALERT_API_KEY` | 지하철 알림 | 서울 열린데이터광장 일반인증키 |
| `CITY_REALTIME_API_KEY` | 실시간 도시데이터 | 서울 열린데이터광장 일반인증키 |
| `TOPIS_NOTICE_API_KEY` | TOPIS 공지 | 서울 열린데이터광장 일반인증키 |
| `TRAFFIC_INCIDENT_API_KEY` | 교통 돌발 | 서울 열린데이터광장 일반인증키 |
| `KMA_API_KEY` | 날씨 | 기상청 API 허브 |
| `DATA_GO_KR_API_KEY` | 날씨 대체 경로 | 공공데이터포털 |

날씨 키가 둘 다 있으면 `KMA_API_KEY`를 우선 사용한다.

### 공통 설정

| 환경변수 | 의미 | 기본값 |
|---|---|---|
| `COLLECTOR_SOURCE` | 실행할 수집기 이름 | 필수 |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka 접속 주소 | 필수 |
| `COLLECTOR_RUN_ONCE` | `true`면 한 사이클만 실행 | `false` |
| `COLLECTOR_INTERVAL_SECONDS` | 수집 주기 재정의 | 수집기별 기본값 |
| `API_DAILY_LIMIT` | 비기상 인증키별 최근 24시간 호출 한도 | `900` |
| `API_BUDGET_PATH` | 호출량 기록 SQLite 경로 | `data/request_budget.sqlite3` |

비기상 수집기는 사용자가 주기를 더 짧게 입력해도 표의 기본 안전 주기보다 짧아지지 않는다.

### 수집 범위 설정

| 환경변수 | 의미 |
|---|---|
| `BUS_ROUTE_LIMIT` | 버스 기본 노선 수. 안전을 위해 최대 100 |
| `BUS_ROUTE_IDS` | 직접 선택할 버스 노선 ID 목록(쉼표 구분) |
| `SUBWAY_LINES` | 조회할 지하철 호선 목록(쉼표 구분) |
| `CITY_AREA_IDENTIFIERS` | 조회할 도시데이터 장소 코드·이름 목록 |
| `CITY_AREA_REGISTRY_PATH` | 도시데이터 장소 Registry 교체 경로 |
| `WEATHER_GRID_POINTS` | 날씨 수집 대상 `지역명:nx:ny` 목록 |

전체 환경변수 템플릿은 [collector/.env.example](.env.example)과
[서버용 infra/.env.example](../infra/.env.example)을 참고한다.

## 환경설정 파일 구분

| 실행 방식 | 설정 파일 | 설명 |
|---|---|---|
| Python 수집기 직접 실행 | `collector/.env` | API 응답만 점검하거나 JSON 파일로 저장할 때 사용 |
| Docker Compose 서버 실행 | `infra/.env` | Compose가 키를 각 Collector 컨테이너에 환경변수로 전달 |

`collector/.env`는 개별 수집기 파일이 직접 읽지만, 서버 진입점인 `src/main.py`는 파일을
직접 읽지 않고 프로세스 환경변수를 사용한다. 따라서 서버에서는 반드시 `infra/.env` 또는
배포 시스템의 비밀 환경변수에 키를 설정해야 한다.

```powershell
Copy-Item collector/.env.example collector/.env
Copy-Item infra/.env.example infra/.env
```

## 서버 실행

저장소의 `infra` 디렉터리에서 실행한다.

```powershell
cd infra
docker compose --profile core --profile collect up -d collector-bus-arrival
```

한 번만 실행해 API → Kafka 흐름을 확인하려면:

```powershell
docker compose --profile core --profile collect run --rm `
  -e COLLECTOR_RUN_ONCE=true collector-bus-arrival
```

주요 서비스 이름은 다음과 같다.

| Compose 서비스 | 수집기 |
|---|---|
| `collector-bus-arrival` | 버스 도착 |
| `collector-bus-location` | 버스 위치 |
| `collector-subway-arrival` | 지하철 도착 |
| `collector-subway-location` | 지하철 위치 |
| `collector-subway-alert` | 지하철 알림 |
| `collector-city-realtime` | 실시간 도시데이터 |
| `collector-traffic-incident` | 교통 돌발 |
| `collector-topis-notice` | TOPIS 공지 |
| `collector-weather` | 날씨 |

컨테이너 로그에서 다음 항목을 확인한다.

```text
cycle ok records=N sent=N failed=0 pending=0
```

- `records`: 이번 사이클에서 만든 이벤트 수
- `sent`: Kafka 전송 성공 누적 건수
- `failed`: Kafka 전송 실패 누적 건수
- `pending`: 아직 Kafka 확인을 기다리는 건수

## API만 직접 점검

직접 실행은 Kafka에 발행하지 않고 API 응답을 JSON 파일로 저장한다. 기본적으로
`collector/.env`를 읽는다.

```powershell
cd collector

# 버스 노선 하나만 확인
python src/sources/bus_arrival.py --bus-route-id 100100124
python src/sources/bus_location.py --bus-route-id 100100124

# 전체 지하철 도착정보 확인
python src/sources/subway_arrival.py

# 지하철 한 호선 위치 확인
python src/sources/subway_location.py --subway-line "1호선"

# 최근 지하철 알림 5건 범위 확인
python src/sources/subway_alert.py --page-size 5

# 최근 TOPIS 공지와 교통 돌발 범위 확인
python src/sources/topis_notice.py --page-size 5
python src/sources/traffic_incident.py --page-size 5 --first-page-only
```

전체 범위를 확인하기 전에는 반드시 예상 호출량과 남은 일일 한도를 계산한다.

## 호출 한도 보호

날씨를 제외한 8개 수집기는 `RequestBudget`을 통해 인증키별 호출량을 합산한다.

- 기본 한도는 최근 24시간 900회다.
- 실패한 HTTP 요청과 리다이렉트도 호출 횟수에 포함한다.
- 인증키 원문은 저장하지 않고 SHA-256 식별값만 저장한다.
- 재시작 후에도 기록이 유지되도록 Docker volume의 SQLite에 저장한다.
- 8개 수집기의 동시 쓰기는 WAL, 30초 `busy_timeout`, 짧은 트랜잭션으로 직렬화한다.
- 잔여 호출량이 한 사이클 전체에 부족하면 시작 전에 차단해 일부만 수집되는 것을 막는다.

이 제한기는 Collector가 실행한 요청만 기록한다. 같은 키를 Postman이나 다른 서버에서도
사용하면 해당 호출은 기록되지 않으므로 운영 인증키는 Collector 전용으로 사용하는 것이 안전하다.

## Kafka 이벤트 구조

API 항목 하나는 Kafka 메시지 하나가 된다. 공통 형태는 다음과 같다.

```json
{
  "eventId": "UUID",
  "eventType": "bus.arrival",
  "source": "seoul.bus_arrival",
  "occurredAt": "2026-09-09T12:00:00+09:00",
  "collectedAt": "2026-09-09T12:00:01+09:00",
  "payload": {
    "providerRouteId": "서울시가 제공한 노선 ID",
    "raw": {},
    "observedAt": "수집 시각",
    "timestampBasis": "provider 또는 received",
    "contentHash": "원본 내용 SHA-256"
  }
}
```

`payload.raw`에는 제공기관의 원본 항목을 보존한다. 제공기관의 ID는 `provider*Id`로
구분하며, 내부 DDUBI `segmentId`로 임의 변환하지 않는다. 상세 계약은
[`docs/contracts/events`](../docs/contracts/events)를 참고한다.

## 오류 처리와 보안

- HTTP 상태뿐 아니라 JSON/XML 구조와 제공기관 결과 코드도 검증한다.
- 한 사이클 실패로 프로세스가 종료되지 않고 다음 주기에 다시 시도한다.
- Kafka가 늦게 시작되면 최대 5분 동안 준비를 확인한다.
- Kafka 메시지는 `acks=all`, idempotence 활성화 상태로 발행한다.
- 인증키가 포함될 수 있는 HTTP 요청 URL은 INFO 로그에 출력하지 않는다.
- `.env`, SQLite 예산 DB, API 응답 파일은 Git에 커밋하지 않는다.

## 테스트

```powershell
cd collector
python -m pytest -q -p no:cacheprovider
```

테스트는 실제 API나 Kafka 없이 고정 응답과 가짜 Producer를 사용한다. 주요 검증 범위는
다음과 같다.

- JSON/XML 응답 파싱과 업무 오류 처리
- API 키와 요청 URL의 로그 노출 방지
- Kafka 이벤트 계약과 전송 결과 처리
- 최근 24시간 호출 한도와 재시작 후 상태 유지
- 여러 수집기의 SQLite 동시 접근
- 버스 100개 노선 제한과 수집 주기

수도권 대응표 경계는 `tests/test_bus_crosswalk.py`에서 출처 충돌·모호한 진입 구간·실제 배포 파일을 검증한다.

## 버스 공통 ID 대응표

`BUS_CROSSWALK_PATH`와 manifest의 `BUS_CROSSWALK_SHA256`을 설정하면 버전 고정
대응표를 한 번 검증하고 캐시한다. Spark에도 같은 파일·SHA를 배포하고 함께 재시작한다.
미설정 상태에서도 원본 관측은 보존하되 BUS `segmentIds`는 비우고 payload의
`canonicalMapping`에 사유를 남긴다. 도착 대상 정류장의 진입 edge는 차량 현재 위치가 아니다.
위치 관측의 `sectOrd`만으로 구간을 추측하지 않는다.
생성·배포 방법은 [마스터 안내](../backend/transit-master/README.md)를 따른다.

## 경기·인천 선택 실행 수집기

도착/위치 4개 소스와 namespace·페이지·초 단위 규칙은 [통합 보강 계약](../docs/contracts/transit-service-integration.md)을 따른다. 기존 운영 대상과 주기는 변경하지 않았다.
