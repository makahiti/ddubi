# 비기상 실시간 Spark 잡 배포 요청

## 요청 배경

`sparkjobs/streaming/realtime_latest.py`는 버스·지하철·도로·도시·S-DoT 이벤트를
`transport.raw,city.raw`에서 읽어 Redis 최신값으로 만든다. 실행 코드와 테스트는 데이터 담당
MR에 포함됐지만, 현재 Compose에는 `spark-streaming-weather`만 있어 코드를 배포하는 것만으로는
신규 잡이 시작되지 않는다.

`infra/`와 Docker Compose의 소유자는 인프라 담당이다. 아래 변경은 데이터 담당이 인프라 파일을
직접 수정하지 않고도 그대로 검토·적용할 수 있도록 정리한 배포 계약이다.

## 인프라 담당 적용안

`infra/docker-compose.yml`의 `spark-streaming-weather`와 같은 레벨에 다음 서비스를 추가한다.
운영 잡 하나당 driver 서비스 하나라는 현재 구조를 유지하고, 체크포인트는 잡 기본 이름
`realtime_latest`로 분리된다.

```yaml
  spark-streaming-realtime:
    <<: *common
    image: ddubi/spark:${APP_VERSION:-0.1.0}
    container_name: spark-streaming-realtime
    profiles: ["spark"]
    mem_limit: 1g
    working_dir: /opt/ddubi/sparkjobs
    command: ["sh", "submit.sh", "streaming/realtime_latest.py"]
    ports:
      - "${SPARK_REALTIME_DRIVER_UI_PORT:-4042}:4040"
    environment:
      TZ: ${TZ:-Asia/Seoul}
      SPARK_VERSION: ${SPARK_VERSION:-3.5.3-python3}
      SPARK_CHECKPOINT_DIR: ${SPARK_CHECKPOINT_DIR}
      SPARK_EVENTLOG_DIR: ${SPARK_EVENTLOG_DIR}
      SPARK_EVENTLOG_ENABLED: ${SPARK_EVENTLOG_ENABLED:-false}
      SPARK_STARTING_OFFSETS: ${SPARK_STARTING_OFFSETS:-latest}
      SPARK_JOB_TOPICS: transport.raw,city.raw
      KAFKA_BOOTSTRAP_SERVERS: ${KAFKA_BOOTSTRAP_SERVERS}
      REDIS_HOST: ${REDIS_HOST}
      REDIS_PORT: ${REDIS_PORT}
      REDIS_PASSWORD: ${REDIS_PASSWORD}
    volumes:
      - ./spark/conf:/opt/spark/conf:ro
      - ../sparkjobs:/opt/ddubi/sparkjobs
      - spark-checkpoints:/opt/ddubi/checkpoints
      - spark-events:/opt/ddubi/spark-events
      - spark-ivy:/opt/ddubi/ivy
    depends_on:
      - spark-master
      - spark-worker-1
      - spark-worker-2
```

서버1 오버레이 `infra/docker-compose.server1.yml`의 Spark 서비스 목록에는 다음 한 줄을 추가한다.
HDFS DataNode2 주소를 Spark driver에도 전달하기 위한 기존 anchor 규칙이다.

```yaml
  spark-streaming-realtime: *dn2-hosts
```

`.gitlab-ci.yml`의 `compose-validate`는 이미 `spark` profile을 검증하고 배포 명령도 같은 profile을
올리므로, 서비스가 Compose에 추가되면 별도 CI 잡 변경 없이 검증·자동 기동 대상에 포함된다.

## 적용 전후 검증

1. `docker compose ... --profile spark config -q`로 base/server1 overlay 문법을 확인한다.
2. 데이터 서버에서 `spark-streaming-realtime` 컨테이너가 재시작 없이 유지되는지 확인한다.
3. Spark UI 4042와 로그에서 `transport.raw,city.raw` offset 증가를 확인한다.
4. 샘플 소스 한 개를 실행하고 해당 `*:latest:*` Redis 키의 JSON·TTL을 확인한다.
5. 과거 `occurredAt` 이벤트를 뒤늦게 발행해 기존 최신 Redis 값이 바뀌지 않는지 확인한다.
6. driver/worker 메모리와 Kafka lag를 확인한 뒤 필요할 때만 리소스 상한을 조정한다.

## 완료 조건

- `--profile spark up -d --build`만으로 날씨와 비기상 최신값 잡이 함께 실행된다.
- 두 잡의 checkpoint 디렉터리와 Spark UI 포트가 겹치지 않는다.
- Redis 키·TTL이 `docs/contracts/redis-keys.yaml`과 일치한다.
- 수집 중단 시 TTL 만료되고, 늦은 과거 이벤트는 현재값을 덮어쓰지 않는다.
