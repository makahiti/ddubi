#!/usr/bin/env bash
# HDFS 네임노드의 목차 파일(fsimage 와 edits)을 하루 한 번 묶어 두는 백업 스크립트. 서버1 호스트의 크론이 실행한다.
# 정책 설명은 docs/data-retention.md 7절에 있다.
#
# 손으로 한 번 돌려 보기:
#   bash ~/S15P21E202/infra/scripts/backup-namenode.sh
# 크론 등록(서버 시계가 UTC 라서 19:20 이 한국 시간 04:20 이다):
#   20 19 * * * bash /home/ubuntu/S15P21E202/infra/scripts/backup-namenode.sh >> /home/ubuntu/backups/hdfs-name/backup.log 2>&1
#
# 왜 필요한가.
#   HDFS 의 데이터 블록은 두 서버에 한 부씩 있지만, 어느 블록이 어느 파일인지 적힌 목차는 서버1 볼륨 한 곳에만 있다.
#   서버2 의 세컨더리 네임노드가 한 시간마다 최신 사본을 하나 만들어 두지만, 그것은 가장 최신 것 하나뿐이다.
#   실수로 지운 것을 며칠 뒤에 알아채는 경우를 위해 날짜별 사본을 7일 동안 남긴다. 전체가 16MB 안팎이라 부담이 없다.
#
# 어떻게 하는가.
#   네임노드 컨테이너 안의 /hadoop/dfs/name/current 폴더를 tar 로 묶어 호스트로 받아 gzip 으로 압축한다. 네임노드는 멈추지 않는다.
#   지금 쓰고 있는 edits_inprogress 파일은 끝이 잘려 있을 수 있다. 그래서 복구할 때는 fsimage 파일과 마감된 edits 파일만 쓴다.
#   복구 절차는 docs/runbooks/hdfs-namenode-metadata-loss.md 에 있다.
#
# 함께 확인하는 것.
#   가장 새 fsimage 파일이 몇 시간 전 것인지 본다. 세컨더리 네임노드가 제대로 일하고 있으면 한 시간 안쪽이다.
#   26시간을 넘으면 로그 줄에 WARN 을 붙인다. 아침 브리핑이 이 줄을 읽어 알려 준다.
set -euo pipefail
export TZ=Asia/Seoul   # 파일명·로그 시각을 한국 시간으로 (서버 시계는 UTC)

BACKUP_DIR="${BACKUP_DIR:-$HOME/backups/hdfs-name}"
KEEP_DAYS="${KEEP_DAYS:-7}"
CONTAINER="${CONTAINER:-hdfs-namenode}"
SRC_DIR="/hadoop/dfs/name"
MAX_IMAGE_AGE_H="${MAX_IMAGE_AGE_H:-26}"

stamp="$(date +%Y%m%d-%H%M)"
out="$BACKUP_DIR/namenode-$stamp.tar.gz"
mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

if ! docker inspect "$CONTAINER" --format '{{.State.Running}}' 2>/dev/null | grep -q true; then
  echo "$(date '+%F %T') FAIL: 컨테이너 $CONTAINER 가 실행 중이 아니다"; exit 1
fi

# 묶기는 컨테이너 안에서, 압축은 호스트에서. 실패하면 부분 파일을 남기지 않는다.
tmp="$out.part"
if docker exec "$CONTAINER" tar -C "$SRC_DIR" -cf - current | gzip -6 > "$tmp"; then
  :
else
  rm -f "$tmp"; echo "$(date '+%F %T') FAIL: tar 실패"; exit 1
fi

# 무결성: gzip 이 끝까지 읽히고, VERSION 파일과 fsimage 가 하나 이상 들어 있어야 한다
if ! gzip -t "$tmp"; then
  rm -f "$tmp"; echo "$(date '+%F %T') FAIL: 압축 파일이 손상됐다"; exit 1
fi
listing="$(tar -tzf "$tmp")"
if ! grep -qx 'current/VERSION' <<<"$listing" || ! grep -qE '^current/fsimage_[0-9]+$' <<<"$listing"; then
  rm -f "$tmp"; echo "$(date '+%F %T') FAIL: 백업 안에 VERSION 또는 fsimage 가 없다"; exit 1
fi
mv "$tmp" "$out"
chmod 600 "$out"

# 세컨더리 네임노드가 일하고 있나: 가장 새 fsimage 의 나이(시간)
newest_image_epoch="$(docker exec "$CONTAINER" sh -c "ls -t $SRC_DIR/current/fsimage_* 2>/dev/null | grep -v md5 | head -1 | xargs -r stat -c %Y")"
age_note=""
if [ -n "$newest_image_epoch" ]; then
  age_h=$(( ( $(date +%s) - newest_image_epoch ) / 3600 ))
  if [ "$age_h" -gt "$MAX_IMAGE_AGE_H" ]; then
    age_note=" WARN: 마지막 fsimage 가 ${age_h}시간 전 — 서버2 hdfs-secondarynamenode 로그 확인"
  else
    age_note=" fsimage 나이 ${age_h}시간"
  fi
else
  age_note=" WARN: fsimage 를 찾지 못했다"
fi

# 보존: KEEP_DAYS 지난 백업 삭제
deleted=$(find "$BACKUP_DIR" -name "namenode-*.tar.gz" -mtime +"$KEEP_DAYS" -print -delete | wc -l)
size=$(du -h "$out" | cut -f1)
count=$(ls "$BACKUP_DIR"/namenode-*.tar.gz | wc -l)
echo "$(date '+%F %T') OK: $out ($size) 보관 ${count}개, 삭제 ${deleted}개.$age_note"
