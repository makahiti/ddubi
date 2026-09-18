#!/usr/bin/env bash
# 도커가 쌓아 두는 찌꺼기를 주 1회 정리한다. 두 서버 모두에서 크론으로 돈다.
#
#   손으로:  bash ~/S15P21E202/infra/scripts/docker-prune.sh
#   크론:    40 19 * * 6  (토요일 19:40 UTC = 일요일 04:40 KST)   — install-cron.sh 가 등록한다
#
# 왜 필요한가. backend·ml-serving 을 다시 빌드할 때마다 빌드 캐시가 남고(서버2 에 10GB, 2026-09-15 확인), 이미지를 새로 받으면
# 태그가 떨어진 옛 이미지가 남는다. 디스크가 급하진 않지만 방치하면 계속 늘고, 가득 차면 Kafka·MySQL 이 함께 멈춘다.
#
# 무엇을 지우는가. 일주일 넘은 빌드 캐시, 태그가 없는(dangling) 이미지, 멈춘 채 일주일 넘은 일회성 컨테이너만 지운다.
# 무엇을 안 지우는가. 볼륨은 절대 건드리지 않는다(데이터). 태그가 있는 이미지도 지우지 않는다(되돌리기용 옛 버전이 있을 수 있다).
# 러너의 CI 캐시 볼륨도 볼륨이라 건드리지 않는다.
set -u
export TZ=Asia/Seoul
before=$(df --output=used -BM / | tail -1 | tr -dc 0-9)

echo "$(date '+%F %T') 시작 — 도커 사용량:"
docker system df --format '  {{.Type}}: {{.Size}} (회수 가능 {{.Reclaimable}})' 2>/dev/null

# 1) 빌드 캐시: 일주일(168시간) 넘은 것만
docker builder prune --force --filter until=168h 2>&1 | tail -1 | sed 's/^/  빌드 캐시: /'
# 2) 태그 없는 이미지
docker image prune --force 2>&1 | tail -1 | sed 's/^/  이미지: /'
# 3) 멈춘 채 일주일 넘은 컨테이너. compose 가 관리하는 운영 컨테이너는 restart 정책으로 늘 살아 있어 여기 걸리지 않는다
docker container prune --force --filter until=168h 2>&1 | tail -1 | sed 's/^/  컨테이너: /'

after=$(df --output=used -BM / | tail -1 | tr -dc 0-9)
echo "$(date '+%F %T') OK: 디스크 사용 ${before}MB → ${after}MB (회수 $((before - after))MB)"
