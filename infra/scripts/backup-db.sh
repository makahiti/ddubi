#!/usr/bin/env bash
# MySQL(ddubi) 일일 백업. 서버2 호스트의 크론이 실행한다 (docs/data-retention.md 7절).
#
#   사용: bash ~/S15P21E202/infra/scripts/backup-db.sh            # 수동 1회
#   크론(서버 시계는 UTC 라 19:10 UTC = 04:10 KST):
#     10 19 * * * bash /home/ubuntu/S15P21E202/infra/scripts/backup-db.sh >> /home/ubuntu/backups/mysql/backup.log 2>&1
#
# 왜 있나: 회원·일정·추천 데이터는 MySQL 에만 있다. HDFS(복제 2)와 달리 MySQL 볼륨은 서버2 한 대에만 있어서
# 볼륨이 깨지면 복구 수단이 없었다 (2026-09-09 보관 정책 정리 때 발견). 하루 1회 논리 백업을 7일 보존한다.
#
# 방식: mysql 컨테이너 안에서 mysqldump 를 돌려 표준출력으로 받아 호스트에서 gzip 한다.
#   --single-transaction : InnoDB 를 잠그지 않고 일관된 스냅샷을 찍는다 (서비스 중단 없음)
#   --routines --triggers: 저장 프로시저·트리거도 포함
#   비밀번호는 컨테이너 환경변수(MYSQL_ROOT_PASSWORD)를 컨테이너 안에서 읽으므로 호스트 명령줄·로그에 남지 않는다
#
# 복구(개요, 자세한 절차는 docs/data-retention.md):
#   gunzip -c ddubi-YYYYmmdd-HHMM.sql.gz | docker exec -i mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" ddubi'
#   복구 뒤 backend 를 재시작한다. Flyway 이력 테이블(flyway_schema_history)도 덤프에 포함되므로 버전 불일치가 나지 않는다.
set -euo pipefail
export TZ=Asia/Seoul   # 파일명·로그 시각을 한국 시간으로 (서버 시계는 UTC)

BACKUP_DIR="${BACKUP_DIR:-$HOME/backups/mysql}"
KEEP_DAYS="${KEEP_DAYS:-7}"
DB="${DB:-ddubi}"
CONTAINER="${CONTAINER:-mysql}"

stamp="$(date +%Y%m%d-%H%M)"
out="$BACKUP_DIR/$DB-$stamp.sql.gz"
mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

if ! docker inspect "$CONTAINER" --format '{{.State.Running}}' 2>/dev/null | grep -q true; then
  echo "$(date '+%F %T') FAIL: 컨테이너 $CONTAINER 가 실행 중이 아니다"; exit 1
fi

# 덤프는 컨테이너 안에서, 압축은 호스트에서. 실패하면 부분 파일을 남기지 않는다.
tmp="$out.part"
if docker exec "$CONTAINER" sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers --quick "$0"' "$DB" \
     2> >(grep -v "Using a password on the command line" >&2) | gzip -6 > "$tmp"; then
  :
else
  rm -f "$tmp"; echo "$(date '+%F %T') FAIL: mysqldump 실패"; exit 1
fi

# 무결성: gzip 이 끝까지 읽히고, 덤프 끝 표식이 있어야 한다
if ! gzip -t "$tmp" || ! gunzip -c "$tmp" | tail -c 200 | grep -q "Dump completed"; then
  rm -f "$tmp"; echo "$(date '+%F %T') FAIL: 덤프가 완전하지 않다"; exit 1
fi
mv "$tmp" "$out"
chmod 600 "$out"

# 보존: KEEP_DAYS 지난 백업 삭제
deleted=$(find "$BACKUP_DIR" -name "$DB-*.sql.gz" -mtime +"$KEEP_DAYS" -print -delete | wc -l)
size=$(du -h "$out" | cut -f1)
count=$(ls "$BACKUP_DIR"/$DB-*.sql.gz | wc -l)

# 서버1 로 사본 보내기. 백업이 MySQL 과 같은 서버2 디스크에만 있으면 서버2 디스크를 잃을 때 원본과 백업이 함께 사라진다.
# 배포용 키(~/.ssh/ddubi_deploy)로 서버1 ~/backups/mysql-from-server2/ 에 복사하고, 서버1에서도 KEEP_DAYS 지난 것을 지운다.
# 복사가 실패해도 백업 자체는 성공이므로 종료 코드는 0 으로 두고, 로그 줄에 WARN 을 붙인다. 아침 브리핑이 WARN 을 보면 알린다.
COPY_HOST="${COPY_HOST:-$(grep -E '^SERVER1_PRIVATE_IP=' "$(dirname "$0")/../.env" 2>/dev/null | cut -d= -f2- | tr -d '\r')}"
COPY_KEY="${COPY_KEY:-$HOME/.ssh/ddubi_deploy}"
COPY_DIR="${COPY_DIR:-backups/mysql-from-server2}"
copy_note=""
if [ -z "$COPY_HOST" ]; then
  copy_note=" WARN: 서버1 주소(SERVER1_PRIVATE_IP)를 몰라 사본을 못 보냈다"
elif ! [ -r "$COPY_KEY" ]; then
  copy_note=" WARN: 복사용 키 $COPY_KEY 가 없어 사본을 못 보냈다"
else
  ssh_opts=(-i "$COPY_KEY" -o BatchMode=yes -o ConnectTimeout=10 -o StrictHostKeyChecking=accept-new)
  if ssh "${ssh_opts[@]}" "ubuntu@$COPY_HOST" "mkdir -p '$COPY_DIR' && chmod 700 '$COPY_DIR'" \
     && scp -q "${ssh_opts[@]}" "$out" "ubuntu@$COPY_HOST:$COPY_DIR/" \
     && ssh "${ssh_opts[@]}" "ubuntu@$COPY_HOST" "find '$COPY_DIR' -name '$DB-*.sql.gz' -mtime +$KEEP_DAYS -delete; ls '$COPY_DIR'/$DB-*.sql.gz | wc -l" > /tmp/backup-copy-count 2>/dev/null; then
    copy_note=" 서버1 사본 OK(보관 $(cat /tmp/backup-copy-count)개)"
  else
    copy_note=" WARN: 서버1 사본 복사 실패(ssh/scp). 서버1 접속과 키를 확인"
  fi
  rm -f /tmp/backup-copy-count
fi
echo "$(date '+%F %T') OK: $out ($size) 보관 ${count}개, 삭제 ${deleted}개.$copy_note"
