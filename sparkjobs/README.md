# DDUBI Spark 작업

Collector가 Kafka에 발행한 계약 이벤트를 실시간·배치 연산하고, 서비스가 조회할 최신 상태는
Redis에 TTL과 함께 저장한다. Kafka 원문 보존은 Spark가 아니라 Archiver가 담당하므로 Spark
잡이 재시작되거나 계산식이 바뀌어도 HDFS 원문으로 Replay할 수 있다.

## 실시간 잡

| 잡 | 입력 토픽 | 처리 이벤트 | 출력 |
|---|---|---|---|
| `streaming/weather_latest.py` | `city.raw` | `weather.observation` | Redis `weather:latest:{nx}:{ny}` + HDFS `silver/weather` |
| `streaming/realtime_latest.py` | `transport.raw,city.raw` | 버스·지하철·도로·도시·S-DoT 9종 | `docs/contracts/redis-keys.yaml`의 소스별 latest 키 |

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
