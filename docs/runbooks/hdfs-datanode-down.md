# HDFS 데이터노드 한 대가 죽었다

대상: `hdfs-datanode1`(서버1), `hdfs-datanode2`(서버2). 복제 계수가 2 이므로 한 대가 죽어도 모든 블록의 복사본이 다른 쪽에 남아 있다.

## 증상

- 네임노드 UI(9870) Datanodes 탭에서 Live 가 2 → 1, 잠시 뒤 Dead 1
- `hdfs dfsadmin -report` 에 `Under replicated blocks` 가 0 에서 전체 블록 수 근처로 올라간다
- 아카이버·Spark 는 계속 동작한다. 로그에 데이터노드 연결 실패 WARN 이 몇 줄 찍힐 수 있다

## 먼저 확인 (서버1)

```
docker exec hdfs-namenode hdfs dfsadmin -report | grep -E "Live datanodes|Dead datanodes|Under replicated|Missing blocks:"
docker exec hdfs-namenode hdfs dfsadmin -safemode get
# 죽은 쪽 서버에서
docker ps -a --format "{{.Names}}\t{{.Status}}" | grep hdfs-datanode
docker inspect hdfs-datanode2 --format "restarts={{.RestartCount}} oom={{.State.OOMKilled}} exit={{.State.ExitCode}}"
docker logs hdfs-datanode2 --tail 50 2>&1 | grep -E "ERROR|Exception|Out of memory" | tail -5
```

읽는 법:

| 보이는 것 | 뜻 |
|---|---|
| Live 2 인데 한 노드의 `Last contact` 만 오래됨 | 노드가 죽은 직후 ~10분 30초. 네임노드는 아직 살아 있다고 믿는다. **이 시간 동안 WebHDFS 읽기(아카이버·replay)가 그 노드로 보내져 실패한다** (2026-09-07 검증). Spark 의 hdfs:// 쓰기는 다른 노드로 우회해 정상 |
| Live 1, Missing blocks 0 | 네임노드가 죽은 노드를 인지한 뒤. 모든 데이터가 남은 노드에 있다. 서비스 영향 없음 |
| **Missing blocks > 0** | 복사본이 하나도 없는 블록이 있다. 복제가 2 미만으로 쓰인 파일이 있었다는 뜻. 죽은 노드를 살리는 것이 최우선 |
| `OOMKilled=true` | 자바 8 이 컨테이너 메모리 제한을 못 읽는 문제. `HADOOP_HEAPSIZE_MAX` 가 compose 에 있는지 확인 (2026-09-03 에 512m 으로 명시) |
| 네임노드가 세이프 모드 ON | 네임노드가 막 재시작한 것. 블록 보고가 모이면(1~2분) 스스로 해제된다. 그동안 쓰기가 거부되므로 Spark 가 재시작할 수 있다 |

## 조치

1. **죽은 노드를 다시 띄운다.** 재시작 정책이 이미 띄우고 있을 가능성이 크다. 아니면:
```
# 서버2 예시
$DC2 --profile hdfs2 up -d hdfs-datanode2
```
2. **복제가 회복되는지 본다.** 노드가 돌아오면 자기 블록을 네임노드에 보고하고 Under replicated 가 줄어 0 이 된다 (수 분).
3. 노드가 오래(10분 이상) 죽어 있었다면 네임노드가 Dead 로 판정하고 남은 노드에 복사본을 다시 만들기 시작한다. 우리는 노드가 2대라 복사할 곳이 없으므로 Under replicated 로 남고, 죽은 노드가 돌아오면 정리된다.
4. **디스크나 하드웨어 문제로 노드를 복구할 수 없다면** 데이터노드 볼륨(`ddubi_hdfs-datanode2-data`)을 지우고 빈 상태로 다시 띄운다. 네임노드가 남은 노드의 복사본을 새 노드에 채운다. 복제 2 덕분에 데이터는 보존된다.

## 복구 확인

```
docker exec hdfs-namenode hdfs dfsadmin -report | grep -E "Live datanodes|Under replicated"   # Live 2, Under replicated 0
docker exec hdfs-namenode hdfs fsck / | grep -E "Missing|Corrupt|status"                     # Missing 0, Corrupt 0, HEALTHY
```
장애 중 아카이브 읽기·쓰기가 계속됐는지:
```
docker logs archiver --since 1h 2>&1 | grep -E "wrote|committed|ERROR" | tail -3
$DC1 --profile replay run --rm replay-producer python -m replay --source-topic city.raw --target-topic city.replay --from-date $(date +%F) --to-date $(date +%F) --rate 1000 --limit 100 --dry-run   # 읽기 검증 (발행 안 함)
```

## 예방·후속

- **죽은 직후의 읽기 실패**가 9/7 연습에서 드러난 약점이었다. 원인은 네임노드의 사망 판정 지연(heartbeat.recheck 5분 × 2 + 30초 = 10분 30초)과, WebHDFS 클라이언트(fsspec)가 리다이렉트된 데이터노드 접속 실패 시 다른 복제본으로 재시도하지 않는 것.
- **2026-09-09 보완(적용)**: ① `dfs.namenode.heartbeat.recheck-interval` 30000 → 사망 판정 약 1분 30초 ② `dfs.namenode.avoid.read.stale.datanode` true → 심장박동 30초 끊긴 노드는 읽기 대상에서 뒤로 밀림(리다이렉트가 죽은 노드로 가는 창이 30초로 줄어듦) ③ 아카이버 쓰기·replay 읽기에 재시도 3회(2·4·8초) ④ 네임노드 헬스체크(세이프 모드 해제 = healthy). 따라서 위 증상 표의 "10분 30초" 는 "약 1분 30초(읽기 우회는 30초)" 로 읽는다. 다음 연습에서 재측정해 표를 고친다.
- HDFS 에 파일을 쓰는 클라이언트마다 복제 계수를 2 로 맞춰야 한다. Spark 는 `spark-defaults.conf` 의 `spark.hadoop.dfs.replication 2`, 아카이버는 네임노드 기본값. 새 클라이언트가 생기면 확인한다.
- 데이터노드 메모리 상한(1GiB)과 힙(512m)은 자바 8·cgroup v2 문제로 명시해 두었다. 이 값은 지우지 않는다.
- HDFS 설정 xml 은 단일 파일 마운트라 고친 뒤 컨테이너를 재생성해야 반영된다 (`up -d --force-recreate`).
- Grafana 에 데이터노드 수·Under replicated 패널과 알림을 붙이는 것이 모니터링 확장 항목이다.

## 연습 기록

| 날짜 | 한 일 | 복구까지 | 유실 | 절차서 수정 |
|---|---|---|---|---|
| 2026-09-03 | 네임노드 OOM 자동 재시작 (실제 장애) — 데이터노드가 아니라 네임노드였음. 힙 512m 명시로 해결 | 자동 | 없음 | |
| 2026-09-07 | 3: dn2 `docker stop` 83초. 그동안 네임노드는 Live 2 유지(Last contact 만 멈춤). **WebHDFS 읽기(replay dry-run) 즉시 실패**(dn2 로 리다이렉트, connection refused). Spark 체크포인트 쓰기는 정상(batch 979). dn2 재기동 후 5초 만에 Live 2. 그 사이 쓰인 블록 4개가 fsck 에서 Under-replicated 로 남았다가 약 10분 뒤 0 으로 회복 | 재기동 후 5초 | 없음 (읽기 실패는 일시적) | 증상 표에 "죽은 직후 10분" 행 추가, 예방 항목 추가 |
