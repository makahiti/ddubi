#!/usr/bin/env bash
# 지하철 시각표를 그날치로 새로 만들어 서버에 올리고 백엔드를 다시 띄운다.
#
#   bash ~/S15P21E202/infra/scripts/refresh-subway-timetable.sh          # 오늘치
#   bash ~/S15P21E202/infra/scripts/refresh-subway-timetable.sh 2026-09-23   # 날짜 지정
#
# 왜 필요한가.
#   백엔드가 읽는 timetable.json 은 하루치다. 유효기간(다음 날 06:00)이 지나면 백엔드가
#   조용히 시각표를 쓰지 않고 예전 추정값으로 돌아간다. 고장은 아니지만 지하철 시간이
#   부정확해진다. 그동안 사람이 매일 손으로 만들어 올려야 했다.
#
# 무엇을 하나.
#   1) 그날이 평일·토요일·일요일/공휴일 중 무엇인지 정한다 (infra/config/kr-holidays.json)
#   2) build_timetable.py 로 그날치 시각표를 만든다 (임시 파일로)
#   3) 만들기에 성공했을 때만 진짜 자리로 옮긴다
#   4) 백엔드를 다시 띄운다 - 이 파일은 기동할 때 한 번만 읽힌다
#
# 안전장치.
#   - 임시 파일로 먼저 만든다. 중간에 실패해도 멀쩡한 기존 파일이 깨지지 않는다.
#   - 만들어진 파일이 이상하면(너무 작거나 유효기간이 그날이 아니면) 교체하지 않는다.
#   - 기존 파일을 한 벌 남긴다. 되돌릴 일이 생기면 그걸 쓴다.
#   - 이미 그날치가 올라가 있으면 아무것도 하지 않는다. 같은 날 두 번 돌아도 안전하다.
set -euo pipefail

REPO="${REPO:-/home/ubuntu/S15P21E202}"
SUBWAY_DIR="$REPO/backend/transit-master/subway"
TARGET="$SUBWAY_DIR/timetable.json"
TMP="$SUBWAY_DIR/timetable.json.new"
BACKUP_DIR="$SUBWAY_DIR/backup"
RAW_CSV="${RAW_CSV:-$SUBWAY_DIR/raw/서울교통공사_서울 도시철도 열차운행시각표.csv}"
MASTER="$SUBWAY_DIR/metropolitan-subway-master.json.gz"
HOLIDAYS="$REPO/infra/config/kr-holidays.json"
BUILDER="$REPO/backend/tools/transit-timing/build_timetable.py"

# CSV 를 실제로 내려받은 시각. CSV 를 새로 받으면 이 값도 같이 고쳐야 한다.
# "지금 시각" 을 넣으면 낡은 자료를 방금 받은 것처럼 꾸미는 셈이 된다.
PUBLISHED_AT="${PUBLISHED_AT:-2026-09-21T23:48:03+09:00}"
SOURCE_URL="${SOURCE_URL:-https://www.data.go.kr/data/15098251/fileData.do}"

# 이 원본으로 만들어야 한다는 약속. 2026-09-21 에 포털에서 받은 개정판이다.
# 포털에서 새 CSV 를 받으면 이 값과 PUBLISHED_AT 을 함께 고친다.
#
# 왜 굳이 검사하나. 원본이 옛 판으로 바뀌어도 시각표는 멀쩡하게 만들어진다. 다만 그 안에
# 이미 없어진 열차가 되살아난다(개정 때 평일 운행 873 건이 빠졌다). 조용히 과거로
# 돌아가는 것이라 아무도 눈치채지 못한다. 그래서 다르면 아예 만들지 않는다.
EXPECTED_CSV_SHA256="${EXPECTED_CSV_SHA256:-fd7e7b6edc18a5ac4f40f53b594d460d5cbd96d90919aaeb54b957890080d606}"

log() { echo "[$(date '+%F %T')] $*"; }

# 서버 시계는 UTC 다. 시각표는 한국 날짜 기준이라 KST 로 물어본다.
service_date="${1:-$(TZ=Asia/Seoul date +%F)}"

for f in "$RAW_CSV" "$MASTER" "$HOLIDAYS" "$BUILDER"; do
  if [ ! -f "$f" ]; then
    log "실패: 필요한 파일이 없다 - $f"
    exit 1
  fi
done

# 0) 원본이 약속한 그 파일인지 본다. 다르면 만들지 않는다.
actual_sha="$(sha256sum "$RAW_CSV" | cut -d' ' -f1)"
if [ "$actual_sha" != "$EXPECTED_CSV_SHA256" ]; then
  log "실패: 원본 CSV 가 약속한 파일이 아니다. 시각표를 만들지 않는다."
  log "  기대  $EXPECTED_CSV_SHA256"
  log "  실제  $actual_sha"
  log "  포털에서 개정판을 다시 받아 raw/ 에 두거나, 새 판이 맞다면"
  log "  EXPECTED_CSV_SHA256 과 PUBLISHED_AT 을 함께 고친다."
  exit 1
fi

# 1) 운행 구분 판정. 토요일 → SAT, 일요일이나 공휴일 → END, 나머지 → DAY
day_type="$(python3 - "$service_date" "$HOLIDAYS" <<'PY'
import datetime, json, sys

service_date = datetime.date.fromisoformat(sys.argv[1])
with open(sys.argv[2], encoding='utf-8') as f:
    holidays = json.load(f)['holidays']

if service_date.weekday() == 5:
    print('SAT')
elif service_date.weekday() == 6 or service_date.isoformat() in holidays:
    print('END')
else:
    print('DAY')
PY
)"

holiday_name="$(python3 -c "
import json,sys
print(json.load(open(sys.argv[1], encoding='utf-8'))['holidays'].get(sys.argv[2], ''))
" "$HOLIDAYS" "$service_date")"

log "대상 $service_date · 운행구분 $day_type${holiday_name:+ ($holiday_name)}"

# 공휴일 목록이 대상 날짜를 넘겼는지 본다. 넘겼으면 공휴일을 평일로 잘못 볼 수 있다.
last_holiday="$(python3 -c "
import json,sys
print(max(json.load(open(sys.argv[1], encoding='utf-8'))['holidays']))
" "$HOLIDAYS")"
if [[ "$service_date" > "$last_holiday" ]]; then
  log "경고: 공휴일 목록이 $last_holiday 까지다. $service_date 는 그 뒤라 공휴일을 놓칠 수 있다"
fi

# 2) 이미 그날치가 올라가 있으면 다시 만들지 않는다.
# 만들어진 파일에는 serviceDate 키가 없다. 운행 날짜는 validFrom(그날 00:00 KST)에 들어 있다.
if [ -f "$TARGET" ] && python3 -c "
import json,sys
try:
    d = json.load(open(sys.argv[1], encoding='utf-8'))
except Exception:
    sys.exit(1)
sys.exit(0 if str(d.get('validFrom', ''))[:10] == sys.argv[2] else 1)
" "$TARGET" "$service_date"; then
  log "이미 $service_date 치가 올라가 있다. 아무것도 하지 않는다"
  exit 0
fi

# 3) 임시 파일로 만든다.
log "시각표 생성 시작"
python3 "$BUILDER" \
  --csv "$RAW_CSV" \
  --master "$MASTER" \
  --service-date "$service_date" \
  --day-type "$day_type" \
  --published-at "$PUBLISHED_AT" \
  --source-url "$SOURCE_URL" \
  --output "$TMP"

# 4) 만들어진 것이 쓸 만한지 본다. 아니면 교체하지 않는다.
python3 - "$TMP" "$service_date" <<'PY'
import json, os, sys

path, service_date = sys.argv[1], sys.argv[2]
size = os.path.getsize(path)
if size < 1_000_000:
    raise SystemExit(f'생성된 파일이 너무 작다({size} 바이트). 교체하지 않는다')

with open(path, encoding='utf-8') as f:
    data = json.load(f)
valid_from = str(data.get('validFrom', ''))[:10]
if valid_from != service_date:
    raise SystemExit(f'날짜가 다르다: {valid_from} != {service_date}')
if not data.get('validUntil'):
    raise SystemExit('validUntil 이 없다')
trips = len(data.get('trips') or [])
if trips < 1000:
    raise SystemExit(f'운행이 너무 적다({trips}건). 교체하지 않는다')
print(f"검사 통과 - 운행 {trips}건 · 유효 {data.get('validUntil')} 까지")
PY

# 5) 기존 파일을 한 벌 남기고 교체한다. 최근 3개만 둔다.
mkdir -p "$BACKUP_DIR"
if [ -f "$TARGET" ]; then
  cp -p "$TARGET" "$BACKUP_DIR/timetable-$(date '+%Y%m%d-%H%M%S').json"
  ls -1t "$BACKUP_DIR"/timetable-*.json 2>/dev/null | tail -n +4 | xargs -r rm -f
fi
mv "$TMP" "$TARGET"
log "교체 완료: $TARGET"

# 6) 백엔드를 다시 띄운다. 이 파일은 기동할 때 한 번만 읽힌다.
log "백엔드 재시작"
cd "$REPO/infra"
docker compose -f docker-compose.yml -f docker-compose.server2.yml \
  --profile data --profile serving --profile hdfs2 --profile monitoring \
  up -d --no-build --force-recreate backend

log "끝. 반영 확인:"
log "  docker exec backend sh -c 'head -c 200 /opt/ddubi/transit-master/subway/timetable.json'"
