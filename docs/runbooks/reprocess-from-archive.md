# 데이터를 다시 계산해야 한다 (재처리)

원본은 HDFS 아카이브(`/ddubi/archive/<토픽>/date=/hour=/`)에 영구 보관되고, Redis·기준선은 그 원본에서 다시 만들 수 있는 결과물이다. 그래서 결과가 잘못됐거나 사라졋으면 원본으로 다시 계산한다.

## 언제 필요한가

| 상황 | 재처리 방법 |
|---|---|
| 기준선(baseline)이 틀렸다, 로직을 고쳤다, 기간을 바꿨다 | **A. 배치 재실행** — 아카이브를 읽어 다시 계산한다. 가장 흔하고 안전 |
| Redis 최신값이 비었다 (TTL 만료, Redis 볼륨 손실) | 기다리면 된다. 다음 수집 사이클(10분)에 Spark 가 새 값을 쓴다. 재처리 불필요 |
| Kafka 데이터 볼륨을 잃어 Spark·아카이버가 읽을 원본이 없다 | Kafka 는 대기열일 뿐이라 잃어도 아카이브가 원본. 새 데이터부터 다시 쌓이면 된다. 과거 구간이 Redis 에 필요하면 **B** |
| 과거 특정 시간대를 스트리밍 잡에 다시 통과시켜야 한다 | **B. replay 재생** — 아카이브를 Kafka 토픽에 다시 발행해 Spark 가 읽게 한다 |
| Spark 스트리밍 잡의 로직을 고쳐 과거 데이터에도 적용해야 한다 | 원칙적으로 A 의 영역(배치). 스트리밍 최신값은 "지금 값"이라 과거 재적용 의미가 없다 |

## A. 기준선 배치 재실행 (서버1)

```
$DC1 --profile batch --profile spark run --rm spark-batch sh submit.sh batch/weather_baseline.py --days 28
```
- 28일 창의 아카이브를 읽어 격자·요일·시간대 기준선을 계산하고 `hdfs://.../ddubi/baseline/weather` 에 덮어쓴다. 1분 안에 끝난다 (2026-09-07 실측 55초, 2,142행).
- 자원: executor 480m·코어 2 가 worker 에 남아 있어야 한다. `Initial job has not accepted any resources` 가 반복되면 [spark-streaming-crash.md](spark-streaming-crash.md) 의 자원 부족 항목.
- 결과 확인: `docker exec hdfs-namenode hdfs dfs -ls /ddubi/baseline/weather` 에 새 시각의 parquet 와 `_SUCCESS`, 복제 계수 2 (`hdfs dfs -stat %r`).
- backend 업로드(방법 2)는 2026-09-08 실물 연결이 확인됐다(서버1 → 서버2 사설 IP /internal). weather 기준선은 아직 HDFS 까지만 쓰고, 구간(segment) 기준선 배치가 생기면 업로드·activate 까지 한다.
- **급감 거절이 났을 때**(activate 409 `ROW_COUNT_DROPPED`): 배치가 중간에 끊긴 게 아닌지 먼저 본다(업로드 로그의 versionTotal 과 배치 행 수). 노선 폐지 같은 정상 축소가 확실하면 서버1 `.env` 에 `BASELINE_ALLOW_SHRINK=true` 와 `BASELINE_SHRINK_REASON=<이유>` 를 넣고 배치를 한 번 다시 돌린 뒤 **두 값을 반드시 비운다**. 이유는 backend 로그와 pipeline_config 에 남는다.

## B. 아카이브를 Kafka 로 재생 (replay)

```
$DC1 --profile replay run --rm replay-producer python -m replay \
  --source-topic city.raw --target-topic city.replay \
  --from-date 2026-09-01 --to-date 2026-09-02 --rate 500 --limit 100000
```
- `--source-topic`: 아카이브의 어느 토픽 폴더를 읽나. `--target-topic`: 어느 Kafka 토픽에 다시 넣나.
- `--rate`(초당 건수) 또는 `--speed`(원본 시간 배속) 중 하나. `--limit` 은 안전장치. `--repeat N` 은 부하 실험용 반복. `--dry-run` 은 읽기만 하고 발행하지 않는다.
- **운영 토픽(city.raw)에 직접 재생하지 않는다.** 아카이버가 그 토픽을 구독하므로 같은 데이터가 HDFS 에 두 번 쌓이고, 기준선 배치가 오염된다. 재생은 전용 토픽(city.replay)으로 하고, 그 토픽을 읽는 잡(실험용 `spark-streaming-replay`, Redis 접두사 `weather:replay`)으로 결과를 만든다.
- 운영 Redis(`weather:latest`)에 과거 구간을 채워야 하는 아주 예외적인 경우에만 city.raw 에 재생하되, 그 전에 아카이버를 멈추고(`$DC1 --profile archive stop archiver`) 끝난 뒤 아카이버의 커밋 오프셋을 재생 구간 뒤로 옮긴다. 이 경로는 검증되지 않았으므로 인프라 담당이 직접 한다.
- 최신값 잡은 발생시각(occurredAt)이 더 늦은 것만 남기므로, 과거 데이터를 재생해도 현재값을 덮어쓰지 않는다.

## 재처리 뒤 확인

```
# A: 기준선 파일 시각·행 수는 배치 로그의 "wrote rows=N grids=14"
# B: 재생 로그의 "완료 발행=N 성공=N 실패=0", 대상 토픽 오프셋 증가, 읽는 잡의 batch 로그와 Redis 키
docker exec kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server kafka:9092 --topic city.replay
```

## 원본이 온전한지 확인하는 법

- 아카이브 파일명의 숫자(`city.raw-20260903T13-000000000415-...`)는 파티션 0 의 시작 오프셋이다. 같은 날짜 폴더에서 연속 파일의 이 숫자가 끊기지 않으면 빠진 구간이 없다.
- `--dry-run` 재생은 모든 파일을 실제로 열어 읽으므로 Parquet 손상 여부까지 확인된다.

## 연습 기록

| 날짜 | 한 일 | 소요 | 결과 | 절차서 수정 |
|---|---|---|---|---|
| 2026-09-02 | B 최초 실측: 이틀치 4,057건을 city.replay 로 500/s 재생 | 10초 | 무손실 | dn2 매핑·오버레이 필수 |
| 2026-09-07 | A: 배치 재실행 (자원 재배분 검증) | 55초 | 2,142행, 복제 2 | |
| 2026-09-07 | B: 실험 잡 기동(운영 잡 2개와 동시, 자원 재배분 덕에 정지 불필요) → 9/6 하루치 2,000건을 300/s 로 city.replay 재생 → 27초 뒤 batch 18 grids 14 → Redis `weather:replay:*` 14키가 9/6 23:00 값으로 갱신, 운영 키 무영향 → 실험 잡 정지 | 재생 10초 + 처리 27초 | 없음 | 절차 그대로 동작 |
