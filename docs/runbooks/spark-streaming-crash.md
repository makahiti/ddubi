# Spark 스트리밍 잡이 죽었다

대상(서버1, 상시 5개): `spark-streaming-weather`(날씨 최신값), `spark-streaming-realtime`(버스·지하철 최신값과 지하철 Snapshot, 버스 Silver), `spark-streaming-city-realtime`(도시 실시간 정규화, 2026-09-18 추가), `spark-streaming-topis`(TOPIS 공지 GMS 구조화, 2026-09-21 추가, topis-ai 프로필), `spark-streaming-traffic-incident`(TOPIS 돌발 Silver, 2026-09-23 첫 가동, road-silver 프로필). dt 확인(2026-09-23)으로 상시 잡은 이 5개로 고정이고 새 잡 계획은 없다. 아래 명령은 weather 로 적었지만 잡 이름만 바꾸면 같다.

전에 정의돼 있던 `spark-streaming-segment-observations`·`spark-streaming-segment-status` 는 2026-09-23 dt 결정으로 segment-observations·segment-status 잡을 폐기했다(앞으로도 쓸 계획 없음).

topis 잡만 다른 점: GMS·카카오 API 키(`GMS_API_KEY`, `KAKAO_REST_API_KEY`)가 비어 있으면 기동 때 바로 죽고 재시작을 반복한다. 로그에 `ValueError` 와 키 이름이 찍힌다. 또 처리할 새 공지가 없으면 `topisRows=0` 이 정상이다(수집기가 같은 공지를 다시 보내지 않으므로).

executor 가 죽는 경우(2026-09-22): 드라이버 컨테이너는 멀쩡한데 워커 컨테이너(`spark-worker-1`·`2`) 안의 executor 가 메모리 상한에 걸려 exit 137 로 죽고 Spark 가 새 executor 를 띄운다. 이때는 이 절차가 아니라 워커의 `mem_limit` 과 그 안에 올라간 executor 수를 본다. 확인은 `docker top spark-worker-2 -eo pid,rss,args | grep CoarseGrainedExecutorBackend`(executor 별 RSS) 와 `sudo journalctl -k | grep -i "killed process"`.

## 증상

- Grafana 나 Redis 에서 `weather:latest:*` 값이 갱신되지 않는다 (TTL 2시간이 지나면 키가 사라진다)
- 마스터 UI(8080) Running Applications 에 잡이 없거나, Completed 에 FAILED 로 내려가 있다
- `docker ps` 에서 드라이버 컨테이너가 Restarting 이거나 Up 시간이 방금이다

## 먼저 확인 (서버1)

```
docker ps -a --format "{{.Names}}\t{{.Status}}" | grep spark-streaming
docker inspect spark-streaming-weather --format "restarts={{.RestartCount}} status={{.State.Status}} oom={{.State.OOMKilled}}"
docker logs spark-streaming-weather --tail 100 2>&1 | grep -E "ERROR|Exception|Caused by|not accepted any resources" | tail -10
```

읽는 법:

| 로그에 보이는 것 | 원인 | 조치 |
|---|---|---|
| `Initial job has not accepted any resources` 반복 | worker 에 executor 자리가 없다 (다른 잡이 메모리·코어를 다 쓰고 있음) | 아래 "자원 부족" |
| `SafeModeException` / `Cannot create directory ... checkpoints` | HDFS 네임노드가 세이프 모드다 (재시작 직후 1분쯤) | 기다리면 재시작 정책이 알아서 복귀한다 |
| `Connection refused` + Redis 주소 | 서버2 Redis 가 죽었거나 방화벽 문제 | 서버2에서 `docker ps | grep redis` 확인 |
| `OOMKilled=true` | 드라이버 컨테이너 메모리 초과 | compose 의 `mem_limit` 을 올리고 배포 |
| Kafka `TimeoutException` / broker 연결 실패 | Kafka 가 죽었다 | [kafka-restart.md](kafka-restart.md) |
| 아무 오류 없이 종료, `restarts` 가 늘지 않음 | 누가 `docker stop`/`docker kill` 했다. **도커는 사람이 API 로 멈춘 컨테이너를 재시작 정책 대상에서 뺀다** — `unless-stopped` 라도 스스로 안 뜬다 (2026-09-07 검증) | 아래 "수동 재시작" |

## 조치

**프로세스가 스스로 죽은 경우(OOM, 예외, 네트워크)는 기다리면 됩니다.** 드라이버 컨테이너는 `restart: unless-stopped` 라 죽으면 스스로 다시 뜨고, 잡은 체크포인트(`hdfs://.../checkpoints/<잡이름>`)에서 마지막으로 처리한 Kafka 오프셋을 읽어 그 다음부터 이어 갑니다. 죽어 있던 동안의 데이터는 Kafka 에 그대로 있으므로 유실되지 않습니다.

**수동 재시작** (사람이 stop 했거나, 재시작 정책이 멈춘 경우):
```
$DC1 --profile spark up -d spark-streaming-weather
```

**자원 부족**: 마스터 UI(8080)에서 worker 의 Memory·Cores 남은 양을 본다. 실험 잡이나 배치가 자리를 쥐고 있으면 그것을 먼저 끝낸다.
```
$DC1 --profile experiment stop spark-streaming-replay     # 실험 잡이 켜져 있었다면
```
운영 잡 두 개와 배치가 함께 들어가도록 worker 는 2560m·코어 2, 잡은 각 768m·코어 1 로 맞춰져 있다(2026-09-07). 이 배분을 바꾸려면 `.env.example` 의 Spark 자원 절을 따른다.

## 복구 확인

```
docker logs spark-streaming-weather --since 3m 2>&1 | grep -E "started topic|batch="
```
`started topic=city.raw checkpoint=...` 뒤에 `batch=N grids=14` 가 나오면 정상. 다음 수집 사이클(10분)까지 데이터가 없으면 batch 줄이 안 나올 수 있으니, 그때는 마스터 UI 에 RUNNING 으로 있는지만 본다.

유실 없이 이어 읽었는지 확인:
```
# 체크포인트가 기록한 마지막 오프셋 vs Kafka 끝 오프셋 — 다음 배치 뒤 둘이 같아져야 한다
N=$(docker exec hdfs-namenode hdfs dfs -ls /ddubi/checkpoints/weather_latest/offsets | awk '{print $8}' | sed 's#.*/##' | sort -n | tail -1)
docker exec hdfs-namenode hdfs dfs -cat /ddubi/checkpoints/weather_latest/offsets/$N | tail -1
docker exec kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server kafka:9092 --topic city.raw
```

## 예방·후속

- 배포나 재부팅 뒤 드라이버가 1~2회 재시작하는 것은 정상이다(HDFS·Kafka 가 늦게 뜨는 순서 문제). 3회 이상 반복되면 로그를 본다.
- 잡 하나가 죽어도 다른 잡은 영향이 없다. 드라이버 컨테이너가 잡마다 따로 있기 때문이다.
- Spark 지표를 Prometheus 에 붙이면(모니터링 확장 항목) 배치가 멈춘 것을 알림으로 받을 수 있다. 지금은 Redis TTL 이 유일한 간접 신호다.

## 연습 기록

장애 시뮬레이션 방법 (2026-09-07 검증으로 확정):
- `docker kill`/`docker stop` 은 "사람이 멈춤"으로 취급되어 재시작 정책이 동작하지 않는다. 검증에 쓰지 않는다.
- 컨테이너 안의 PID 1(자바 드라이버)은 같은 네임스페이스 안에서 보내는 신호를 무시한다. `kill -9 1` 도, `pkill -f SparkSubmit` 도 소용없다.
- **파이썬 드라이버 프로세스를 죽인다.** 자바가 파이썬 종료를 감지해 스스로 끝나고 컨테이너가 exit 137 로 죽어 재시작 정책이 동작한다.
  ```
  docker exec spark-streaming-weather sh -c 'kill -9 $(pgrep -x python3 | head -1)'
  ```
- 검증 스크립트에서 `docker logs --since` 는 `HH:MM:SS` 만 주면 안 되고 `2026-09-07T04:28:45Z` 처럼 완전한 시각을 써야 한다. 아니면 1줄만 나와 실패로 오독한다.

| 날짜 | 한 일 | 복구까지 | 유실 | 절차서 수정 |
|---|---|---|---|---|
| 2026-09-07 | 1-a: `docker kill spark-streaming-weather` → 재시작 정책이 살리지 않음(restarts 0). 11분 뒤 수동 `up -d` → 16초 만에 잡 시작, 37초에 밀린 14건 처리, 체크포인트 합계 = Kafka 합계 13,738 | 수동 재시작 후 37초 | 없음 | 증상 표에 kill/stop 구분 추가, 시뮬레이션 방법 명시 |
| 2026-09-07 | 1-b: 컨테이너 안 python3 드라이버 kill -9 → 컨테이너 exit 137 → **재시작 정책이 1초 만에 재기동**(restarts 1) → 16초 뒤 잡 started → 일회 수집 14건을 batch 976 으로 처리, 체크포인트 = Kafka 13,906 | 자동, 잡 재시작까지 17초 | 없음 | 시뮬레이션 방법 3줄로 정리 |
