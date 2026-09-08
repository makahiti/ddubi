# DDUBI 실시간·준실시간 Collector

외부 서울 교통·도시·날씨 API를 조회해 기존 계약의 `transport.raw` 또는 `city.raw`
Kafka 토픽으로 발행한다. 외부 제공자 ID는 `provider*Id`로 보존하며, 검증되지 않은 값을
DDUBI `segment_id`로 만들지 않는다. API 원문은 `payload.raw`에 남아 Archiver와 Replay가
같은 입력을 다시 처리할 수 있다.

## 구현된 소스

| `COLLECTOR_SOURCE` | 필수 범위 설정 | API 키 | 토픽 | 기본 주기 |
|---|---|---|---|---:|
| `bus_position` | `BUS_ROUTE_IDS` | `DATA_GO_KR_API_KEY` | `transport.raw` | 30초 |
| `bus_arrival` | `BUS_ROUTE_IDS` | `DATA_GO_KR_API_KEY` | `transport.raw` | 30초 |
| `subway_position` | 선택: `SUBWAY_LINES` | `SEOUL_API_KEY` | `transport.raw` | 30초 |
| `subway_arrival` | `SUBWAY_STATIONS` | `SEOUL_API_KEY` | `transport.raw` | 30초 |
| `traffic_realtime` | `TRAFFIC_LINK_IDS` | `SEOUL_API_KEY` | `transport.raw` | 300초 |
| `traffic_incident` | 없음 | `SEOUL_API_KEY` | `city.raw` | 60초 |
| `city_realtime` | 선택: 장소 식별자·Registry 교체 | `SEOUL_API_KEY` | `city.raw` | 300초 |
| `sdot_population` | 선택: 자치구·서비스명 | `SEOUL_API_KEY` | `city.raw` | 600초 |
| `subway_alert` | `SUBWAY_ALERT_API_URL` | `DATA_GO_KR_API_KEY` | `city.raw` | 60초 |
| `weather` | 선택: `WEATHER_GRID_POINTS` | `KMA_API_KEY` 또는 `DATA_GO_KR_API_KEY` | `city.raw` | 600초 |

`bus`, `subway`, `event`, `traffic`은 기존 실행 이름과의 호환용 통합 모듈이다. 서로 갱신
주기가 다른 소스는 위 개별 이름으로 프로세스를 나눠 실행하는 것이 정확하다.

## 1차 운영 대상

별도 노선·역·도로 링크 마스터 없이 시작할 수 있는 1차 대상은 `weather`,
`traffic_incident`, `subway_alert`, `subway_position`, `sdot_population`, `city_realtime`이다.
버스 위치·도착과 링크별 도로 속도는 코드가 준비되어 있지만, 활성 노선 Registry와 canonical
구간 crosswalk가 완성될 때까지 2차 대상으로 둔다.

## 설정과 1회 점검

비밀키는 코드나 URL에 넣지 말고 실행 환경에만 설정한다. 쉼표 목록은 실제 서비스 대상만
넣어 호출 한도와 Kafka 유입량을 통제한다.

```powershell
$env:COLLECTOR_SOURCE = "bus_position"
$env:KAFKA_BOOTSTRAP_SERVERS = "kafka:9092"
$env:DATA_GO_KR_API_KEY = "발급받은-디코딩-키"
$env:BUS_ROUTE_IDS = "100100118,100100047"
$env:COLLECTOR_RUN_ONCE = "true"
python src/main.py
```

지하철 위치는 설정을 생략하면 공식 명세의 16개 호선을 기본 조회한다. 일부만 점검하려면
`SUBWAY_LINES=1호선,2호선`처럼 제한한다. 역별 도착정보는 `SUBWAY_STATIONS=서울,시청`처럼
서비스 대상 역을 지정하며, TOPIS 도로 소통정보는 링크 ID가 필수다.

실시간 도시데이터는 2026-09-03에 공식 XLSX에서 확인한 121개 장소코드 Registry를 기본으로
사용한다. 일부만 점검하려면 장소명 또는 코드를 `CITY_AREA_IDENTIFIERS`에 넣고, 공식 목록이
변경되면 한 줄당 식별자 하나인 UTF-8 파일을 `CITY_AREA_REGISTRY_PATH`로 지정한다. 형식은
[config/city_areas.example.txt](config/city_areas.example.txt)를 참고한다. API 제약 때문에 각
장소는 한 번씩 별도 호출된다.

지하철 이례상황 알림은 공공데이터포털 활용신청 후 계정에 표시되는 요청 주소를
`SUBWAY_ALERT_API_URL`에 설정한다. 이 URL에는 `serviceKey`를 포함하지 않는다. 제공처가
표시한 서비스명이 필요하면 `SUBWAY_ALERT_SERVICE_NAME`도 설정한다.

S-DoT와 TOPIS 돌발정보의 OpenAPI 서비스명이 발급 화면과 다를 경우 각각
`SDOT_SERVICE_NAME`, `TRAFFIC_INCIDENT_SERVICE_NAME`으로 재정의한다. 서울 열린데이터광장
페이지 크기는 `SEOUL_API_PAGE_SIZE`(1~1000), 전체 주기는 `COLLECTOR_INTERVAL_SECONDS`로
조정할 수 있다. S-DoT은 센서별 최신 한 건만 발행한다. 기본 `IotVdata018`은 공개된 100대
센서를 기준으로 페이지를 넘기며, 다른 실시간 서비스는 `SDOT_EXPECTED_SENSOR_COUNT`를
해당 센서 수로 설정한다. `SDOT_MAX_PAGES`는 한 사이클의 최대 호출량을 제한한다.

## 장애 처리

- HTTP 상태, JSON/XML 구조, 서울시/공공데이터포털 결과 코드를 수집 경계에서 검증한다.
- 여러 노선·역·링크 중 일부가 실패하면 성공한 관측은 발행하고 실패 대상을 로그에 남긴다.
- 모든 대상이 실패하면 사이클을 실패 처리한다. 정상적인 빈 응답은 장애로 오인하지 않는다.
- 인증키가 포함된 요청 URL과 원문 전체를 예외나 INFO 로그에 남기지 않는다.
- Kafka 발행 실패와 외부 API 실패는 정상 수집 건수와 분리해 기록한다.

## 테스트

```powershell
python -m pytest -q
```

테스트는 실제 API와 Kafka를 호출하지 않고 고정 응답으로 정상, 단건, 일부 실패, 전체 실패,
설정 누락, 단위/시간 파싱, 비밀키 보호 경계를 검증한다. 실제 키를 받은 뒤에는 각 소스를
`COLLECTOR_RUN_ONCE=true`로 한 번씩 실행해 발급 권한, 서비스명, 대상 ID를 최종 확인한다.
