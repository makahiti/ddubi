# HDFS 네임노드 메타데이터를 잃었다 (서버1 네임노드 볼륨 손실·서버1 사망)

> 상황: 서버1 이 죽었거나, 살아 있어도 `hdfs-namenode` 가 "이름 폴더가 없다/손상됐다" 로 뜨지 못한다. HDFS 블록(데이터 본체)은 두 서버에 복제 2 로 남아 있지만 "어느 블록이 어느 파일인지" 적힌 목차를 잃어 아무것도 읽을 수 없다.
> 검증: **격리 시험 묶음으로 검증(2026-09-14, 서버2)**. 확인된 것 — 세컨더리 사본 생성과 네임노드 되돌림, 이어받기 35~47초, 데이터노드 재등록, 읽기, 사본 뒤 파일이 빠지는 손실 경계, 세이프 모드 강제 해제, 남은 블록 정리와 쓰기 확인, 되돌리기 복원. 시험에서 찾은 결함 4개(잠금 순서, 세이프 모드 거부, 복원 권한, 블록 번호 겹침)는 스크립트와 이 절차서에 반영했다(7절 표). 운영 볼륨으로 연습한 적은 없다. 첫 연습 뒤 이 줄을 갱신한다.

## 0. 구조를 먼저 이해한다 (1분)

- HDFS 의 **블록**은 쓰는 순간 서버1·서버2 데이터노드에 함께 저장된다. 서버1 이 사라져도 서버2 에 전부 있다.
- **메타데이터**(fsimage + edits, 16MB 안팎)는 서버1 네임노드 볼륨에만 있다. 이것을 잃으면 블록이 있어도 못 읽는다.
- 그래서 서버2 에 두 가지를 두었다.
  1. `hdfs-secondarynamenode`(항상 켜짐): 매시간 서버1 에서 메타데이터를 받아 합쳐 서버2 볼륨 `hdfs-namesecondary-data` 에 둔다. **최대 1시간 전 사본.**
  2. `hdfs-namenode-standby`(평소 꺼짐): 그 사본으로 시작할 수 있는 예비 네임노드.
- 서버가 2대라 자동 이어받기(HA)는 만들 수 없다. 서버2 는 "서버1 이 죽었다" 와 "네트워크만 끊겼다" 를 구별할 수 없고, 잘못 켜면 네임노드가 둘이 되어 목차가 갈라진다. **그래서 사람이 확인하고 명령 한 줄로 켠다.**
- 잃는 것: 마지막 사본 뒤 최대 1시간 안에 새로 쓴 파일의 이름표. 그 파일들의 원본은 Kafka 에 21일 있으니 아카이버를 되감아 다시 만든다(4절).

## 1. 확인 — 정말 메타데이터를 잃었나

```bash
# 서버2 에서 서버1 네임노드 응답
curl -sf --max-time 5 http://<서버1 사설IP>:9870/jmx?qry=Hadoop:service=NameNode,name=NameNodeInfo >/dev/null && echo 살아있음 || echo 응답없음
```
- **응답 있음** → 이 절차서가 아니다. 데이터노드 문제면 [hdfs-datanode-down.md](hdfs-datanode-down.md).
- **응답 없음 + 서버1 자체가 ssh 로 안 들어감** → 서버1 사망. 2절로.
- **응답 없음 + 서버1 은 살아 있음** → 서버1 에서 원인을 본다.
  ```bash
  docker logs hdfs-namenode --tail 50
  docker exec hdfs-namenode ls /hadoop/dfs/name/current | head
  ```
  - 단순 컨테이너 문제(OOM·재시작 중)면 `docker restart hdfs-namenode` 로 끝난다. 이어받지 않는다.
  - "Directory /hadoop/dfs/name is in an inconsistent state" 류·VERSION 없음·디스크 오류 → 메타데이터 손실. 2절로. 이때는 서버1 네임노드가 **완전히 멈춰 있어야** 한다(`docker stop hdfs-namenode`).

먹통인 사이 아카이버·Spark 는 HDFS 쓰기 실패로 재시도하며 기다린다. Kafka 에 데이터는 남으니 서두르지 않는다.

## 2. 이어받기 — 서버2 에서 명령 한 줄

```bash
cd ~/S15P21E202/infra
bash scripts/failover-namenode.sh --yes            # 서버1 네임노드가 응답하면 멈춘다(안전장치)
bash scripts/failover-namenode.sh --yes --force    # 서버1 이 살아 있지만 네임노드 볼륨만 잃은 경우(1절에서 stop 한 뒤)
```
스크립트가 하는 일은 다섯 가지다. 서버1 네임노드가 응답하지 않는지 확인한다. 세컨더리를 멈춰 사본 폴더의 잠금을 푼다. `.env` 에 `HDFS_NAMENODE_HOST=<서버2 IP>` 를 써서 데이터노드2가 예비를 보게 한다. 예비 네임노드를 켜고(이름 폴더가 비어 있으면 사본에서 `-importCheckpoint`) 데이터노드2를 다시 만든다. 세이프 모드가 풀릴 때까지 기다리되, 마지막 사본 뒤에 쓰인 파일의 블록 때문에 HDFS 가 스스로 나오지 않으면 강제로 해제한다(7절 표).

기대: 1분 안에 `OK: 예비 네임노드가 동작한다`(9/14 시험 35~47초), 데이터노드 1대(서버1 죽음) 또는 2대(서버1 살아 있고 4절까지 한 뒤). 출력에 "강제 해제 1" 이 보이면 사본 뒤 파일이 있었다는 뜻이니 4절의 되감기가 꼭 필요하다.

## 3. 확인

```bash
docker exec hdfs-namenode-standby hdfs dfsadmin -report | head -20        # Live datanodes, Missing blocks
docker exec hdfs-namenode-standby hdfs dfs -ls /ddubi/archive | tail -5    # 목차가 보이나
docker exec hdfs-namenode-standby hdfs fsck / | tail -5                    # 서버1 죽었으면 Under-replicated 다수·Missing 0 이 정상
```
- Missing blocks 가 0 이 아니면: 마지막 사본 뒤 만들어진 파일 중 블록이 서버1 에만 있던 것. 4절에서 되감기로 다시 만든다.

## 4. 서버1 이 살아 있다면 — 쓰는 쪽을 서버2 네임노드로 돌린다

서버1 안에서는 `hdfs-namenode` 가 compose 서비스 이름이라 도커 DNS 가 죽은 로컬 네임노드를 가리킨다. 세 번째 오버레이로 서버2 IP 를 덮어쓴다.
```bash
cd ~/S15P21E202/infra
docker stop hdfs-namenode                                    # 이미 멈췄으면 무해
echo "HDFS_NAMENODE_HOST=<서버2 사설IP>" >> .env
docker compose -f docker-compose.yml -f docker-compose.server1.yml -f docker-compose.failover-server1.yml \
  --profile hdfs --profile archive --profile spark up -d --force-recreate \
  hdfs-datanode1 archiver spark-streaming-weather spark-streaming-realtime
docker logs archiver --tail 20      # "wrote" 가 다시 나오나
```
**최근 1시간치 아카이브 다시 만들기**: 아카이버 컨슈머 그룹 `archiver` 의 위치를 마지막 체크포인트 시각으로 되감는다.
```bash
docker stop archiver
docker exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --group archiver \
  --reset-offsets --to-datetime <마지막 사본 시각, 예 2026-09-14T06:00:00.000> --all-topics --execute
docker start archiver
```
아카이버는 같은 시간 창 파일을 다시 쓰므로 중복이 아니라 덮어쓰기가 된다(경로가 시간 단위). 체크포인트 시각은 서버2 `docker logs hdfs-secondarynamenode | grep -i "checkpoint" | tail -1` 에서 본다.

## 5. 되돌리기 — 서버1 을 복구한 뒤

예비는 임시다. 서버1 네임노드 볼륨이 새로 생기면 **예비가 운영 중 받은 변경**을 서버1 로 옮겨야 한다. 방향이 바뀐 것뿐이라 같은 방법을 쓴다.
1. 쓰기를 멈춘다: 서버1 `docker stop archiver spark-streaming-weather spark-streaming-realtime`.
2. 예비의 최신 메타데이터를 꺼낸다: 서버2 `docker exec hdfs-namenode-standby hdfs dfsadmin -saveNamespace` 는 세이프 모드가 필요하므로 대신
   ```bash
   docker exec hdfs-namenode-standby hdfs dfsadmin -safemode enter
   docker exec hdfs-namenode-standby hdfs dfsadmin -saveNamespace
   docker exec hdfs-namenode-standby tar -C /hadoop/dfs/name -cf - current | gzip > ~/namenode-from-standby.tar.gz
   docker stop hdfs-namenode-standby
   ```
3. 서버1 에 넣는다: 새 볼륨이 빈 상태에서. **`--user root` 가 필수**다(이미지 기본 사용자 hadoop 은 도커가 만든 볼륨에 못 쓴다 — 9/14 시험에서 `Cannot mkdir: Permission denied` 로 복원이 조용히 실패했다).
   ```bash
   scp ubuntu@<서버2>:~/namenode-from-standby.tar.gz /tmp/
   docker run --rm --user root -v ddubi_hdfs-namenode-data:/hadoop/dfs/name -v /tmp:/src --entrypoint sh apache/hadoop:3.4.1 \
     -c 'cd /hadoop/dfs/name && tar xzf /src/namenode-from-standby.tar.gz && test -f current/VERSION && echo "복원 OK" || echo "복원 실패"'
   ```
   **"복원 OK" 가 안 나오면 네임노드를 켜지 않는다.** compose 의 네임노드 명령은 `current/VERSION` 이 없으면 새로 format 하는데, 그러면 클러스터 ID 가 바뀌어 데이터노드 두 대가 "Incompatible clusterIDs" 로 등록을 거부하고 목차가 빈 HDFS 가 뜬다(9/14 시험에서 재현). 블록은 그대로 남아 있으니 네임노드를 멈추고 볼륨을 지운 뒤 복원부터 다시 하면 된다.
4. 양쪽 `.env` 에서 `HDFS_NAMENODE_HOST` 줄을 지운다. 서버2 `.env.bak-failover-*` 가 원본이다.
5. 서버1: 오버레이 없이 정상 기동 `docker compose -f docker-compose.yml -f docker-compose.server1.yml --profile hdfs --profile archive --profile spark up -d --force-recreate hdfs-namenode hdfs-datanode1 archiver spark-streaming-weather spark-streaming-realtime`.
6. 서버2: `docker compose -f docker-compose.yml -f docker-compose.server2.yml --profile hdfs2 up -d --force-recreate hdfs-datanode2 hdfs-secondarynamenode`. 예비의 이름 볼륨은 비운다: `docker volume rm ddubi_hdfs-namenode-standby-data`(다음 failover 때 사본에서 새로 import 하도록).
7. 3절 확인 명령을 서버1 `hdfs-namenode` 로 다시 돌린다.

## 6. 예방 상태 확인 (평소)

- Grafana 알림 "HDFS 체크포인트 멈춤" 이 없어야 한다. 손으로 보려면 서버1 `docker exec hdfs-namenode ls -lt /hadoop/dfs/name/current | grep fsimage | head -2` — 가장 새 fsimage 가 1시간 안쪽.
- 서버1 `~/backups/hdfs-name/backup.log` 마지막 줄이 `OK` 이고 WARN 이 없어야 한다(매일 04:20).
- 서버2 `docker volume inspect ddubi_hdfs-namesecondary-data` 가 존재하고 `docker exec hdfs-secondarynamenode ls /hadoop/dfs/namesecondary/current | grep fsimage` 가 나와야 한다.

## 7. 실패 경로 — 이 절차가 안 될 때

| 증상 | 원인 | 조치 |
|---|---|---|
| 스크립트 1) 에서 "서버1 네임노드가 살아 있다" 로 멈춤 | 진짜 죽지 않음 | 이어받지 않는다. 1절로 돌아가 원인을 본다 |
| 예비 로그 "Unable to acquire file lock on path /hadoop/dfs/namesecondary/in_use.lock ... another node ...@hdfs-secondarynamenode has already locked" 뒤 종료(exit 1) | 세컨더리가 아직 사본 폴더를 잡고 있음. 스크립트를 쓰지 않고 손으로 예비를 켰을 때 생긴다(9/14 시험 재현) | `docker stop hdfs-secondarynamenode` 뒤 예비 컨테이너를 지우고(이름 볼륨도 비운다) 다시 켠다 |
| 예비 로그 "No image directories available"/"namesecondary/current 없음" | 세컨더리가 한 번도 체크포인트를 못 만들었음 | 서버1 일일 tar 백업을 서버2 로 가져와 `ddubi_hdfs-namenode-standby-data` 볼륨에 풀고(5절 3번과 같은 방법) 예비를 켠다 |
| 예비 로그 "Refusing to leave safe mode without a force flag. Exiting safe mode will cause a deletion of N byte(s)" | 마지막 사본 뒤에 쓰인 파일의 블록이 데이터노드에 남아 있는데 목차에 없다. HDFS 는 이 블록이 지워질까 봐 세이프 모드에서 스스로 나오지 않는다(9/14 시험 재현). **기다려도 풀리지 않는다** | 스크립트가 이 문구를 보면 `hdfs dfsadmin -safemode forceExit` 로 강제 해제한다. 지워지는 블록은 이름표를 잃은 파일이고 원본은 Kafka 에 있다. 손으로 할 때도 같은 명령 |
| 이어받기 직후 예비에 쓰기가 "could only be written to 0 of the 1 minReplication nodes ... node(s) are excluded" 로 실패, 데이터노드 로그에 "ReplicaAlreadyExistsException ... already exists in state FINALIZED" | **원인 확인(9/14 시험)**. 예비는 옛 사본으로 시작해 "다음 블록 번호" 도 옛 값인데, 데이터노드에는 사본 뒤 파일의 블록이 그 번호로 남아 있다. 네임노드는 첫 블록 보고에서 모르는 블록을 지우지 않고 다음 전체 보고(기본 6시간)에서 지우므로 그때까지 번호가 겹친다 | 스크립트 6단계가 `hdfs dfsadmin -triggerBlockReport <데이터노드>:9867` 로 전체 보고를 바로 시켜 남은 블록을 지우고, 시험 파일을 써서 확인한다. 손으로 할 때도 같은 명령. 실패해도 시도마다 번호가 하나 앞서가므로 몇 번 더 쓰면 성공한다 |
| 세이프 모드가 180초 안에 안 풀림(위 문구 없음) | 데이터노드가 아직 등록 안 됨(서버1 죽어 1대뿐) | `docker logs hdfs-datanode2` 로 등록 여부 확인. 등록됐는데도 안 풀리면 `hdfs dfsadmin -safemode forceExit`. Under-replicated 는 정상 |
| 되돌린 원래 네임노드가 세이프 모드에 머묾 | **원인 확인(9/14 시험)**. 위 남은 블록이 아직 지워지지 않은 채 네임노드를 되돌리면, 네임노드가 아는 번호보다 큰 블록을 "미래의 데이터" 로 보고 나오지 않는다. 스크립트 6단계가 남은 블록을 지우고 나면 생기지 않는다 | 로그에 "Refusing to leave safe mode" 가 있으면 `hdfs dfsadmin -safemode forceExit`. 세컨더리는 네임노드가 세이프 모드인 동안 체크포인트에 실패하니(로그 "Exception in doCheckpoint") 해제 뒤 다음 주기를 기다린다 |
| 복원 뒤 네임노드가 빈 목차로 뜨고 데이터노드 로그에 "Incompatible clusterIDs" | 복원이 실패한 채(VERSION 없음) 네임노드를 켜서 새로 format 됨 | 네임노드 정지 → 이름 볼륨 삭제 → 5절 3번을 `--user root` 로 다시. 데이터노드 블록은 무사하다 |
| datanode2 가 예비에 안 붙음 | `.env` 의 HDFS_NAMENODE_HOST 가 안 반영됨 | `docker exec hdfs-datanode2 getent hosts hdfs-namenode` 가 서버2 IP 인지. 아니면 datanode2 `--force-recreate` |
