#!/bin/bash
# 바깥(개발 PC)에서 보는 점검. 공개돼야 할 것만 열려 있고 나머지는 막혀 있는지 본다. 읽기 전용이다.
# 사용(Git Bash): bash infra/scripts/checks/external.sh | column -t -s'|'
DOMAIN="${PUBLIC_DOMAIN:-j15e202.p.ssafy.io}"
SERVER1="${SERVER1_DOMAIN:-j15e202a.p.ssafy.io}"
r() { printf '%s|%s|%s|%s\n' "$1" "$2" "$3" "$4"; }

# 포트가 열려 있는지는 TCP 연결이 되는지로 본다. 4초 안에 안 되면 막힌 것으로 본다.
for hp in "$DOMAIN 443 열림" "$DOMAIN 80 열림" "$DOMAIN 3306 막힘" "$DOMAIN 6379 막힘" "$DOMAIN 9090 막힘" "$DOMAIN 3000 막힘" "$SERVER1 9870 막힘" "$SERVER1 9000 막힘" "$SERVER1 29092 막힘" "$SERVER1 8085 막힘" "$SERVER1 8080 막힘"; do
  set -- $hp
  if timeout 4 bash -c "exec 3<>/dev/tcp/$1/$2" 2>/dev/null; then got=열림; else got=막힘; fi
  [ "$got" = "$3" ] && r PASS 보안 "$1:$2" "$got" || r FAIL 보안 "$1:$2" "$got (기대 $3)"
done
c=$(curl -s -o /dev/null -w '%{http_code}' --max-time 8 "http://$DOMAIN/api/health"); [ "$c" = 301 ] && r PASS 보안 "http → https 리다이렉트" "301" || r WARN 보안 "http → https" "$c"
# 보안 헤더는 backend 로 넘어가는 경로에서 본다. /healthz 는 nginx 가 직접 답하는 자리라 2026-09-15 전까지 헤더가 빠져 있었다.
h=$(curl -sI --max-time 8 "https://$DOMAIN/api/health" | grep -ciE 'x-content-type-options|x-frame-options|referrer-policy'); [ "$h" -ge 3 ] && r PASS 보안 "nginx 보안 헤더 3개" "$h" || r WARN 보안 "nginx 보안 헤더" "${h}개"
s=$(curl -sI --max-time 8 "https://$DOMAIN/healthz" | grep -i '^server:' | tr -d '\r'); echo "$s" | grep -qE 'nginx/[0-9]' && r WARN 보안 "nginx 버전 노출" "$s" || r PASS 보안 "nginx 버전 숨김" "${s:-헤더 없음}"
g=$(curl -s -o /dev/null -w '%{size_download}' -H 'Accept-Encoding: gzip' --max-time 8 "https://$DOMAIN/v3/api-docs"); u=$(curl -s -o /dev/null -w '%{size_download}' --max-time 8 "https://$DOMAIN/v3/api-docs"); [ "$g" -lt "$u" ] 2>/dev/null && r PASS 성능 "API 응답 gzip 압축" "${u} → ${g} bytes" || r WARN 성능 "API 응답 압축 없음" "${u} bytes"
