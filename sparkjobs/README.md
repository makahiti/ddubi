# DDUBI Spark 작업

Collector가 Kafka에 발행한 계약 이벤트를 실시간·배치 연산하고, 서비스가 조회할 최신 상태는
Redis에 TTL과 함께 저장한다. Kafka 원문 보존은 Spark가 아니라 Archiver가 담당하므로 Spark
잡이 재시작되거나 계산식이 바뀌어도 HDFS 원문으로 Replay할 수 있다.

## 실시간 잡

| 잡 | 입력 토픽 | 처리 이벤트 | 출력 |
|---|---|---|---|
| `streaming/weather_latest.py` | `city.raw` | `weather.observation` | Redis `weather:latest:{nx}:{ny}` + HDFS `silver/weather` |
| `streaming/realtime_latest.py` | `transport.raw,city.raw` | 버스·지하철·도로·S-DoT | `docs/contracts/redis-keys.yaml`의 소스별 latest 키 |
| `streaming/city_realtime_silver.py` | `city.raw` | `city.realtime` | Redis 도시 latest/forecast + 선택적 HDFS Parquet |
| `streaming/topis_enriched.py` | `city.raw` | `topis.notice` | GMS/Kakao 정규화 → Redis JSON + 선택적 HDFS Parquet |
| `streaming/traffic_incident_silver.py` | `city.raw` | `traffic.incident` | 링크·좌표 검증 → Redis JSON + 선택적 HDFS Parquet |

서버 `.env`에 `SILVER_OUTPUT_URI`가 설정되어 있으면 Weather Silver가 Kafka의 새 관측을 자동 저장한다.
Silver가 실패해도 Redis latest는 계속 갱신한다. Redis latest와 HDFS Silver는
`common/weather_normalizer.py`를 공유한다. 컬럼, 결측 처리, 실행 방법은
[Weather Silver 계약](../docs/contracts/weather-silver.md)을 따른다.
원본 archive만 있고 Silver가 없을 때의 진단, 컨테이너 환경 반영, 별도 아카이브 backfill은
[날씨 Silver 복구 안내](../docs/runbooks/weather-silver-missing.md)를 따른다.

`realtime_latest`는 외부 API 원문을 그대로 Redis에 넣지 않는다. 버스 도착예정 초, 지하철
도착예정 초, 도로 속도(km/h), 통행시간(초), 장소 인구 범위(명)처럼 계산에 필요한 대표값만
typed field로 승격한다. 전체 원문은 이벤트의 `eventId`를 기준으로 HDFS에서 추적한다.

## 실행

Spark 컨테이너의 `/opt/ddubi/sparkjobs`에서 다음처럼 제출한다.

```sh
sh submit.sh streaming/weather_latest.py
sh submit.sh streaming/realtime_latest.py
sh submit.sh streaming/city_realtime_silver.py
sh submit.sh streaming/topis_enriched.py
```

TOPIS AI 잡의 컬럼, 좌표·반경, 결측/오류 정책과 과거 아카이브 백필은
[TOPIS 공지 AI 전처리 계약](../docs/contracts/topis-notice-enrichment.md)을 따른다.
교통 돌발의 LINK_ID 우선순위, EPSG:5181 변환, 위치 충돌과 마스터 승인 조건은
[Traffic Incident Silver 계약](../docs/contracts/traffic-incident-silver.md)을 따른다.
도시 인구·예측·도로 링크의 컬럼, WGS84 `XYLIST`, 결측 및 백필 정책은
[City Realtime Silver 계약](../docs/contracts/city-realtime-silver.md)을 따른다.

Compose의 TOPIS AI는 `topis-ai`, 교통 돌발 Silver는 `road-silver` 프로필로 서버1에서 실행한다.
새 돌발 회차를 버스 구간 Redis feature로 갱신하는 Worker는 서버2의 `road-feature` 프로필로 실행한다.
일반 비기상 잡의 배포 절차는
[배포 요청 문서](../docs/meetings/2026-09-04-realtime-latest-deployment-handoff.md)를 따른다.

공통 필수 환경변수는 `KAFKA_BOOTSTRAP_SERVERS`, `REDIS_HOST`, `SPARK_CHECKPOINT_DIR`이며,
Redis 인증을 사용하면 `REDIS_PASSWORD`도 넣는다. 비기상 잡은 기본적으로 두 원본 토픽을 모두
구독한다. Replay나 격리 점검 때만 `SPARK_JOB_TOPICS`, `SPARK_JOB_NAME`을 바꾸고 운영 잡과
checkpoint 경로가 겹치지 않게 한다.

Collector는 자료별 갱신주기와 호출 설정이 다르므로 `collector/README.md`의 개별
`COLLECTOR_SOURCE` 프로세스로 실행한다. 모든 Collector 이벤트는 기존 Archiver가 원본 토픽을
소비해 HDFS에 저장하므로 비기상 소스마다 별도 아카이버를 만들 필요는 없다.

## 식별자와 현재 제한

Redis의 `providerRouteId`, `providerStationId`, `providerLinkId`는 서울 API가 준 외부 ID다.
BUS는 동일 SHA의 대응표로 재검증한 `segmentIds`만 사용하며 다른 모드의 기존 ID는 보존한다.
값에 `canonicalMappingAvailable`와 버전·범위·실패 사유를 남긴다. 도착 대상 진입 edge를
차량 현재 구간으로 간주하거나 관측만으로 `segment:status:{segmentId}`를 생성하지 않는다.
현재 범위는 다음과 같다.

1. 외부 API 수집과 Kafka 발행
2. HDFS 원문 보존
3. 제공자 ID별 최신 관측 정규화와 Redis 서빙
4. 누락 ID·늦은 이벤트·TTL 기반 stale 방어

canonical 구간 상태와 지연시간 계산은 crosswalk가 공급된 뒤 별도 변환에서 이어 붙인다.

BUS 대응표를 사용하려면 driver에서 읽을 수 있는 `BUS_CROSSWALK_PATH`와 manifest의
`BUS_CROSSWALK_SHA256`을 잡 환경에 설정한다. `.env`를 자동으로 읽지 않는다.
Collector와 같은 버전의 파일을 함께 배포·재시작하며 불일치한 BUS ID는 비운다.
전체 인접 edge는 기동 시 JVM 인덱스로 만들고 원천 이벤트는 DataFrame 조인으로 처리한다.
파일 미설정은 `CROSSWALK_NOT_CONFIGURED`, SHA 오류는 잡 기동 실패다.
재생성·배포 방법은 [마스터 안내](../backend/transit-master/README.md), 의미·키 규칙은
[구간 연결 계약](../docs/contracts/metropolitan-segment-integration.md)을 따른다.

Feature 조립 전에 같은 대응표를 Spark 조인용 Parquet으로 변환한다. 이 작업은 ID를
새로 만들지 않고 SHA 검증을 통과한 `segmentId`를 그대로 옮긴다. `edges/`는 원본
순번·방향을 보존하고, `mappings/`는 제공자 노선+이전 정류장+다음 정류장과 `segmentId`의
대응이며, `segments/`는 중복을 제거한 구간 목록이다. `complete/`가 있는 출력만 다음
작업의 입력으로 사용한다.

```sh
sh submit.sh batch/export_bus_segment_mapping.py \
  --crosswalk "$BUS_CROSSWALK_PATH" \
  --crosswalk-sha "$BUS_CROSSWALK_SHA256" \
  --output hdfs://hdfs-namenode:9000/ddubi/master/bus-segment/version=20260918-v1
```

버스 구간을 현재 날씨 Collector의 기상청 격자와 연결하려면 같은 crosswalk와 버스
마스터를 사용해 아래 배치를 실행한다. 출발·도착 좌표를 각각 공식 5km 격자로 변환하고,
중간 좌표의 격자를 대표값으로 쓴다. 양 끝 격자가 다르면 `CROSSES_GRID`, 좌표가 없으면
`UNRESOLVED`, 현재 수집하지 않는 격자는 `OUT_OF_COVERAGE`로 남긴다. 다른 서울 격자로
억지로 바꾸지 않는다. 운영에서 `WEATHER_GRID_POINTS`를 바꿨다면 배치에도 같은 값을 준다.

```sh
sh submit.sh batch/export_bus_weather_mapping.py \
  --crosswalk /opt/ddubi/transit-master/bus/metropolitan-bus-v6.crosswalk.json.gz \
  --crosswalk-sha 8014da5e1453d426789fa396622a0f0e4069ac484db76860348af517adc1f246 \
  --bus-master /opt/ddubi/transit-master/bus/metropolitan-bus-v6.json.gz \
  --bus-master-sha 6999b0e67eb1af391de563291ce9cf4251b5ec271307e3f24de55d22d17318e2 \
  --output hdfs://hdfs-namenode:9000/ddubi/master/bus-weather-grid/version=20260918-v1
```

출력 `edges/`에는 순번별 좌표와 출발·도착·대표 격자를, `mappings/`에는 고유
`segmentId → nx, ny`를 저장한다. `quality/`에서 상태·경계 통과 건수를 확인하고
`complete/`가 있는 출력만 Feature 조립기의 정적 입력으로 사용한다.

같은 `mappings` DataFrame은 Redis에도 배포한다. 각 `segmentId`를 Hash field로 쓰고,
작성 중인 Hash의 전체 행 수를 확인한 뒤에만
`ml:mapping:bus-weather:active:{masterVersion}` 포인터를 새 버전으로 바꾼다. 따라서 일부만
작성된 대응표가 요청 처리에 노출되지 않는다. HDFS 쓰기가 실패해도 Redis 배포는 계속하며,
Redis를 의도적으로 생략하는 검증 실행에서만 `--skip-redis`를 준다. Redis 전용 재배포는
`--output`을 생략하고 같은 명령을 실행한다. 기본 TTL은 30일이며
`BUS_WEATHER_MAPPING_TTL_SECONDS`로 조정한다.

요청 시점의 Feature 조립기는 active 포인터에서 현재 Hash 키를 얻고, 해당 Hash에서
`segmentId`를 `HGET`해 `nx, ny`를 찾은 다음 기존 `weather:latest:{nx}:{ny}`를 읽는다.
이 배치는 대응표 배포까지 담당하며 통합 Feature Snapshot 생성은 별도 단계다.

검토 완료 도로 대응표와 승인된 OA-13310 링크 마스터가 준비되면 버스 구간→TOPIS 링크 배치를
실행한다. 후보·감사 파일은 입력으로 사용할 수 없으며 두 artifact의 SHA·버스 버전·유효기간과
모든 LINK_ID 존재 여부가 맞아야 실행된다. 현재 저장소에는 승인된 운영 artifact가 없으므로 아래
경로와 SHA를 임의 값으로 채우지 않는다.

```sh
sh submit.sh batch/export_bus_road_mapping.py \
  --crosswalk /opt/ddubi/transit-master/bus/metropolitan-bus-v6.crosswalk.json.gz \
  --crosswalk-sha <BUS_CROSSWALK_SHA256> \
  --road-crosswalk /opt/ddubi/transit-master/road/<reviewed-road-crosswalk>.json \
  --road-crosswalk-sha <ROAD_CROSSWALK_SHA256> \
  --traffic-link-master /opt/ddubi/transit-master/road/<approved-oa13310-links>.json.gz \
  --traffic-link-master-sha <TRAFFIC_LINK_MASTER_SHA256> \
  --as-of 2026-09-18T12:00:00+09:00 \
  --output hdfs://hdfs-namenode:9000/ddubi/master/bus-road-link/version=20260918-v1
```

`edges/`는 노선 순번별 링크 배열과 검증 근거, `mappings/`는 고유 segmentId별 안전한 링크
배열을 Parquet으로 저장한다. `review-table/`에는 구간별 LINK_ID·상태·근거·적용기간을
사람이 확인할 수 있는 CSV로 저장한다. 같은 `mappings` DataFrame을 Redis versioned Hash에도 JSON으로
기록한다. 동일 segmentId가 서로 다른 도로 경로를 가지거나 일부 occurrence만 매핑되면 링크를
확정하지 않고 `AMBIGUOUS` 또는 `PARTIAL`로 남긴다. 출력 경로를 지정하면 확인표 저장
실패 시 Redis 활성화도 중단한다. 새 사고의 구간별 특성 자동 갱신은
`docs/runbooks/incident-segment-activation.md`를 따른다.

City Silver 도로 좌표가 HDFS에 쌓인 뒤 버스 구간→도시 영역 대응표를 배포한다. 도시데이터는
서울 전체가 아니라 주요 장소만 제공하므로 먼 구간은 `OUT_OF_COVERAGE`로 보존한다.

```sh
sh submit.sh batch/export_bus_city_mapping.py \
  --crosswalk /opt/ddubi/transit-master/bus/metropolitan-bus-v6.crosswalk.json.gz \
  --crosswalk-sha 8014da5e1453d426789fa396622a0f0e4069ac484db76860348af517adc1f246 \
  --bus-master /opt/ddubi/transit-master/bus/metropolitan-bus-v6.json.gz \
  --bus-master-sha 6999b0e67eb1af391de563291ce9cf4251b5ec271307e3f24de55d22d17318e2 \
  --city-road-input 'hdfs://hdfs-namenode:9000/ddubi/silver/city/realtime-v1/live/batches/batch=*/road-link-snapshot' \
  --output hdfs://hdfs-namenode:9000/ddubi/master/bus-city-area/version=20260918-v1
```

기본 거리 정책은 300m, 여러 영역 판정 여유는 50m다. 운영 자료로 조정한 경우 실행 인자와
출력의 정책값을 함께 보존한다. 위 SHA는 `metropolitan-bus-v6` 파일을 확인한 값이다.
HDFS의 `road-link-snapshot` Parquet이 실제로 생성된 뒤 실행하고, Redis 대응표는 기본 30일
TTL이므로 만료 전에 재배포한다. 자세한 판정은
[버스 구간→도시 영역 계약](../docs/contracts/bus-city-mapping.md)을 따른다.

## 구간 기준선·판정 실행 준비 (2026-09-10)

Collector는 변경하지 않는다. 실제 구간 통과시간 정규화 입력이 있어야 아래 계산을 운영에 연결할 수 있다.
위치·도착예정 원본을 학습 정답으로 대체하지 않는다. 현재는 고정시각 Replay/검토 출력과 별도 발행 단계다.
입력·정책·담당자 확인 사항은 [구간 파이프라인 계약](../docs/contracts/segment-pipeline-handoff.md)을 따른다.

```sh
# Backend가 사용하는 것과 동일한 파일·SHA에서 ID 레지스트리를 생성한다.
sh submit.sh batch/export_segment_registry.py \
  --subway "$SUBWAY_MASTER_PATH" --subway-sha "$SUBWAY_MASTER_SHA256" \
  --output "$SEGMENT_REGISTRY_URI"

# 실제 통과 사실에 대해서만 최소 표본·날짜 기준을 적용한다. 기본은 검토용 파일 출력이다.
sh submit.sh batch/segment_baseline.py \
  --input "$SEGMENT_FACTS_URI" --registry "$SEGMENT_REGISTRY_URI" \
  --output "$SEGMENT_BASELINE_OUTPUT_URI" --version "$BASELINE_VERSION" \
  --as-of 2026-09-17 --days 7 --min-samples "$MIN_SAMPLES" --min-dates "$MIN_DATES"

# 분 단위 평가시각 및 검토한 임계값을 명시한다. 최초 실행은 --previous를 생략한다.
sh submit.sh streaming/segment_status.py \
  --input "$SEGMENT_FACTS_URI" --registry "$SEGMENT_REGISTRY_URI" \
  --baseline "$BASELINE_PARQUET_URI" --previous "$PREVIOUS_SUCCESSFUL_LATEST_URI" \
  --output "$STATUS_OUTPUT_URI" --as-of "$EVALUATION_TIME_KST" \
  --slow-seconds "$SLOW_SECONDS" --delayed-seconds "$DELAYED_SECONDS" \
  --min-samples "$MIN_SAMPLES" --max-age-seconds "$MAX_AGE_SECONDS" --policy-version "$STATUS_POLICY_VERSION"

# 결과 검토 후에만 발행한다. 실패하면 같은 준비 결과를 재발행하고 previous를 전진시키지 않는다.
sh submit.sh streaming/segment_status.py --publish-output "$STATUS_OUTPUT_URI"
```

구간 기준선의 `--upload`는 실제 Backend 버전을 활성화하므로 검토 후 사용한다.
새 평가마다 출력 경로를 달리하고, `latest/history/rejected/manifest`가 완성된 출력만 발행한다.
Kafka 이력은 상태 변경과 10분 구획별 전체 기록이며 중복 제거 키는 `eventId`다.
상시 스케줄러, 실제 관측 정규화, 공유 파일 배포 및 상태 변경 이력 보관 설정은 별도 연결이 필요하다.

## 테스트

버스 위치·도착 이력의 제출용 전처리는 `batch/bus_silver.py`가 담당한다.
Redis 최신값과 달리 전체 관측을 보존하며 원본을 직접 읽어 오류·중복·결측 사유를 남긴다.
컬럼, 결측 처리 이유, 실행과 제출 기준은 [버스 Silver 안내](../docs/contracts/bus-silver.md)를 따른다.
새 구간 매핑·날씨 결합·실제 이동시간 Label 생성은 이 잡의 범위가 아니다.

지하철 시간표 연동용 최신값은 `observation.arrivalTrainType`과 `sourceGeneratedAt`을 함께 보존한다.
버스 대기 연동은 `exps1/2`(초), `staOrd`, 운행/만차/우회 코드와 `mkTm`을 보존한다.
기존 traTime(분)을 ArrivalSeconds에 저장한 오류를 수정했다. `arrivalTimeBasis=SEOUL_EXPS_SECONDS_V1`이
없는 이전 Redis 값은 Backend가 사용하지 않으며 별도 데이터 삭제는 필요 없다. [대기시간 계약](../docs/contracts/transit-waiting.md).
원본 생성시각이 없으면 수집시각으로 채우지 않는다. Backend는 새 필드 누락/만료 시 실시간 보정 대신
시간표로 fallback한다. 기존 키와 TTL은 유지하며 잡 재시작 후 생성하는 값부터 새 필드가 포함된다.
전체 계산 조건과 시간표 공급 형식은 [지하철 계산 계약](../docs/contracts/subway-timing.md)을 따른다.

```sh
python -m pytest -q
```

변환은 Kafka·Redis 없이 배치 DataFrame으로 검증한다. 새 이벤트나 필드를 추가할 때는 이벤트
계약, Collector payload, `realtime_latest` 정규화, Redis 키 계약과 실패 경로 테스트를 같은
변경에서 갱신한다.

## 실측 상태·도로 영향 실행

새 `streaming.segment_observations`, `streaming.segment_status_live`, `streaming.road_incidents`의 입력·실행·재시도·활성화 조건은 [통합 보강 계약](../docs/contracts/transit-service-integration.md)에 있다. 기존 Collector 주기/운영 submit은 변경하지 않는다.
