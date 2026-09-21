# Weather Silver v1 — 팀 공유용 설명서

## 한 줄 설명

기상청 원본의 `T1H`, `RN1` 같은 코드를 사람이 읽고 ML이 사용할 수 있는 컬럼으로 바꿔
HDFS에는 이력으로, Redis에는 격자별 최신값으로 저장한다.

```text
Kafka city.raw
├─ Archiver → /ddubi/archive/city.raw       원본 보관
└─ Weather Streaming → 공통 표준 DataFrame
                       ├─ Redis weather:latest:{nx}:{ny}   격자별 최신값
                       └─ HDFS /ddubi/silver/weather       전처리 이력
```

HDFS와 Redis는 `sparkjobs/common/weather_normalizer.py` 하나를 같이 사용한다. Kafka 메시지가
들어오면 같은 표준 DataFrame에서 Redis와 HDFS 출력이 갈라진다. 저장소마다 `T1H`를 다르게
해석하는 문제를 막기 위해서다.

## 변환 전과 후

입력의 `observations`는 Map이다.

```text
{T1H: 18.3, REH: 60, RN1: 0, PTY: 0, WSD: 2.1}
```

Silver에서는 다음처럼 스칼라 컬럼으로 바꾼다.

| 원본 | Silver 컬럼 | 단위 |
|---|---|---|
| `T1H` | `temperatureC` | °C |
| `REH` | `humidityPercent` | % |
| `RN1` | `precipitationMm` | mm/최근 1시간 |
| `PTY` | `precipitationTypeCode` | 기상청 코드 |
| `WSD` | `windSpeedMps` | m/s |
| `VEC` | `windDirectionDegree` | degree |
| `UUU` | `windEastWestMps` | m/s |
| `VVV` | `windNorthSouthMps` | m/s |

```text
+------+-------+------------+---------------+---------------+------------+
|nx,ny |시간   |temperatureC|humidityPercent|precipitationMm|windSpeedMps|
+------+-------+------------+---------------+---------------+------------+
|60,127|08:00  |18.3        |60.0           |0.0            |2.1         |
|60,127|09:00  |19.0        |72.0           |3.0            |3.2         |
+------+-------+------------+---------------+---------------+------------+
```

`districts` 배열은 Archive와 기존 Redis 호환값에는 남지만 Weather Silver에는 넣지 않는다.
구 이름이 필요하면 별도의 격자-행정구역 정적 대응표를 사용한다. 버스 ML은 구 이름을 거치지 않고
`segmentId → (nx, ny)` 대응표로 날씨를 찾는다.

## 결측과 이상값

- 값이 없거나 기상청 결측 표식처럼 허용 범위를 벗어나면 `null`이다.
- 실제 무강수 `precipitationMm=0`과 미수집 `null`을 구분한다.
- `missingFields`에 null인 표준 컬럼 이름을 기록한다.
- `eventId`, 격자, 관측/수집시각이 없거나 수집시각이 관측시각보다 빠르면 Silver에서 제외한다.
- 적설 깊이는 현재 원천에 없으므로 만들거나 0으로 채우지 않는다.

같은 격자·관측시각·값을 반복 수집한 경우 내용 해시로 중복을 제거한다. 같은 관측시각의 값이
수정되면 해시가 달라 두 revision을 모두 남긴다. Streaming은 최근 2일 범위에서 중복을 기억한다.

## 운영 HDFS 자동 저장

`spark-streaming-weather`가 실행 중이고 `SILVER_OUTPUT_URI`가 설정되어 있으면 별도 명령 없이
Kafka의 새 날씨를 자동 저장한다.

```text
Redis 확인 주기: 10초
HDFS Silver 저장 주기: 기본 10분
```

HDFS 주기를 10분으로 둔 이유는 메시지마다 파일을 만들지 않고 한 묶음으로 쓰기 위해서다.
환경에 따라 `WEATHER_SILVER_TRIGGER`를 늘릴 수 있다. Redis와 HDFS는 서로 다른 checkpoint를
사용하므로 재시작하면 각각 마지막 완료 Kafka offset 다음부터 이어간다.

Silver를 켤 때 사용할 환경값:

```text
SILVER_OUTPUT_URI=hdfs://hdfs-namenode:9000/ddubi/silver
WEATHER_SILVER_TRIGGER=10 minutes
WEATHER_SILVER_WATERMARK=2 days
```

운영 Compose 기본값은 빈 `SILVER_OUTPUT_URI`와 `WEATHER_REQUIRE_SILVER=false`다.
서버 `.env`에 위 HDFS 경로가 있을 때만 Silver를 시작한다. `WEATHER_REQUIRE_SILVER=true`는
URI 누락·형식 오류를 기동 설정 오류로 만들고 싶을 때만 사용한다. Silver가 시작하지 못하거나
실행 중 실패해도 서비스용 Redis 스트림은 계속 실행하며, Redis 실패는 잡 전체 실패로 처리한다.
설정 누락/잡 정지 조사와 아카이브 복구는 [날씨 Silver 복구 안내](../runbooks/weather-silver-missing.md)를 따른다.

```text
/ddubi/silver/weather/
└─ silverVersion=weather-silver-v1/
   └─ eventDate=2026-09-14/
      └─ eventHour=08/
```

## Redis 호환

기존 키 `weather:latest:{nx}:{ny}`, TTL 7,200초, `observations` 필드는 유지한다. 새 값에는
표준 `features`, `featureVersion`, `missingFields`, `validUntil`이 추가된다. 늦게 도착한 과거
관측은 기존 시각 비교 저장 로직이 거절한다.

## 아직 하지 않은 것

- 운영 서버 배포 및 실제 Kafka→HDFS Streaming 검증
- 새 `batch/weather_silver_backfill.py`의 운영 실행 검증 (과거 원본을 별도 버전 경로로 복구하는 코드는 추가됨)
- `batch/export_bus_weather_mapping.py` 구현의 운영 HDFS 실행 및 결과 검토
- 서울 14개 외 경기·인천 격자 수집
- Redis 소비자의 새 `features` 전환

코드가 추가되었다는 사실과 운영 적용 완료는 구분한다.
