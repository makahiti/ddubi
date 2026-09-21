#!/bin/bash
# 서버2(서비스) 전체 점검. 읽기 전용이다. 결과는 한 줄에 한 항목: RESULT|영역|항목|근거
# 사용: bash ~/S15P21E202/infra/scripts/checks/server2.sh | column -t -s'|'
set -u; export TZ=Asia/Seoul
now=$(date +%s)
DOMAIN="${PUBLIC_DOMAIN:-j15e202.p.ssafy.io}"
r() { printf '%s|%s|%s|%s\n' "$1" "$2" "$3" "$4"; }
age_min() { echo $(( (now - $1) / 60 )); }
# Prometheus 순간 질의. 결과가 없으면 빈 문자열.
pq() { docker exec prometheus sh -c "wget -qO- 'http://localhost:9090/api/v1/query?query=$(python3 -c 'import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))' "$1")'" | python3 -c 'import sys,json
r=json.load(sys.stdin)["data"]["result"]; print(r[0]["value"][1] if r else "")'; }
# Redis 명령. 비밀번호는 컨테이너 환경변수에서 읽어 화면에 나오지 않는다.
rc() { docker exec redis sh -c "redis-cli -a \"\$REDIS_PASSWORD\" --no-auth-warning $*"; }
gapi() { docker exec grafana sh -c "curl -s -u admin:\$GF_SECURITY_ADMIN_PASSWORD http://localhost:3000$1"; }

# 1. 컨테이너. certbot 은 갱신 대기 루프라 상태가 Up 이어야 하고, 헬스체크가 있는 것은 healthy 여야 한다.
bad=$(docker ps -a --filter label=com.docker.compose.project=ddubi --format '{{.Names}} {{.Status}}' | grep -vE ' Up ' || true)
[ -z "$bad" ] && r PASS 컨테이너 "서버2 전부 Up" "$(docker ps --filter label=com.docker.compose.project=ddubi -q | wc -l)개" || r FAIL 컨테이너 "Up 아님" "$bad"
unh=$(docker ps --filter label=com.docker.compose.project=ddubi --format '{{.Names}} {{.Status}}' | grep -E 'unhealthy|starting' || true); [ -z "$unh" ] && r PASS 컨테이너 "헬스체크 전부 healthy" "-" || r FAIL 컨테이너 "unhealthy" "$unh"

# 2. 사용자 경로. /api/health 는 인증 없이 부르면 401 이 정상이다(502·000 이면 nginx→backend 경로 문제).
# Swagger 와 api-docs 는 9/18 backend 가 운영 프로파일(prod)로 바뀐 뒤 닫혀 400 이 정상이다. 200 이 나오면 개발 프로파일로 떠 있는 것이니 그것이 이상 신호다.
for p in "/healthz 200" "/api/health 401" "/swagger-ui/index.html 400" "/v3/api-docs 400"; do set -- $p; c=$(curl -s -o /dev/null -w '%{http_code}' --max-time 8 "https://$DOMAIN$1"); [ "$c" = "$2" ] && r PASS 서비스 "https $1" "$c" || r FAIL 서비스 "https $1" "$c (기대 $2)"; done
days=$(( ( $(date -d "$(echo | openssl s_client -servername "$DOMAIN" -connect "$DOMAIN:443" 2>/dev/null | openssl x509 -noout -enddate | cut -d= -f2)" +%s) - now ) / 86400 )); [ "$days" -gt 30 ] && r PASS 서비스 "TLS 인증서 남은 날" "${days}일" || r WARN 서비스 "TLS 인증서" "${days}일 (30일 미만이면 certbot 갱신 확인)"
h=$(curl -sI --max-time 8 "https://$DOMAIN/api/health" | grep -ciE 'x-content-type-options|x-frame-options|referrer-policy'); [ "$h" -ge 3 ] && r PASS 서비스 "보안 헤더 3개" "$h" || r WARN 서비스 "보안 헤더" "${h}개"
e=$(docker logs backend --since 1h 2>&1 | grep -c ' ERROR ' || true); [ "$e" -eq 0 ] && r PASS 서비스 "backend 1시간 ERROR 로그" "0건" || r WARN 서비스 "backend 1시간 ERROR 로그" "${e}건: $(docker logs backend --since 1h 2>&1 | grep ' ERROR ' | tail -1 | cut -c1-100)"

# 3. ML 서빙. 준비 상태와 유효 기간을 본다. 예측 호출 수는 backend 가 ML 을 쓰고 있는지 보는 참고값이다.
ready=$(docker exec nginx curl -s --max-time 8 http://ml-serving:8000/health/ready); c=$(docker exec nginx curl -s -o /dev/null -w '%{http_code}' --max-time 8 http://ml-serving:8000/health/ready)
until_d=$(echo "$ready" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("supported_until",""))' 2>/dev/null)
if [ "$c" = 200 ]; then left=$(( ( $(date -d "$until_d" +%s 2>/dev/null || echo $now) - now ) / 86400 )); [ "$left" -gt 3 ] && r PASS ML "ml-serving 준비" "200, 유효 ${until_d} (${left}일 남음)" || r WARN ML "ml-serving 유효 기간" "${until_d} (${left}일 남음 — 새 묶음 필요, docs/runbooks/ml-bundle-swap.md)"; else r WARN ML "ml-serving /health/ready" "$c (backend 는 카카오 값으로 대체)"; fi
r INFO ML "24시간 예측 호출 수" "$(docker logs ml-serving --since 24h 2>&1 | grep -c 'POST /v1/bus/predictions' || true)건"

# 4. Redis. 날씨 격자 14개 키가 있어야 하고, 실시간 키는 TTL 이 계약(지하철 도착 1,080초)에 가까워야 한다.
wk=$(rc --scan --pattern 'weather:latest:*' | wc -l); tk=$(rc --scan --pattern 'transit:latest:*' | wc -l)
[ "${wk:-0}" -ge 14 ] && r PASS Redis "weather:latest 키" "${wk}개" || r WARN Redis "weather:latest 키" "${wk:-0}개 (격자 14 기대)"
if [ "${tk:-0}" -gt 0 ]; then k=$(rc --scan --pattern 'transit:latest:subway-arrival:*' | head -1); ttl=$(rc ttl "$k"); [ "${ttl:-0}" -gt 300 ] && r PASS Redis "transit:latest 키·TTL" "${tk}개, 지하철 도착 TTL ${ttl}초" || r WARN Redis "transit TTL 짧음" "${ttl}초 (계약 1,080. Spark 잡이 옛 코드로 도는지 확인)"; else r WARN Redis "transit:latest 키" "0개 (6분 주기 직전이면 잠깐 0 가능)"; fi
r INFO Redis "전체 키 수" "$(rc dbsize)"

# 5. MySQL 과 백업. 백업 로그 마지막 줄이 26시간 안의 OK 여야 하고 WARN(서버1 사본 실패)이 없어야 한다.
tables=$(docker exec mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e "select count(*) from information_schema.tables where table_schema=\"ddubi\""' 2>/dev/null); [ "${tables:-0}" -ge 28 ] && r PASS MySQL "ddubi 테이블 수" "${tables}" || r WARN MySQL "ddubi 테이블 수" "${tables:-?}"
b=$(tail -n1 ~/backups/mysql/backup.log 2>/dev/null); ts=$(echo "$b" | grep -oE '^[0-9-]{10} [0-9:]{8}'); m=$(age_min $(date -d "$ts" +%s 2>/dev/null || echo 0))
if echo "$b" | grep -q OK && ! echo "$b" | grep -q WARN && [ "$m" -le 1560 ]; then r PASS 백업 "MySQL 백업" "${m}분 전 OK, 서버1 사본 포함"; else r WARN 백업 "MySQL 백업" "${b:-로그 없음}"; fi

# 6. 감시 체계 자체. Prometheus 대상 전부 UP, Grafana 규칙 전부 health ok, 발동 알림 수.
up=$(pq 'sum(up)'); tot=$(pq 'count(up)'); [ -n "$up" ] && [ "$up" = "$tot" ] && r PASS 감시 "Prometheus 대상 UP" "$up/$tot" || r FAIL 감시 "Prometheus 대상 UP" "${up:-?}/${tot:-?}"
rules=$(gapi /api/prometheus/grafana/api/v1/rules | python3 -c 'import sys,json
rs=[r for g in json.load(sys.stdin)["data"]["groups"] for r in g["rules"]]; bad=[r["name"] for r in rs if r["health"]!="ok"]; print(len(rs), ",".join(bad))')
[ -z "${rules#* }" ] && r PASS 감시 "Grafana 규칙 health" "${rules%% *}개 전부 ok" || r FAIL 감시 "Grafana 규칙 health 이상" "$rules"
fir=$(gapi /api/prometheus/grafana/api/v1/alerts | python3 -c 'import sys,json
a=sorted({x["labels"]["alertname"] for x in json.load(sys.stdin)["data"]["alerts"] if x["state"]=="firing"}); print(len(a), ",".join(a))'); [ "${fir%% *}" = 0 ] && r PASS 감시 "발동 알림" "0" || r WARN 감시 "발동 알림" "$fir"
ml=$(pq 'probe_success{job="ml-serving"}'); [ "$ml" = 1 ] && r PASS 감시 "ml-serving 지표" "1" || r WARN 감시 "ml-serving 지표" "${ml:-없음}"
lat=$(pq 'max({__name__=~"metrics_.*_driver_spark_streaming_.*_latency_Value"})'); [ -n "$lat" ] && [ "${lat%.*}" -lt 60000 ] && r PASS 감시 "Spark 최근 배치 처리 시간" "${lat%.*}ms" || r WARN 감시 "Spark 배치 처리 시간" "${lat:-없음}"
ck=$(pq '(time()*1000-hdfs_namenode_last_checkpoint_time_ms)/3600000'); [ -n "$ck" ] && r PASS 감시 "HDFS 체크포인트 나이(시간)" "$(printf '%.2f' $ck)" || r WARN 감시 "HDFS 체크포인트 지표" "없음"
inc=$(pq 'sum(increase(kafka_topic_partition_current_offset{topic="transport.raw"}[15m]))'); [ "${inc%.*}" -gt 0 ] 2>/dev/null && r PASS 감시 "transport.raw 15분 유입" "${inc%.*}건" || r FAIL 감시 "transport.raw 15분 유입" "${inc:-0}"
inc2=$(pq 'sum(increase(kafka_topic_partition_current_offset{topic="city.raw"}[15m]))'); [ "${inc2%.*}" -gt 0 ] 2>/dev/null && r PASS 감시 "city.raw 15분 유입" "${inc2%.*}건" || r WARN 감시 "city.raw 15분 유입" "0 (날씨 10분 주기라 잠깐 0 가능)"
r INFO 감시 "아카이버 랙(1시간 톱니가 정상)" "$(pq 'sum(kafka_consumergroup_lag{consumergroup="archiver"})' | cut -d. -f1)"

# 7. 크론과 브리핑
bl=$(grep -E '^[0-9-]{10} [0-9:]{8} sent' ~/brief.log 2>/dev/null | tail -n1 | cut -c1-19); m=$(age_min $(date -d "$bl" +%s 2>/dev/null || echo 0)); [ -n "$bl" ] && [ "$m" -le 600 ] && r PASS 크론 "브리핑 마지막 발송" "$bl" || r WARN 크론 "브리핑 마지막 발송" "${bl:-없음}"
r INFO 크론 "서버2 crontab 줄 수" "$(crontab -l 2>/dev/null | grep -vc '^#')"

# 8. 저장소 작업 트리. 서버 코드는 항상 깃과 같아야 한다. 수정된 추적 파일이 있으면 누군가 서버에서 파일을 직접 고친 것이고,
# stash 가 있으면 배포 준비 스크립트(pre-deploy.sh)가 그런 변경을 치워 둔 흔적이다. 둘 다 "무엇을 왜 고쳤나" 확인 뒤 정리한다.
# 미추적 파일은 세지 않는다(운영 자료 폴더가 미추적으로 있는 것이 정상). 절차: docs/runbooks/deploy-rollback.md 첫 절.
mod=$(cd ~/S15P21E202 && git status --porcelain --untracked-files=no 2>/dev/null | wc -l); st=$(cd ~/S15P21E202 && git stash list 2>/dev/null | wc -l)
[ "$mod" -eq 0 ] && r PASS 저장소 "수정된 추적 파일" "0개" || r WARN 저장소 "수정된 추적 파일" "${mod}개 (서버에서 직접 고친 파일, git status 로 확인)"
[ "$st" -eq 0 ] && r PASS 저장소 "stash" "0개" || r WARN 저장소 "stash" "${st}개 (배포 준비가 치워 둔 서버 변경, git stash list 로 확인)"

# 9. 자원·보안. 방화벽 DOCKER-USER 체인은 상대 서버 허용 + 80/443 허용 + 나머지 DROP 이라 5줄이 정상이다.
d=$(df --output=pcent / | tail -1 | tr -dc 0-9); mem=$(free | awk '/Mem:/{printf "%d", $3*100/$2}')
[ "$d" -lt 80 ] && r PASS 자원 "서버2 디스크" "${d}%" || r WARN 자원 "서버2 디스크" "${d}%"
[ "$mem" -lt 90 ] && r PASS 자원 "서버2 메모리" "${mem}%" || r WARN 자원 "서버2 메모리" "${mem}%"
fw=$(sudo -n iptables -S DOCKER-USER 2>/dev/null | grep -cE 'DROP|RETURN'); [ "${fw:-0}" -ge 4 ] && r PASS 보안 "DOCKER-USER 방화벽 규칙" "${fw}줄" || r WARN 보안 "DOCKER-USER 방화벽" "${fw:-확인 불가}줄"
[ -f /var/run/reboot-required ] && r WARN 호스트 "재부팅 대기" "$(tr '\n' ' ' < /var/run/reboot-required.pkgs 2>/dev/null)" || r PASS 호스트 "재부팅 대기 없음" "-"
