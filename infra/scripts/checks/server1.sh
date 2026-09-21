#!/bin/bash
# 서버1(데이터) 전체 점검. 읽기 전용이다. 결과는 한 줄에 한 항목: RESULT|영역|항목|근거
# 사용: bash ~/S15P21E202/infra/scripts/checks/server1.sh | column -t -s'|'
set -u; export TZ=Asia/Seoul
now=$(date +%s)
r() { printf '%s|%s|%s|%s\n' "$1" "$2" "$3" "$4"; }
age_min() { echo $(( (now - $1) / 60 )); }

# 1. 컨테이너가 전부 살아 있나. 일회성으로 쓰는 것(replay·batch·replay-producer)은 꺼져 있어도 정상이다.
bad=$(docker ps -a --filter label=com.docker.compose.project=ddubi --format '{{.Names}} {{.Status}}' | grep -vE ' Up ' | grep -vE '^(spark-streaming-replay|spark-batch|replay-producer)' || true)
[ -z "$bad" ] && r PASS 컨테이너 "서버1 전부 Up" "$(docker ps --filter label=com.docker.compose.project=ddubi -q | wc -l)개" || r FAIL 컨테이너 "Up 아님" "$bad"
# kafka-exporter 의 재시작 9회는 9/3~9/8 Kafka 재시작 때 쌓인 옛 숫자라 제외한다.
rs=$(docker ps --filter label=com.docker.compose.project=ddubi --format '{{.Names}}' | xargs -r -I{} docker inspect {} --format '{{.Name}} {{.RestartCount}}' | awk '$2>0 && $1!="/kafka-exporter"' | tr '\n' ' ')
[ -z "$rs" ] && r PASS 컨테이너 "재시작 0" "-" || r WARN 컨테이너 "재시작 있음" "$rs"

# 2. 수집기 9개. 마지막 사이클이 성공이고 소스별 주기의 2배 안에 있어야 한다.
#    주기: weather 10분, subway-arrival 6분, subway-location 60분, bus 두 개 480분, city-realtime 360분(6시간), 나머지 15분.
for c in $(docker ps --format '{{.Names}}' | grep '^collector-'); do
  case $c in *weather) p=10;; *subway-arrival) p=6;; *subway-location) p=60;; *bus-*) p=480;; *city-realtime) p=360;; *) p=15;; esac
  last=$(docker logs "$c" --since 20h 2>&1 | grep -E 'cycle (ok|failed|skipped)' | tail -1)
  ts=$(echo "$last" | grep -oE '^[0-9-]{10} [0-9:]{8}'); st=$(echo "$last" | grep -oE 'cycle (ok|failed|skipped)')
  [ -z "$ts" ] && { r FAIL 수집 "$c" "20시간 안에 사이클 기록 없음"; continue; }
  m=$(age_min $(date -d "$ts" +%s))
  if [ "$st" = "cycle skipped" ]; then
    resume=$(echo "$last" | grep -oE 'resumeAt=[0-9T:+-]+' | cut -d= -f2)
    resume_epoch=$(date -d "$resume" +%s 2>/dev/null || echo 0)
    if [ "$resume_epoch" -gt "$now" ]; then r PASS 수집 "$c" "예정된 수집 건너뛰기, 재개 $resume"
    elif [ "$m" -le $((p*2)) ]; then r PASS 수집 "$c" "수집 재개 대기, 마지막 건너뛰기 ${m}분 전"
    else r WARN 수집 "$c" "예정 재개 시각 이후 사이클 기록 없음"; fi
  elif [ "$st" = "cycle ok" ] && [ "$m" -le $((p*2)) ]; then r PASS 수집 "$c" "마지막 성공 ${m}분 전 (주기 ${p}분)"
  elif [ "$st" = "cycle ok" ]; then r WARN 수집 "$c" "마지막 성공 ${m}분 전, 주기 ${p}분의 2배 초과"
  else r WARN 수집 "$c" "마지막 사이클 실패 ${m}분 전 (다음 시도까지 대기)"; fi
done

# 3. Spark 두 잡. 체크포인트의 offsets 파일이 최근에 새로 생겼으면 배치가 돌고 있는 것이다. 실험용 weather_replay 는 건너뛴다.
for j in $(docker exec hdfs-namenode hdfs dfs -ls /ddubi/checkpoints 2>/dev/null | awk '{print $NF}' | grep checkpoints/ | grep -v replay); do
  line=$(docker exec hdfs-namenode hdfs dfs -ls "$j/offsets" 2>/dev/null | grep -E '/offsets/[0-9]+$' | sort -k6,7 | tail -1)
  [ -z "$line" ] && { r WARN Spark "$(basename $j) 체크포인트" "offsets 없음"; continue; }
  ts=$(echo "$line" | awk '{print $6" "$7}'); m=$(age_min $(date -d "$ts" +%s)); n=$(basename "$(echo "$line" | awk '{print $NF}')")
  [ "$m" -le 15 ] && r PASS Spark "$(basename $j) 체크포인트 전진" "배치 #$n, ${m}분 전" || r WARN Spark "$(basename $j) 체크포인트" "마지막 배치 ${m}분 전"
done
for c in spark-streaming-weather spark-streaming-realtime; do
  e=$(docker logs "$c" --since 1h 2>&1 | grep -cE 'ERROR|Exception' || true)
  [ "$e" -eq 0 ] && r PASS Spark "$c 1시간 오류 로그" "0건" || r WARN Spark "$c 1시간 오류 로그" "${e}건"
done
# 잡이 켜진 뒤 Spark 실행 코드가 바뀌었으면 옛 코드로 돌고 있는 것이다(9/15 Redis TTL 사고). 브리핑도 같은 것을 본다.
# 비교 대상은 돌고 있는 잡이 실제로 읽는 폴더(streaming·common·batch)와 submit.sh·requirements.txt 만이다.
# tools·tests·examples 는 잡과 무관하므로 빼는데, 9/17 에 tools 파일 하나가 바뀐 것을 재시작 필요로 잘못 잡은 뒤 좁혔다.
commit_ts=$(cd ~/S15P21E202 && git log -1 --format=%ct -- sparkjobs/streaming sparkjobs/common sparkjobs/batch sparkjobs/submit.sh sparkjobs/requirements.txt)
for c in spark-streaming-weather spark-streaming-realtime; do
  st=$(date -d "$(docker inspect $c --format '{{.State.StartedAt}}')" +%s)
  [ "$st" -ge "$commit_ts" ] && r PASS Spark "$c 코드 최신" "시작 $(date -d @$st '+%m-%d %H:%M')" || r WARN Spark "$c 재시작 필요" "시작 $(date -d @$st '+%m-%d %H:%M') < 실행 코드 커밋 $(date -d @$commit_ts '+%m-%d %H:%M')"
done

# 4. 아카이버 → HDFS. 가장 새 Parquet 이 2시간 안에 생겨야 한다(1시간 단위 저장).
line=$(docker exec hdfs-namenode hdfs dfs -ls -R /ddubi/archive 2>/dev/null | grep -E '\.parquet$' | sort -k6,7 | tail -1)
if [ -n "$line" ]; then ts=$(echo "$line" | awk '{print $6" "$7}'); m=$(age_min $(date -d "$ts" +%s)); [ "$m" -le 120 ] && r PASS 아카이브 "HDFS 최신 Parquet" "${m}분 전" || r WARN 아카이브 "HDFS 최신 Parquet" "${m}분 전 (2시간 초과)"; else r FAIL 아카이브 "HDFS Parquet" "파일 없음"; fi
e=$(docker logs archiver --since 1h 2>&1 | grep -cE 'ERROR|Traceback' || true); [ "$e" -eq 0 ] && r PASS 아카이브 "archiver 1시간 오류 로그" "0건" || r WARN 아카이브 "archiver 1시간 오류 로그" "${e}건"

# 5. HDFS 건강과 네임노드 목차 사본. 세컨더리(서버2)가 일하면 가장 새 fsimage 가 2시간 안쪽이다.
rep=$(docker exec hdfs-namenode hdfs dfsadmin -report 2>/dev/null); live=$(echo "$rep" | grep -oE 'Live datanodes \([0-9]+\)' | grep -oE '[0-9]+'); miss=$(echo "$rep" | grep -m1 'Missing blocks' | grep -oE '[0-9]+$')
[ "${live:-0}" = 2 ] && [ "${miss:-1}" = 0 ] && r PASS HDFS "데이터노드 2 · Missing 0" "-" || r FAIL HDFS "데이터노드/Missing" "live=$live missing=$miss"
fs=$(docker exec hdfs-namenode hdfs fsck / 2>/dev/null | grep -oE 'Status: [A-Z]+'); [ "$fs" = "Status: HEALTHY" ] && r PASS HDFS "fsck" "$fs" || r FAIL HDFS "fsck" "${fs:-확인 실패}"
img=$(docker exec hdfs-namenode sh -c 'ls -t /hadoop/dfs/name/current/fsimage_* | grep -v md5 | head -1 | xargs stat -c %Y'); m=$(age_min $img); [ "$m" -le 120 ] && r PASS HDFS "네임노드 목차 사본" "가장 새 fsimage ${m}분 전" || r FAIL HDFS "네임노드 목차 사본" "가장 새 fsimage ${m}분 전 (세컨더리 확인)"

# 6. 서버1 크론. 서버2 감시는 5분마다 실행 시각을 남기고, 정상일 때는 로그에 줄을 남기지 않는다.
l=$(cat ~/.check-server2.last 2>/dev/null); m=$(age_min $(date -d "$l" +%s 2>/dev/null || echo 0)); [ -n "$l" ] && [ "$m" -le 10 ] && r PASS 크론 "서버2 감시 크론 동작" "마지막 실행 ${m}분 전" || r FAIL 크론 "서버2 감시 크론" "마지막 실행 ${l:-없음}"
w=$(grep -c . ~/check-server2.log 2>/dev/null); w=${w:-0}; [ "$w" -eq 0 ] && r PASS 크론 "서버2 감시 로그" "실패 기록 없음" || r WARN 크론 "서버2 감시 로그" "${w}줄: $(tail -n1 ~/check-server2.log | cut -c1-80)"
b=$(tail -n1 ~/backups/hdfs-name/backup.log 2>/dev/null); if echo "$b" | grep -q OK && ! echo "$b" | grep -q WARN; then r PASS 백업 "네임노드 백업" "$(echo "$b" | cut -c1-19) OK"; else r WARN 백업 "네임노드 백업" "${b:-로그 없음}"; fi

# 7. 자원
d=$(df --output=pcent / | tail -1 | tr -dc 0-9); mem=$(free | awk '/Mem:/{printf "%d", $3*100/$2}')
[ "$d" -lt 80 ] && r PASS 자원 "서버1 디스크" "${d}%" || r WARN 자원 "서버1 디스크" "${d}%"
[ "$mem" -lt 90 ] && r PASS 자원 "서버1 메모리" "${mem}%" || r WARN 자원 "서버1 메모리" "${mem}%"
[ -f /var/run/reboot-required ] && r WARN 호스트 "재부팅 대기" "$(tr '\n' ' ' < /var/run/reboot-required.pkgs 2>/dev/null)" || r PASS 호스트 "재부팅 대기 없음" "-"
