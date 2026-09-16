#!/usr/bin/env bash
# 서버1에서 5분마다 서버2가 살아 있는지 확인하고, 죽어 있으면 Mattermost 로 직접 알리는 스크립트.
#
# 왜 필요한가.
#   알림을 보내는 Grafana 와 Prometheus, 그리고 아침 브리핑이 모두 서버2에 있다. 서버1이 죽으면 서버2가 알아채서 알려 주지만,
#   서버2가 죽으면 알림 시스템도 함께 죽어서 아무도 모른다. 사용자 API 도 서버2에 있으니 가장 먼저 알아야 할 장애가
#   가장 늦게 알려지는 구조였다. 그래서 서버1이 서버2를 바깥에서 지켜본다.
#
# 무엇을 확인하는가.
#   1. 사용자 서비스: https 로 /healthz 가 200, /api/health 가 401(인증 없이 부르면 401 이 정상)이어야 한다.
#   2. 감시 시스템: 서버2 사설 IP 로 Prometheus(9090)와 Grafana(3000)의 health 가 200 이어야 한다.
#      nginx 는 살았는데 감시만 죽은 경우도 잡기 위해 따로 본다.
#
# 언제 알리는가.
#   한 번 실패는 순간 지연일 수 있어 무시한다. 두 번 연속(10분) 실패하면 한 번 알리고, 다시 살아나면 "복구" 를 한 번 알린다.
#   상태가 바뀔 때만 보내므로 같은 알림이 5분마다 반복되지 않는다.
#
# 준비물.
#   서버1 infra/.env 에 GRAFANA_ALERT_WEBHOOK_URL 이 있어야 한다(서버2 와 같은 값). 없으면 로그에 남기고 종료한다.
#   크론 등록(서버1):  */5 * * * * bash /home/ubuntu/S15P21E202/infra/scripts/check-server2.sh >> /home/ubuntu/check-server2.log 2>&1
#
# 이 스크립트 자체가 멈추면 어떻게 아는가.
#   실행마다 ~/.check-server2.last 에 시각을 적는다. 이 파일이 오래되면 크론이 멈춘 것이다. 아침 브리핑이 이 파일을 읽어 알리는 것은 다음 단계.
set -u
export TZ=Asia/Seoul

cd "$(dirname "$0")/.."   # infra/
STATE_DIR="${STATE_DIR:-$HOME}"
COUNT_FILE="$STATE_DIR/.check-server2.count"    # 연속 실패 횟수
STATUS_FILE="$STATE_DIR/.check-server2.status"  # ok 또는 down
LAST_FILE="$STATE_DIR/.check-server2.last"      # 마지막 실행 시각
PUBLIC_DOMAIN="${PUBLIC_DOMAIN:-$(grep -E '^PUBLIC_DOMAIN=' .env 2>/dev/null | cut -d= -f2- | tr -d '\r')}"
PUBLIC_DOMAIN="${PUBLIC_DOMAIN:-j15e202.p.ssafy.io}"
SERVER2_IP="${SERVER2_IP:-$(grep -E '^SERVER2_PRIVATE_IP=' .env 2>/dev/null | cut -d= -f2- | tr -d '\r')}"
WEBHOOK="${WEBHOOK:-$(grep -E '^GRAFANA_ALERT_WEBHOOK_URL=' .env 2>/dev/null | cut -d= -f2- | tr -d '\r')}"
THRESHOLD="${THRESHOLD:-2}"   # 이 횟수만큼 연속 실패해야 알린다

now() { date '+%F %T'; }
date '+%F %T' > "$LAST_FILE"

if [ -z "$WEBHOOK" ]; then echo "$(now) FAIL: infra/.env 에 GRAFANA_ALERT_WEBHOOK_URL 이 없다. 알릴 길이 없어 종료"; exit 1; fi
if [ -z "$SERVER2_IP" ]; then echo "$(now) FAIL: infra/.env 에 SERVER2_PRIVATE_IP 가 없다"; exit 1; fi

# code <url> : HTTP 상태 코드만 돌려준다. 연결이 안 되면 000.
code() { local c; c=$(curl -s -o /dev/null -w '%{http_code}' --max-time 8 "$1" 2>/dev/null); echo "${c:-000}"; }

problems=""
c=$(code "https://$PUBLIC_DOMAIN/healthz");        [ "$c" = 200 ] || problems="$problems nginx(/healthz=$c)"
c=$(code "https://$PUBLIC_DOMAIN/api/health");     [ "$c" = 401 ] || problems="$problems backend(/api/health=$c)"
c=$(code "http://$SERVER2_IP:9090/-/healthy");     [ "$c" = 200 ] || problems="$problems prometheus($c)"
c=$(code "http://$SERVER2_IP:3000/api/health");    [ "$c" = 200 ] || problems="$problems grafana($c)"

count=$(cat "$COUNT_FILE" 2>/dev/null || echo 0)
status=$(cat "$STATUS_FILE" 2>/dev/null || echo ok)

# send <제목> <본문> : Mattermost 로 보낸다. 실패해도 스크립트는 죽지 않고 로그에 남긴다.
send() {
  local text="### $1
$2
_서버1의 check-server2.sh 가 보냈습니다. 서버2의 Grafana 알림과는 별개입니다._"
  local payload
  payload=$(printf '%s' "$text" | python3 -c 'import json,sys; print(json.dumps({"username":"뚜비 감시(서버1)","text":sys.stdin.read()}))')
  if curl -sf --max-time 10 -X POST -H 'Content-Type: application/json' -d "$payload" "$WEBHOOK" >/dev/null; then
    echo "$(now) 발송: $1"
  else
    echo "$(now) FAIL: Mattermost 발송 실패 ($1). 웹훅 주소나 인터넷을 확인"
  fi
}

if [ -n "$problems" ]; then
  count=$((count + 1)); echo "$count" > "$COUNT_FILE"
  echo "$(now) 실패 ${count}회:$problems"
  if [ "$count" -ge "$THRESHOLD" ] && [ "$status" != down ]; then
    echo down > "$STATUS_FILE"
    send "🔴 서버2 응답 없음 ($(date '+%m-%d %H:%M'))" "서버1에서 $((THRESHOLD * 5))분 동안 서버2 확인에 실패했습니다. 실패 항목:$problems
서버2가 통째로 죽었으면 Grafana 알림도 함께 죽어 이 메시지가 유일한 알림입니다. 절차서: docs/runbooks/backend-down-and-nginx-502.md"
  fi
else
  if [ "$status" = down ]; then
    send "🟢 서버2 복구 ($(date '+%m-%d %H:%M'))" "서버2 확인 4항목(nginx, backend, Prometheus, Grafana)이 모두 정상으로 돌아왔습니다."
  fi
  [ "$count" != 0 ] && echo "$(now) 정상 복귀 (연속 실패 ${count}회 뒤)"
  echo 0 > "$COUNT_FILE"; echo ok > "$STATUS_FILE"
fi
