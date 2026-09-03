# 부하·확장 실험 절차서 (11단계)

증명할 명제 두 가지:
1. **계산의 분산** — Spark worker 를 1→2→3 으로 늘리면 같은 부하를 더 빨리 소화한다
2. **저장의 분산** — 데이터노드 하나가 죽어도 아카이브를 읽을 수 있다

도구: `replay-producer`(부하 발생), `spark-streaming-replay`(실험용 소비자), 체크포인트 오프셋 + 드라이버 로그(측정), Spark UI(보조), Spark Master UI(재배치 관찰)

1차 실행 결과와 교훈: [2026-09-03-load-test-round1.md](2026-09-03-load-test-round1.md) — **현재 weather 잡은 너무 가벼워 worker 수 차이가 나지 않는다** (1대 9,200건/초). 본 실험은 segment.status 잡에서 한다.

## 구성 원칙

- 실험은 **전용 재생 토픽 `city.replay`** 로만 한다. 운영 토픽·아카이브·운영 Redis 키를 건드리지 않는다
- 실험용 스트리밍은 운영 잡과 **같은 코드**를 환경변수만 바꿔 띄운다 (Redis 키 `weather:replay:*`, 체크포인트 `weather_replay`)
- 실험 중에는 **운영 스트리밍(spark-streaming-weather)을 멈춘다** — 워커 자원(각 1536m)을 실험 잡이 전부 쓰게 하고, 측정에 잡음이 섞이지 않게. 실험 후 다시 켠다 (Redis TTL 2h 라 서비스 영향 없음)
- 처리량 상한(`maxOffsetsPerTrigger`)은 실험 잡에서 크게(200,000/트리거) 풀어둔다. 기본 10,000 이면 초당 1,000건에서 막혀 worker 수 차이가 안 보인다
- **예열은 executor 구성이 바뀔 때마다** 한다. worker 를 새로 붙인 직후 첫 배치는 파이썬 실행기 기동·코드 생성·연결 생성으로 2배 이상 느리다 (1차: 예열 없이 22초 vs 예열 후 11초)
- **부하 발생기는 측정 대상과 CPU 를 다툰다.** 서버1(4코어)에서 replay 를 여러 개 돌리면 그 시간 동안 Spark 배치가 3~10배 느려진다.
  원칙은 replay 를 서버2에서 돌리는 것 — 준비물: 서버2 오버레이에 replay-producer 항목(kafka·hdfs-namenode·hdfs-datanode1 → 서버1 사설 IP extra_hosts, KAFKA_BOOTSTRAP_SERVERS/ARCHIVE_URI 를 서버1 IP 로) 추가 필요(미완).
  그 전까지는 replay 를 **1개**만 `--repeat` 로 돌리고, replay 와 겹친 배치는 측정에서 제외한다
- 투입 속도는 처리 능력 **위**로 잡는다. 처리 능력보다 낮으면 랙이 안 쌓여 "마지막 배치 하나 + 트리거 정렬"만 재게 된다. 예열 회차에서 큰 배치의 처리 속도를 먼저 재고 그 1.5~2배로 투입

## 측정 지표

| 지표 | 어디서 | 뜻 |
|---|---|---|
| 소진 시간 | replay "완료" 로그 시각 → 드라이버 로그의 마지막 `batch=N` 완료 시각 | replay 종료 후 밀린 입력을 전부 처리하기까지의 초. **핵심 지표** |
| 배치별 처리 건수 | 실험 잡 체크포인트 `offsets/N` 의 파티션별 종료 오프셋 차이 | 회차 총량 검산(유실 0 확인)과 배치별 처리 속도 계산 |
| 배치 처리시간 | 드라이버 로그 "spent N ms" 경고 또는 연속 배치 완료 시각 차, Spark UI(4041) Batch Duration | 트리거 10s 대비 얼마나 걸리나 |
| 처리 속도 (건/초) | 배치 건수 ÷ 배치 소요 | 큰 배치의 값이 그 worker 수의 정상 상태 처리 능력 |
| 실행기 재배치 | Spark Master UI(8080), 드라이버 로그 "Lost executor" | worker kill 후 실행기가 옮겨가는 시각 |

Spark Structured Streaming 은 처리 오프셋을 체크포인트로 관리하고 Kafka 컨슈머 그룹에 커밋하지 않는다. kafka-exporter 의 consumer group lag 은 이 잡에 대해 존재하지 않으므로 지표로 쓰지 않는다. Spark UI 의 Input Rate 는 "건수 ÷ 트리거 간격"이라 발행 속도가 아니다 (보조 확인용).

측정 명령 (서버1):
```
# 배치 N 의 종료 오프셋 — 연속 배치의 합계 차이 = 그 배치의 처리 건수
docker exec hdfs-namenode hdfs dfs -cat /ddubi/checkpoints/weather_replay/offsets/N | tail -1
# 배치 완료 시각·소요 (falling behind 경고의 spent 값)
docker logs spark-streaming-replay 2>&1 | grep -E "batch=|falling behind"
# 검산: 마지막 배치 종료 오프셋 합계 = Kafka 끝 오프셋 합계
docker exec kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server kafka:9092 --topic city.replay
```

## 실험 절차 (서버1, `~/S15P21E202/infra`)

모든 compose 명령은 `-f docker-compose.yml -f docker-compose.server1.yml` 을 붙인다 (아래 `DC` 로 약칭).

```
DC="docker compose -f docker-compose.yml -f docker-compose.server1.yml"
```

### 0. 준비 (실험 세션당 1회)
```
$DC stop spark-streaming-weather                 # 운영 스트리밍 잠시 중지
$DC --profile core exec kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --create --if-not-exists --topic city.replay --partitions 3
$DC --profile experiment up -d spark-streaming-replay
docker logs -f spark-streaming-replay            # "started topic=city.replay" 확인 후 Ctrl+C
```
PC 에서 터널: `ssh -L 3000:localhost:3000 j15e202.p.ssafy.io`(Grafana), `ssh -L 8080:localhost:8080 -L 4041:localhost:4041 j15e202a.p.ssafy.io`

### 1. 회차 시작 전 — 기준값 기록

마지막 배치 번호 N 과 그 종료 오프셋(`offsets/N`)을 기록한다. 이번 회차의 목표는 **기준 오프셋 합계 + 발행 총량**. 토픽은 회차마다 삭제하지 않는다(체크포인트가 이어 읽는다).

### 2. worker 수 설정 → 예열
```
$DC stop spark-worker-2 spark-worker-3                              # 1대 조건
$DC start spark-worker-2                                            # 2대 조건
$DC --profile experiment up -d spark-worker-2 spark-worker-3        # 3대 조건
```
Master UI(8080)에서 worker 수와 실행기 코어 수가 바뀐 것을 확인한 뒤, **반드시 예열 1회**:
```
$DC --profile replay run --rm replay-producer python -m replay \
  --source-topic city.raw --target-topic city.replay \
  --from-date 2026-08-31 --to-date 2026-09-03 --rate 500 --limit 1000
```
드라이버 로그에 `batch=` 가 찍히고 20초쯤 지난 뒤 본 측정으로. (새 executor 의 첫 배치가 측정에 섞이면 결과가 2배 느리게 나온다)

### 3. 부하 투입 (동일 파라미터 유지)
아카이브 1회 통과는 수천 건(city.raw 기준 약 5,800건)이라 `--repeat` 로 총량을 만든다. 예:
```
$DC --profile replay run --rm replay-producer python -m replay \
  --source-topic city.raw --target-topic city.replay \
  --from-date 2026-08-31 --to-date 2026-09-03 --rate 15000 --repeat 100 --limit 600000
```
- `--limit` 은 반복 포함 총량의 안전장치. rate·repeat·limit 은 모든 조건에서 **동일**하게
- rate 는 예열 회차에서 본 큰 배치 처리 속도의 1.5~2배 (처리 능력보다 낮으면 랙이 안 쌓인다)
- "완료 발행=…" 로그 시각을 기록한다

### 4. 측정
- 드라이버 로그의 마지막 `batch=N` 완료 시각 → **소진 시간 = 그 시각 − replay 완료 시각**
- `offsets/N` 으로 배치별 건수를 뽑아 총량 검산(= 발행 총량, 유실 0) 및 배치별 처리 속도 계산
- replay 가 돌던 시간과 겹친 배치는 CPU 경합으로 느리다 — 처리 속도 비교에서는 제외하고, 소진 시간에는 포함
- 같은 조건 **3회 반복**해 평균. 소진 시간은 10초 트리거 단위로 뭉치므로 1회 값에 의미를 두지 않는다

### 5. 장애 시연 (worker kill)
부하가 들어와 Spark UI의 처리 중인 배치가 보이는 상태에서:
```
$DC stop spark-worker-2     # Master UI: 실행기가 사라지고 남은 worker 에 재배치되는 것을 관찰
$DC start spark-worker-2    # 복구
```
쿼리가 실패하지 않고 남은 worker에서 계속 처리되는 것과, 복구 후 처리 속도가 돌아오는 것을 Spark UI에서 확인한다.

### 6. 저장 분산 시연 (datanode 정지)
서버2에서 `docker stop hdfs-datanode2` → 서버1에서 replay `--dry-run` 이 아카이브를 정상 읽는지 확인 → `docker start hdfs-datanode2`.
NameNode UI(9870) Datanode 탭에서 Live 1→2 로 돌아오는 것까지.

### 7. 정리 (실험 세션 종료)
```
$DC --profile experiment stop spark-streaming-replay
$DC start spark-streaming-weather                # 운영 스트리밍 복귀
$DC start spark-worker-2                          # worker 원상복구
$DC --profile experiment stop spark-worker-3
```
운영 복귀 확인: 서버2 Redis 의 `weather:latest:*` TTL 이 다음 수집 사이클에 갱신되는지.

## 기록 양식

| 회차 | worker | rate | repeat×limit | replay 완료 | 마지막 batch 완료 | 소진(s) | 큰 배치 처리속도(건/s) | 검산(총량 일치) |
|---|---|---|---|---|---|---|---|---|
| 1-1 | 1 | | | | | | | |
| 1-2 | 1 | | | | | | | |
| 1-3 | 1 | | | | | | | |
| 2-1 | 2 | | | | | | | |
| ... | | | | | | | | |

## 주의

- 파티션이 3개라 소비 병렬성 상한도 3. worker 4대 이상은 의미 없다 (실험 범위 1~3 인 이유). executor 당 코어 1(submit.sh 기본)이라 worker 수 = 동시 태스크 수
- **잡이 가벼우면 worker 를 늘려도 차이가 없다.** 배치 시간의 대부분이 건수와 무관한 고정비(태스크 배분·셔플·Redis 연결·체크포인트)면 코어를 늘려도 그대로다. 1차 실험의 weather 잡이 그랬다 — 레코드당 계산이 무거운 잡(segment.status)에서 측정한다
- 부하가 작으면 차이가 안 난다. 총량은 "1대 처리 능력 × 30초 이상", 투입 속도는 처리 능력 위로
- 운영 스트리밍을 끄고 2시간이 지나면 `weather:latest` 가 TTL 만료로 사라진다(사용자 없으면 무해, 복귀 시 첫 배치에서 복원). 실험이 길어지면 알고 있을 것
- `-f` 오버레이를 빠뜨리면 데이터노드2 이름 해석 실패로 replay 가 죽는다
- 실험 잡이 자원을 못 받으면("Initial job has not accepted any resources") 운영 스트리밍이 아직 worker 메모리를 쥐고 있는 것 — 순서는 운영 잡 정지 → 실험 잡 기동
- 실험용 판정 토픽: 나중에 segment.status 잡으로 실험할 때는 판정을 발행하는 토픽도 실험용으로 분리해야 한다(아카이버가 segment.status 를 구독하면 실험 판정이 HDFS 이력에 섞인다)
