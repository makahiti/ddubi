# Kafka 가 죽었거나 재시작해야 한다

대상: 서버1의 `kafka`(KRaft 단일 노드). Kafka 가 없는 동안은 수집기가 보낼 곳이 없고, Spark·아카이버가 읽을 곳이 없다. 대신 Kafka 가 돌아오면 모두 스스로 재연결한다.

## 증상

- 수집기 로그에 `Connect to ipv4#...:9092 failed`, `Local: Message timed out`
- Spark 드라이버 로그에 Kafka `TimeoutException`, 아카이버 로그에 브로커 연결 오류
- kafka-ui(8085) 가 클러스터를 못 찾는다, Grafana 의 Kafka 패널이 비어 있다
- `docker ps` 에서 kafka 가 `unhealthy` 또는 Restarting

## 먼저 확인 (서버1)

```
docker ps -a --format "{{.Names}}\t{{.Status}}" | grep kafka
docker inspect kafka --format "restarts={{.RestartCount}} health={{.State.Health.Status}} oom={{.State.OOMKilled}}"
docker logs kafka --tail 100 2>&1 | grep -E "ERROR|FATAL|Shutdown|started \(kafka.server" | tail -8
df -h /                                                        # 디스크가 찼는지 — Kafka 는 디스크가 차면 죽는다
docker exec kafka du -sh /var/lib/kafka/data                    # 토픽 데이터 크기 (보존 21일·1GiB/파티션)
```

읽는 법:

| 보이는 것 | 원인 | 조치 |
|---|---|---|
| `OOMKilled=true` | 힙(-Xmx1g)·컨테이너 제한(2g) 초과. 파티션·연결 수가 늘었을 때 | compose 의 `KAFKA_HEAP_OPTS`·`mem_limit` 상향 후 배포 |
| `No space left on device` | 디스크 가득 | 보존 설정 확인, 큰 토픽(실험 토픽 등) 삭제 |
| `unhealthy` 인데 로그는 정상 | 헬스체크(9092 포트 TCP 연결)가 5초 안에 안 됨. 브로커가 멈췼거나 기동 중 | 로그에서 기동 진행·오류 확인. 기동 중이면 대기 |
| 로그에 오류 없이 재시작 | 누가 `restart` 했거나 호스트 재부팅 | 아래 복구 확인만 |

## 조치

**재시작이 필요할 때** (설정 변경 적용, 메모리 조정 등):
```
$DC1 --profile core restart kafka          # 설정 파일만 바뀐 경우
$DC1 --profile core up -d kafka            # compose 정의(환경변수·mem_limit)가 바뀐 경우 — 재생성됨
```
재시작에 30~60초 걸린다. `docker inspect kafka --format "{{.State.Health.Status}}"` 가 healthy 가 될 때까지 기다린다.

**죽어 있는 동안 다른 컨테이너는 이렇게 동작한다** — 개입할 필요 없다:
- 수집기: 수집한 데이터를 내부 큐에 10분까지 보관하며 재시도한다(2026-09-03 조정). 10분 안에 Kafka 가 돌아오면 유실 없음. 넘기면 그 사이클은 `failed=N` 으로 로그에 남고 유실된다.
- 아카이버·Spark: 연결이 끊긴 채 재시도한다. 돌아오면 마지막 커밋·체크포인트에서 이어 읽는다.
- kafka-exporter: 브로커가 없으면 종료하고 재시작 정책으로 계속 다시 뜬다(재시작 횟수가 올라가는 것은 정상).

## 복구 확인

```
docker inspect kafka --format "{{.State.Health.Status}}"                                  # healthy
docker exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list     # 토픽 6개
docker exec kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server kafka:9092 --topic city.raw   # 오프셋 합계 — 재시작 전과 같거나 커야 함 (줄면 데이터 볼륨 문제)
docker logs collector-weather --since 15m 2>&1 | grep "cycle ok" | tail -2                 # 다음 사이클 sent=14 failed=0 pending=0
docker logs spark-streaming-weather --since 15m 2>&1 | grep "batch=" | tail -1
docker logs archiver --since 15m 2>&1 | grep -ciE "error|exception"                        # 재연결 후 0
```

유실 판정: 재시작 전 city.raw 오프셋 합계를 적어 두고, 재시작 뒤 수집 사이클 수 × 14 만큼 늘었는지 본다. 수집기 로그에 `failed=` 가 0 이 아니면 그만큼 유실이다.

## 예방·후속

- Kafka 데이터 볼륨(`ddubi_kafka-data`)은 지우면 21일치 대기열이 사라진다. 아카이브(HDFS)에는 남아 있으므로 재처리로 복구할 수 있다 ([reprocess-from-archive.md](reprocess-from-archive.md)).
- 헬스체크는 9092 포트 TCP 연결만 확인한다(2026-09-07, 자바 CLI 방식의 CPU 스파이크 제거). 포트가 열려도 요청 처리 준비까지 몇 초 걸릴 수 있으므로, 새 서비스가 `depends_on: service_healthy` 만 믿고 바로 요청하면 첫 시도가 실패할 수 있다. 재시도를 넣는다.
- transport 수집기가 켜지면 유입량이 크게 늘어 `retention.bytes`(1GiB/파티션) 가 21일보다 먼저 걸릴 수 있다. 가동 후 유입량을 재고 조정한다.

## 연습 기록

| 날짜 | 한 일 | 복구까지 | 유실 | 절차서 수정 |
|---|---|---|---|---|
| 2026-09-03 | 호스트 재부팅으로 Kafka·수집기 동시 재시작 (실제) | 약 1분 | 수집기 1사이클 14건 (시한 30초 때문) → 시한 10분·준비 대기로 수정 | 수집기 동작 설명 추가 |
| 2026-09-07 | 4: `docker restart kafka` → 16초 뒤 healthy, city.raw 오프셋 보존(13,948). 수집기 일회 실행 14건 정상 발행, Spark 14초 뒤 batch 980 처리, 아카이버 재연결(FAIL 로그 3줄 뒤 정상, 랙 28→42 로 계속 소비), 실시간 잡 오류 0, exporter 재시작 없음 | 16초 | 없음 | 절차 그대로 동작 |
