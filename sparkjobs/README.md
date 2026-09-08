# DDUBI Spark 작업

Collector가 Kafka에 발행한 계약 이벤트를 실시간·배치 연산하고, 서비스가 조회할 최신 상태는
Redis에 TTL과 함께 저장한다. Kafka 원문 보존은 Spark가 아니라 Archiver가 담당하므로 Spark
잡이 재시작되거나 계산식이 바뀌어도 HDFS 원문으로 Replay할 수 있다.

## 실시간 잡

| 잡 | 입력 토픽 | 처리 이벤트 | 출력 |
|---|---|---|---|
| `streaming/weather_latest.py` | `city.raw` | `weather.observation` | `weather:latest:{nx}:{ny}` |
| `streaming/realtime_latest.py` | `transport.raw,city.raw` | 버스·지하철·도로·도시·S-DoT 9종 | `docs/contracts/redis-keys.yaml`의 소스별 latest 키 |

`realtime_latest`는 외부 API 원문을 그대로 Redis에 넣지 않는다. 버스 도착예정 초, 지하철
도착예정 초, 도로 속도(km/h), 통행시간(초), 장소 인구 범위(명)처럼 계산에 필요한 대표값만
typed field로 승격한다. 전체 원문은 이벤트의 `eventId`를 기준으로 HDFS에서 추적한다.

## 실행

Spark 컨테이너의 `/opt/ddubi/sparkjobs`에서 다음처럼 제출한다.

```sh
sh submit.sh streaming/weather_latest.py
sh submit.sh streaming/realtime_latest.py
```

현재 저장소의 Compose에는 날씨 잡만 등록돼 있다. 비기상 잡의 자동 기동에 필요한 인프라 변경은
[배포 요청 문서](../docs/meetings/2026-09-04-realtime-latest-deployment-handoff.md)에 서비스 정의와
검증 절차까지 정리했으며, 인프라 담당자가 적용하기 전에는 위 명령으로 수동 실행해야 한다.

공통 필수 환경변수는 `KAFKA_BOOTSTRAP_SERVERS`, `REDIS_HOST`, `SPARK_CHECKPOINT_DIR`이며,
Redis 인증을 사용하면 `REDIS_PASSWORD`도 넣는다. 비기상 잡은 기본적으로 두 원본 토픽을 모두
구독한다. Replay나 격리 점검 때만 `SPARK_JOB_TOPICS`, `SPARK_JOB_NAME`을 바꾸고 운영 잡과
checkpoint 경로가 겹치지 않게 한다.

Collector는 자료별 갱신주기와 호출 설정이 다르므로 `collector/README.md`의 개별
`COLLECTOR_SOURCE` 프로세스로 실행한다. 모든 Collector 이벤트는 기존 Archiver가 원본 토픽을
소비해 HDFS에 저장하므로 비기상 소스마다 별도 아카이버를 만들 필요는 없다.

## 식별자와 현재 제한

Redis의 `providerRouteId`, `providerStationId`, `providerLinkId`는 서울 API가 준 외부 ID다.
검증된 `segmentIds`가 이벤트에 들어온 경우에만 `canonicalMappingAvailable=true`가 된다.
정적↔실시간 crosswalk 배포 계약이 완성되기 전에는 이 잡이 `EXACT`를 추측하거나
`segment:status:{segmentId}`를 생성하지 않는다. 따라서 현재 범위는 다음과 같다.

1. 외부 API 수집과 Kafka 발행
2. HDFS 원문 보존
3. 제공자 ID별 최신 관측 정규화와 Redis 서빙
4. 누락 ID·늦은 이벤트·TTL 기반 stale 방어

canonical 구간 상태와 지연시간 계산은 crosswalk가 공급된 뒤 별도 변환에서 이어 붙인다.

## 테스트

```sh
python -m pytest -q
```

변환은 Kafka·Redis 없이 배치 DataFrame으로 검증한다. 새 이벤트나 필드를 추가할 때는 이벤트
계약, Collector payload, `realtime_latest` 정규화, Redis 키 계약과 실패 경로 테스트를 같은
변경에서 갱신한다.
