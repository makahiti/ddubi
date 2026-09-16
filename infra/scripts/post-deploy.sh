#!/usr/bin/env bash
# 배포 잡(deploy-server1/2)이 compose up 뒤에 서버 안에서 실행하는 확인 스크립트.
# 배포가 "끝났다"가 아니라 "서비스가 살아 있다"를 확인하고, 아니면 잡을 실패로 만든다.
#
# 사용: bash scripts/post-deploy.sh server1 | server2   (infra/ 에서)
#
# server2 는 nginx 템플릿도 다시 적용한다. 템플릿 디렉터리는 마운트라 git pull 로 파일은 바뀌지만
# nginx 는 기동 때 만든 설정을 계속 쓴다. 컨테이너 안에서 공식 이미지의 envsubst 스크립트를 다시 돌려
# 설정을 재생성하고 reload(중단 0초)하면 재시작 없이 반영된다.
set -eu

target="${1:-}"
[ "$target" = server1 ] || [ "$target" = server2 ] || { echo "usage: $0 server1|server2" >&2; exit 2; }

# wait_for <설명> <초> <명령...> : 명령이 성공할 때까지 1초 간격으로 기다린다. 시간 안에 안 되면 실패.
wait_for() {
  local what="$1" limit="$2"; shift 2
  local i=0
  until "$@" >/dev/null 2>&1; do
    i=$((i + 1))
    if [ "$i" -ge "$limit" ]; then echo "FAIL: $what (${limit}s 초과)"; return 1; fi
    sleep 1
  done
  echo "OK: $what (${i}s)"
}

health() { docker inspect "$1" --format '{{.State.Health.Status}}' 2>/dev/null | grep -qx healthy; }

# 우리 compose 프로젝트(ddubi) 컨테이너 중 죽었거나 재시작 반복 중인 것을 보고한다.
# 라벨로 거르는 이유: 서버2에는 GitLab 러너가 잡마다 만드는 보조 컨테이너(runner-…, Exited 0)가 잠깐 남아
# 이름으로 거르면 배포 잡이 자기 러너 컨테이너를 보고 실패했다(2026-09-07 첫 배포). 일회성(run --rm) 컨테이너도 제외.
report_bad_containers() {
  # 의존 서비스(네임노드 등)가 방금 복구됐으면 그것을 기다리던 컨테이너가 재시작 백오프 중일 수 있다(2026-09-09 배포에서
  # archiver 가 그랬다). 20초 간격으로 세 번 보고, 세 번 모두 남아 있을 때만 실패로 본다.
  local bad try
  for try in 1 2 3; do
    bad=$(docker ps -a --filter label=com.docker.compose.project=ddubi \
          --filter status=restarting --filter status=exited --filter status=dead \
          --format '{{.Names}}\t{{.Status}}' | grep -vE '^(spark-streaming-replay|spark-batch|replay-producer|certbot)\b|-run-' || true)
    [ -z "$bad" ] && { echo "OK: 죽었거나 재시작 중인 컨테이너 없음"; return 0; }
    [ "$try" -lt 3 ] && { echo "재시작 중인 컨테이너가 있어 20초 뒤 다시 확인 ($try/3):"; echo "$bad"; sleep 20; }
  done
  echo "WARN: 정상이 아닌 컨테이너:"; echo "$bad"
  return 1
}

case "$target" in
  server1)
    # kafka 가 살아야 수집기·Spark·아카이버가 붙는다. 재생성됐으면 healthy 까지 20~60초.
    wait_for "kafka healthy" 120 health kafka
    # 네임노드는 헬스체크가 없다. JMX 로 세이프 모드가 풀렸는지 본다(재시작 직후 1~2분은 쓰기 거부 상태).
    nn_ready() {
      docker exec hdfs-namenode curl -sf 'http://localhost:9870/jmx?qry=Hadoop:service=NameNode,name=NameNodeInfo' \
        | grep -q '"Safemode" *: *""'
    }
    wait_for "hdfs-namenode 세이프 모드 해제" 180 nn_ready
    report_bad_containers
    ;;
  server2)
    # ML 서빙은 보조 축이라 준비가 안 돼도 배포 실패로 치지 않는다(backend 는 카카오 값으로 fallback). 상태만 알린다.
    # 9/14 장애: 이 검사를 실패 조건으로 두고 backend 가 healthy 를 기다리게 하자 ml-serving 이미지 오류가 곧 backend 7분 다운이 됐다
    if wait_for "ml-serving healthy" 120 health ml-serving; then :; else
      echo "WARN: ml-serving 미준비 — 버스 구간 예측은 카카오 값으로 대체 동작. docker logs ml-serving 확인 (모델 묶음·라이브러리)"
    fi
    # 1) nginx 템플릿 재적용: 설정을 다시 만들고 문법 검사 뒤 reload
    docker exec nginx /docker-entrypoint.d/20-envsubst-on-templates.sh >/dev/null
    docker exec nginx nginx -t
    docker exec nginx nginx -s reload
    echo "OK: nginx 템플릿 재적용·reload"
    # Prometheus 설정도 디렉터리 마운트라 파일은 바뀌지만 프로세스는 옛 설정을 쓴다. 문법 검사 뒤 SIGHUP 으로 재적재(중단 없음).
    docker exec prometheus promtool check config /etc/prometheus/ddubi/prometheus.yml >/dev/null
    docker exec prometheus kill -HUP 1
    echo "OK: prometheus 설정 재적재"
    # Grafana 는 대시보드 파일은 10초마다 다시 읽지만, 알림 규칙·수신처 파일은 기동 때만 읽는다(2026-09-10 발견: 규칙을 고쳐 배포해도
    # 옛 규칙이 남아 있었다). 관리자 API 로 알림 프로비저닝을 다시 읽게 한다. 실패해도 배포 자체는 성공이므로 경고만 남긴다.
    if docker exec grafana sh -c 'curl -sf -o /dev/null -u "admin:$GF_SECURITY_ADMIN_PASSWORD" -X POST http://localhost:3000/api/admin/provisioning/alerting/reload'; then
      echo "OK: grafana 알림 규칙·수신처 재적재"
    else
      echo "WARN: grafana 알림 재적재 실패 — 규칙 변경이 있었다면 docker restart grafana 로 반영"
    fi
    # 2) backend 기동 대기 (재빌드됐으면 Spring 기동에 40~90초)
    wait_for "backend healthy" 180 health backend
    # 3) 바깥에서 본 응답: 인증 없이 /api/health 를 부르면 401 이 정상. 502·000 이면 nginx→backend 경로 문제.
    api_check() { [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 https://${PUBLIC_DOMAIN:-j15e202.p.ssafy.io}/api/health)" = 401 ]; }
    wait_for "https /api/health = 401" 30 api_check
    report_bad_containers
    ;;
esac
echo "post-deploy $target: 통과"
