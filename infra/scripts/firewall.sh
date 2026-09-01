#!/bin/sh
# 도커 컨테이너로 들어오는 외부 트래픽 차단 (서버 간 통신만 허용)
#
# 사용: sudo sh firewall.sh <상대서버_사설IP> [--web]
#   서버1: sudo sh firewall.sh 172.26.6.220
#   서버2: sudo sh firewall.sh 172.26.15.35 --web   (--web: nginx 도입 후 80/443 외부 공개)
#
# 왜 ufw 가 아닌가: 도커는 공개 포트의 iptables 규칙을 직접 관리해서 ufw 를 우회한다.
# 도커가 사용자 규칙 자리로 보장하는 DOCKER-USER 체인에 넣어야 컨테이너 트래픽이 실제로 걸러진다.
# SSH(22)는 호스트 서비스(INPUT 체인)라 이 스크립트의 영향을 받지 않는다 → 잠김 위험 없음.
#
# 재부팅 유지: sudo apt-get install -y iptables-persistent && sudo netfilter-persistent save
# 전부 해제(원복): sudo iptables -F DOCKER-USER && sudo iptables -A DOCKER-USER -j RETURN

set -eu

PEER="${1:?usage: sudo sh firewall.sh <peer-private-ip> [--web]}"
WEB="${2:-}"

# 외부(인터넷) 쪽 네트워크 장치 이름 자동 감지 (EC2 는 보통 ens5)
IFACE=$(ip -o -4 route show to default | awk '{print $5}')
echo "external interface: $IFACE / peer: $PEER"

# DOCKER-USER 체인을 우리 규칙으로 재구성 (없으면 생성)
iptables -N DOCKER-USER 2>/dev/null || true
iptables -F DOCKER-USER

# 1) 상대 서버에서 오는 트래픽 허용 (Spark->Redis, HDFS 복제, Prometheus 스크랩 등)
iptables -A DOCKER-USER -i "$IFACE" -s "$PEER" -j RETURN

# 2) 컨테이너가 먼저 나간 연결의 응답 허용 (수집기의 외부 API 응답 등)
iptables -A DOCKER-USER -i "$IFACE" -m conntrack --ctstate RELATED,ESTABLISHED -j RETURN

# 3) 웹 공개 (서비스 서버에서 nginx 도입 후 --web 로 켠다)
if [ "$WEB" = "--web" ]; then
  iptables -A DOCKER-USER -i "$IFACE" -p tcp -m multiport --dports 80,443 -j RETURN
  echo "web ports 80/443 open to public"
fi

# 4) 그 외 외부에서 컨테이너로 오는 모든 신규 연결 차단
iptables -A DOCKER-USER -i "$IFACE" -j DROP

# 5) 외부 장치가 아닌 트래픽(도커 내부망 등)은 도커 기본 처리로
iptables -A DOCKER-USER -j RETURN

echo "----- DOCKER-USER 적용 결과 -----"
iptables -L DOCKER-USER -n --line-numbers
