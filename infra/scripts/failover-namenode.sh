#!/usr/bin/env bash
# 서버1 네임노드를 잃었을 때, 서버2 의 예비 네임노드(hdfs-namenode-standby)로 HDFS 를 이어받는 스크립트.
# 서버2 의 infra/ 폴더에서 실행한다.
#
#   bash scripts/failover-namenode.sh --yes            서버1 네임노드가 응답하지 않는 것을 확인한 뒤 진행한다.
#   bash scripts/failover-namenode.sh --yes --force    서버1 네임노드가 응답해도 진행한다. 서버1 은 살아 있는데
#                                                      네임노드 볼륨만 잃은 경우에 쓴다. 먼저 서버1 네임노드를 멈춰야 한다.
#
# 왜 자동으로 돌리지 않는가.
#   서버2 는 "서버1 이 죽었다" 와 "서버1 과 네트워크만 끊겼다" 를 구별할 수 없다. 네트워크만 끊긴 상태에서 예비를 켜면
#   네임노드가 둘이 되어 각자 다른 목차를 만들고, 나중에 하나를 버려야 한다. 그래서 판단은 사람이 하고 실행만 이 스크립트가 한다.
#
# 이 스크립트가 하는 일.
#   1. 서버1 네임노드가 정말 응답하지 않는지 확인한다. --force 를 주면 이 확인을 건너뛴다.
#   2. 세컨더리 네임노드를 멈춘다. 세컨더리가 사본 폴더에 잠금을 잡고 있어서, 멈추지 않으면 예비가 그 폴더를 읽지 못한다.
#   3. .env 에 HDFS_NAMENODE_HOST 로 서버2 자기 IP 를 써 넣는다. 데이터노드2가 예비를 네임노드로 보게 되고,
#      이후 배포에서도 이 설정이 유지된다.
#   4. 예비 네임노드를 켜고 데이터노드2를 다시 만든다. 예비의 이름 폴더가 비어 있으면 세컨더리 사본에서 목차를 가져온다.
#   5. 세이프 모드가 풀릴 때까지 기다린다. 마지막 사본 뒤에 쓰인 파일이 있으면 그 파일의 블록이 "목차에 없는 블록" 으로
#      남아 HDFS 가 세이프 모드에서 스스로 나오지 않는다. 그 경우에만 강제로 나온다. 그 블록들은 이름표를 잃은 파일이고,
#      원본은 Kafka 에 남아 있어 아카이버가 다시 만든다. 9/14 시험에서 이 상황을 재현해 확인했다.
#
# 되돌리기(서버1 복구 뒤)는 docs/runbooks/hdfs-namenode-metadata-loss.md 5절에 있다.
set -euo pipefail
cd "$(dirname "$0")/.."   # infra/ 로 이동

yes=0; force=0
for a in "$@"; do
  case "$a" in
    --yes) yes=1 ;;
    --force) force=1 ;;
    *) echo "알 수 없는 인자: $a" >&2; exit 2 ;;
  esac
done
if [ "$yes" != 1 ]; then
  echo "이 스크립트는 HDFS 네임노드를 서버2 로 옮긴다. 진행하려면 --yes 를 붙인다." >&2; exit 2
fi
[ -f .env ] || { echo "FAIL: infra/.env 가 없다" >&2; exit 1; }

server1_ip="$(grep -E '^SERVER1_PRIVATE_IP=' .env | cut -d= -f2- | tr -d '\r')"
[ -n "$server1_ip" ] || { echo "FAIL: .env 에 SERVER1_PRIVATE_IP 가 없다" >&2; exit 1; }
my_ip="$(hostname -I | awk '{print $1}')"
[ -n "$my_ip" ] || { echo "FAIL: 서버2 사설 IP 를 알 수 없다" >&2; exit 1; }

dc() { docker compose -f docker-compose.yml -f docker-compose.server2.yml "$@"; }
# 컨테이너 이름은 운영 기본값을 쓴다. 격리된 시험 묶음에서 돌릴 때만 환경변수로 바꾼다.
SNN_CONTAINER="${SNN_CONTAINER:-hdfs-secondarynamenode}"
STANDBY_CONTAINER="${STANDBY_CONTAINER:-hdfs-namenode-standby}"
nn_jmx() { docker exec "$STANDBY_CONTAINER" curl -sf "http://localhost:9870/jmx?qry=Hadoop:service=NameNode,name=$1" 2>/dev/null; }

echo "1) 서버1 네임노드($server1_ip:9870) 응답 확인"
if curl -sf --max-time 5 "http://$server1_ip:9870/jmx?qry=Hadoop:service=NameNode,name=NameNodeInfo" >/dev/null; then
  if [ "$force" = 1 ]; then
    echo "   서버1 네임노드가 응답한다. --force 라서 계속한다. 서버1 네임노드는 반드시 먼저 멈춰 두어야 한다."
  else
    echo "FAIL: 서버1 네임노드가 살아 있다. 정말 잃은 것이 맞으면 서버1 에서 멈춘 뒤 --force 로 다시 실행한다." >&2; exit 1
  fi
else
  echo "   응답이 없다. 이어받기를 진행한다."
fi

echo "2) 세컨더리 네임노드 정지"
docker stop "$SNN_CONTAINER" >/dev/null 2>&1 || echo "   이미 꺼져 있다."

echo "3) .env 에 HDFS_NAMENODE_HOST=$my_ip 기록"
cp .env ".env.bak-failover-$(date +%Y%m%d-%H%M)"
if grep -qE '^HDFS_NAMENODE_HOST=' .env; then
  sed -i "s/^HDFS_NAMENODE_HOST=.*/HDFS_NAMENODE_HOST=$my_ip/" .env
else
  printf '\n# failover-namenode.sh 가 기록했다. 네임노드가 서버2 예비로 옮겨진 상태다. 서버1 을 복구한 뒤 이 줄을 지운다.\nHDFS_NAMENODE_HOST=%s\n' "$my_ip" >> .env
fi

echo "4) 예비 네임노드 기동, 데이터노드2 재생성"
dc --profile hdfs2 --profile hdfs-standby up -d hdfs-namenode-standby hdfs-datanode2

echo "5) 세이프 모드 해제 대기 (최대 180초)"
forced=0
for i in $(seq 1 180); do
  if nn_jmx NameNodeInfo | grep -q '"Safemode" *: *""'; then break; fi
  # 데이터노드 보고가 끝난 뒤에도 HDFS 가 "나가면 블록이 지워진다" 며 세이프 모드에 머무는 경우.
  # 사본 뒤에 쓰인 파일의 블록이 목차에 없어서 생기는 일이고, 이 상황에서는 기다려도 풀리지 않는다.
  if docker logs "$STANDBY_CONTAINER" 2>&1 | grep -q 'Refusing to leave safe mode'; then
    bytes="$(docker logs "$STANDBY_CONTAINER" 2>&1 | grep -oE 'deletion of [0-9]+ byte' | tail -1 | grep -oE '[0-9]+')"
    echo "   목차에 없는 블록(${bytes:-?} 바이트)이 데이터노드에 남아 있어 HDFS 가 스스로 세이프 모드를 풀지 않는다."
    echo "   이 블록은 마지막 사본 뒤에 쓰인 파일의 것이다. 원본은 Kafka 에 있으니 강제로 나온다."
    docker exec "$STANDBY_CONTAINER" hdfs dfsadmin -safemode forceExit >/dev/null
    forced=1
    sleep 3
    break
  fi
  sleep 1
done
if ! nn_jmx NameNodeInfo | grep -q '"Safemode" *: *""'; then
  echo "FAIL: 세이프 모드가 풀리지 않았다. 데이터노드가 등록되지 않았을 수 있다. docker logs $STANDBY_CONTAINER 와 docker logs hdfs-datanode2 를 본다." >&2
  exit 1
fi
live="$(nn_jmx FSNamesystem | grep -oE '"NumLiveDataNodes" *: *[0-9]+' | grep -oE '[0-9]+$')"
echo "OK: 예비 네임노드가 동작한다 (${i}초, 강제 해제 ${forced}). 살아 있는 데이터노드 ${live:-?}대"

echo "6) 남은 블록 정리와 쓰기 확인"
# 예비는 옛 사본으로 시작하므로 "다음 블록 번호" 도 옛 값이다. 데이터노드에는 사본 뒤에 쓰인 파일의 블록이 그 번호로 남아 있어,
# 새 파일을 쓰면 번호가 겹쳐 데이터노드가 거부한다. 네임노드는 첫 블록 보고에서는 모르는 블록을 지우지 않고 다음 전체 보고(기본 6시간)
# 에서 지우므로, 여기서 전체 보고를 바로 시켜 남은 블록을 지우게 한다. 9/14 시험에서 원인을 확인한 내용이다.
for dn in $(docker exec "$STANDBY_CONTAINER" hdfs dfsadmin -report 2>/dev/null | grep -oE '^Hostname: .*' | awk '{print $2}'); do
  docker exec "$STANDBY_CONTAINER" hdfs dfsadmin -triggerBlockReport "$dn:9867" >/dev/null 2>&1 && echo "   $dn 에 전체 블록 보고 요청" || echo "   $dn 블록 보고 요청 실패(무시)"
done
sleep 10
# 시험 파일을 써서 실제로 쓸 수 있는지 본다. 번호 겹침으로 실패하면 시도마다 번호가 하나씩 앞서가므로 몇 번 더 해 본다.
ok=0
for try in 1 2 3 4 5 6; do
  if docker exec "$STANDBY_CONTAINER" sh -c 'echo probe | hdfs dfs -put -f - /ddubi/.failover-probe' >/dev/null 2>&1; then ok=1; break; fi
  echo "   쓰기 시험 ${try}회 실패, 5초 뒤 다시"; sleep 5
done
if [ "$ok" = 1 ]; then
  docker exec "$STANDBY_CONTAINER" hdfs dfs -rm -skipTrash /ddubi/.failover-probe >/dev/null 2>&1 || true
  echo "OK: 예비에 쓰기 확인 (${try}회째 성공)"
else
  echo "WARN: 예비에 쓰기가 계속 실패한다. docker logs hdfs-datanode2 에서 ReplicaAlreadyExistsException 을 확인한다. 절차서 7절"
fi
echo
echo "다음 할 일:"
echo " - 서버1 이 살아 있다면 서버1 infra/.env 에도 HDFS_NAMENODE_HOST=$my_ip 를 넣고 archiver 와 Spark 를 다시 만든다. 절차서 4절."
echo " - 아카이버의 Kafka 읽기 위치를 마지막 사본 시각으로 되감아 최근 파일을 다시 만든다. 절차서 4절."
echo " - 이 상태는 임시다. 서버1 을 복구한 뒤 절차서 5절대로 되돌린다."
