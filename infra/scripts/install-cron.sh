#!/usr/bin/env bash
# 서버의 크론 항목을 저장소 기준으로 맞추는 스크립트. 서버를 새로 세우거나 크론이 빠졌을 때 한 번 실행한다.
#
#   bash ~/S15P21E202/infra/scripts/install-cron.sh server1
#   bash ~/S15P21E202/infra/scripts/install-cron.sh server2
#
# 왜 필요한가. 백업·감시·브리핑 크론은 서버에 손으로 등록한 것이라 깃에 없었다. 서버를 다시 세우면 잊기 쉽고,
# 어느 서버에 무엇이 도는지 한눈에 볼 곳도 없었다. 이 파일이 그 목록의 진실이다.
#
# 어떻게 동작하는가. 아래 목록의 줄 중 아직 없는 것만 crontab 에 추가한다. 이미 있는 줄은 그대로 두고, 이 목록에 없는
# 사용자 크론도 지우지 않는다. 서버 시계는 UTC 라서 시각은 UTC 로 적혀 있다(19:10 UTC = 04:10 KST).
set -euo pipefail

target="${1:-}"
REPO="${REPO:-/home/ubuntu/S15P21E202}"
HOME_DIR="${HOME_DIR:-/home/ubuntu}"

case "$target" in
  server1)
    lines=(
      # 서버2가 살아 있는지 5분마다 확인하고, 두 번 연속 실패하면 Mattermost 로 알린다 (서버2의 알림 시스템이 함께 죽는 경우 대비)
      "*/5 * * * * bash $REPO/infra/scripts/check-server2.sh >> $HOME_DIR/check-server2.log 2>&1"
      # HDFS 네임노드 메타데이터(목차) 일일 백업, 04:20 KST
      "20 19 * * * bash $REPO/infra/scripts/backup-namenode.sh >> $HOME_DIR/backups/hdfs-name/backup.log 2>&1"
      # 도커 빌드 캐시·태그 없는 이미지 주간 정리, 일요일 04:40 KST (볼륨은 건드리지 않음)
      "40 19 * * 6 bash $REPO/infra/scripts/docker-prune.sh >> $HOME_DIR/docker-prune.log 2>&1"
    )
    mkdir -p "$HOME_DIR/backups/hdfs-name"
    ;;
  server2)
    lines=(
      # MySQL 일일 백업, 04:10 KST. 끝나면 서버1 에도 사본을 보낸다
      "10 19 * * * bash $REPO/infra/scripts/backup-db.sh >> $HOME_DIR/backups/mysql/backup.log 2>&1"
      # 인프라 브리핑을 Mattermost 로, 09:30 과 17:00 KST
      "30 0 * * * python3 $REPO/infra/scripts/daily-brief.py >> $HOME_DIR/brief.log 2>&1"
      "0 8 * * * python3 $REPO/infra/scripts/daily-brief.py >> $HOME_DIR/brief.log 2>&1"
      # 도커 빌드 캐시·태그 없는 이미지 주간 정리, 일요일 04:40 KST (볼륨은 건드리지 않음). 서버2는 backend·ml-serving 빌드로 캐시가 빨리 쌓인다
      "40 19 * * 6 bash $REPO/infra/scripts/docker-prune.sh >> $HOME_DIR/docker-prune.log 2>&1"
    )
    mkdir -p "$HOME_DIR/backups/mysql"
    ;;
  *)
    echo "사용법: $0 server1|server2" >&2; exit 2 ;;
esac

current="$(crontab -l 2>/dev/null || true)"
added=0
for line in "${lines[@]}"; do
  if printf '%s\n' "$current" | grep -qxF -- "$line"; then
    echo "이미 있음: $line"
  else
    current="$(printf '%s\n%s\n' "$current" "$line" | sed '/^$/d')"
    echo "추가: $line"; added=$((added + 1))
  fi
done
printf '%s\n' "$current" | crontab -
echo "완료: ${added}개 추가. 현재 크론:"
crontab -l | grep -v '^#' | sed 's/^/  /'
