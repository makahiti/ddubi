#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""서버 상황 브리핑을 Mattermost 로 보낸다. 서버2 호스트의 크론이 하루 두 번(09:30·17:00 KST) 실행한다.

  수동: python3 ~/S15P21E202/infra/scripts/daily-brief.py            # 채널에 보냄
        python3 ~/S15P21E202/infra/scripts/daily-brief.py --dry-run  # 화면에만 출력
  크론(UTC): 30 0 * * *  및  0 8 * * *   → python3 .../daily-brief.py >> ~/brief.log 2>&1

왜 있나: Grafana 알림은 "이미 벌어진 일"(다운, 80% 초과)만 알린다. 문턱 아래에서 천천히 나빠지는 것
(재시작 횟수, 디스크 추세, 랙 증가)은 사람이 숫자를 봐야 보인다. 이 브리핑은 판정하지 않고 숫자만 정리해 준다.

어디서 읽나: 두 서버 지표는 Prometheus(서버2, localhost:9090)에 다 있다. 컨테이너 재시작 횟수만 Prometheus 에 없어
서버2는 로컬 docker, 서버1은 배포용 SSH 키(~/.ssh/ddubi_deploy)로 묻는다. 웹훅·Grafana 비밀번호는 infra/.env 에서 읽는다.
표준 라이브러리만 쓴다(호스트 python3 에 pip 패키지 없음).
"""
import base64
import json
import math
import os
import re
import subprocess
import sys
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

KST = timezone(timedelta(hours=9))
ENV_PATH = os.path.expanduser("~/S15P21E202/infra/.env")
PROM = "http://localhost:9090"
GRAFANA = "http://localhost:3000"
SERVER1 = "ubuntu@172.26.15.35"
SERVER1_KEY = os.path.expanduser("~/.ssh/ddubi_deploy")
BACKUP_LOG = os.path.expanduser("~/backups/mysql/backup.log")
WINDOW_H = 12  # 브리핑 간격(아침 9:30 ↔ 오후 5:00 ≈ 7.5h/16.5h). 12시간 창으로 "지난 반나절" 을 본다

# 서버1 기본(spark 프로필)에서 항상 떠 있어야 하는 스트리밍 잡 수. weather·realtime 둘이었는데 9/18 dt 의
# city-realtime 이 기본 프로필에 들어와 셋이 됐다. 이 수보다 적으면 "Spark 잡 부족" 으로 경고한다.
# dt 가 recommendation-data·topis-ai·road-silver 프로필의 잡을 기본으로 켜면 여기와 Grafana 규칙
# "Spark 스트리밍 잡 부족"(infra/monitoring/grafana/alerting/rules.yaml) 의 기준을 함께 올린다.
SPARK_APPS_EXPECTED = 3


def load_env(path):
    env = {}
    try:
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip()
    except OSError:
        pass
    return env


def prom(query):
    """Prometheus 순간 질의. 결과가 없거나 실패하면 None."""
    url = PROM + "/api/v1/query?query=" + urllib.parse.quote(query)
    try:
        with urllib.request.urlopen(url, timeout=10) as r:
            data = json.load(r)["data"]["result"]
    except Exception:  # noqa: BLE001 — 브리핑은 한 항목이 실패해도 나머지를 보낸다
        return None
    return data


def scalar(query, default=None):
    r = prom(query)
    if not r:
        return default
    try:
        return float(r[0]["value"][1])
    except (KeyError, ValueError, IndexError):
        return default


def by_label(query, label):
    r = prom(query) or []
    out = {}
    for x in r:
        try:
            out[x["metric"].get(label, "?")] = float(x["value"][1])
        except (KeyError, ValueError):
            pass
    return out


def human(nbytes):
    if nbytes is None or (isinstance(nbytes, float) and not math.isfinite(nbytes)):
        return "?"
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if nbytes < 1024 or unit == "TB":
            return f"{nbytes:.0f}{unit}" if unit in ("B", "KB") else f"{nbytes:.1f}{unit}"
        nbytes /= 1024


def fmt(v, digits=0, unit=""):
    if v is None or (isinstance(v, float) and not math.isfinite(v)):  # Prometheus 나눗셈이 NaN/Inf 를 줄 수 있다 (분모 0)
        return "?"
    return (f"{v:,.{digits}f}" if digits else f"{int(round(v)):,}") + unit


def grafana_firing(env):
    """발동 중(Alerting) 규칙 수와 이름. Grafana 통합 알림 API."""
    user = env.get("GRAFANA_ADMIN_USER", "admin")
    pw = env.get("GRAFANA_ADMIN_PASSWORD", "")
    req = urllib.request.Request(GRAFANA + "/api/prometheus/grafana/api/v1/alerts")
    req.add_header("Authorization", "Basic " + base64.b64encode(f"{user}:{pw}".encode()).decode())
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            alerts = json.load(r)["data"]["alerts"]
    except Exception:  # noqa: BLE001
        return None, []
    firing = sorted({a["labels"].get("alertname", "?") for a in alerts if a.get("state") == "Alerting"})
    return len(firing), firing


REMOTE_SCRIPT = r"""
echo "@@restarts"; docker ps -a --filter label=com.docker.compose.project=ddubi --format '{{.Names}}' | xargs -r -I{} docker inspect {} --format '{{.Name}} {{.RestartCount}} {{.State.Status}}'
echo "@@errors"; for c in archiver spark-streaming-weather spark-streaming-realtime spark-streaming-city-realtime; do printf "%s %s\n" "$c" "$(docker logs "$c" --since %(h)dh 2>&1 | grep -cE 'ERROR|Traceback')"; done
for c in $(docker ps --format '{{.Names}}' | grep '^collector-'); do printf "%s-failed %s\n" "$c" "$(docker logs "$c" --since %(h)dh 2>&1 | grep -cE 'failed=[1-9]|ERROR')"; done
echo "@@archive_files"; docker logs archiver --since %(h)dh 2>&1 | grep -c " wrote "
echo "@@archiver_started"; docker inspect archiver --format '{{.State.StartedAt}}'
echo "@@kafka"; docker exec kafka du -s --block-size=1 /var/lib/kafka/data 2>/dev/null | cut -f1   # 실제 디스크 사용량(희소 인덱스 파일 제외)
echo "@@deploy"; cd ~/S15P21E202 && git log -1 --format='%h %cd' --date=format:'%m-%d %H:%M' && stat -c %y .git/FETCH_HEAD 2>/dev/null | cut -c1-16
echo "@@nn_backup"; tail -n 1 ~/backups/hdfs-name/backup.log 2>/dev/null || echo "로그 없음"
echo "@@spark_started"; docker inspect spark-streaming-realtime spark-streaming-weather spark-streaming-city-realtime --format '{{.Name}} {{.State.StartedAt}}' 2>/dev/null
echo "@@sparkjobs_commit"; cd ~/S15P21E202 && git log -1 --format=%ct -- sparkjobs/streaming sparkjobs/common sparkjobs/batch sparkjobs/submit.sh sparkjobs/requirements.txt
"""


def collect_remote():
    """서버1에 한 번 접속해 재시작·오류 로그·Kafka 크기·배포 커밋을 모아 온다. 실패하면 None."""
    # 12시간치 docker logs 를 컨테이너 10여 개에서 grep 하므로 30초를 넘길 수 있다. 90초까지 기다린다
    out = _run(["ssh", "-i", SERVER1_KEY, "-o", "BatchMode=yes", "-o", "ConnectTimeout=8", SERVER1,
                REMOTE_SCRIPT.replace("%(h)d", str(WINDOW_H))], timeout=90)
    if out is None:
        return None
    sections = _split_sections(out)
    # ssh 가 인증·접속 실패로 끝나도 stdout 은 빈 문자열이라 None 이 아니다. 절(@@)이 하나도 없으면 실패로 본다
    # (9/14 시험에서 발견: 빈 결과가 "서버1 재시작 0" 으로 둔갑했다)
    return sections if sections else None


def collect_local():
    """서버2 로컬: 재시작·backend 오류 로그·배포 커밋·Redis TTL 없는 키·인증서 만료.
    TTL 없는 키는 키마다 따로 묻지 않고 한 연결로 한꺼번에 묻는다(파이프라인). 키마다 묻던 방식은 키가 17,000개가 되자
    40초를 넘겨 서버2 조회 전체가 실패로 처리됐다(9/15 17:00 브리핑). 지금 방식은 0.1초 안에 끝난다. 제한은 안전하게 60초."""
    out = _run(["bash", "-c", r"""
echo "@@restarts"; docker ps -a --filter label=com.docker.compose.project=ddubi --format '{{.Names}}' | xargs -r -I{} docker inspect {} --format '{{.Name}} {{.RestartCount}} {{.State.Status}}'
echo "@@errors"; printf "backend %s
" "$(docker logs backend --since %(h)dh 2>&1 | grep -cE ' ERROR ')"
echo "@@deploy"; cd ~/S15P21E202 && git log -1 --format='%h %cd' --date=format:'%m-%d %H:%M' && stat -c %y .git/FETCH_HEAD 2>/dev/null | cut -c1-16
echo "@@redis_nottl"; docker exec redis sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning --scan | awk "{print \"TTL \" \$0}" | redis-cli -a "$REDIS_PASSWORD" --no-auth-warning | grep -c "^-1$"'
echo "@@segment_status_keys"; docker exec redis sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning --scan --pattern "segment:status:*" | wc -l'
echo "@@segment_status_bad_ttl"; docker exec redis sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning --scan --pattern "segment:status:*" | awk "{print \"TTL \" \$0}" | redis-cli -a "$REDIS_PASSWORD" --no-auth-warning | grep -cE "^(-1|-2)$"'
echo "@@cert"; echo | openssl s_client -connect localhost:443 -servername j15e202.p.ssafy.io 2>/dev/null | openssl x509 -noout -enddate 2>/dev/null | cut -d= -f2
""".replace("%(h)d", str(WINDOW_H))], timeout=60)
    return _split_sections(out or "")


def _split_sections(text):
    sections, cur = {}, None
    for line in text.splitlines():
        if line.startswith("@@"):
            cur = line[2:].strip(); sections[cur] = []
        elif cur is not None and line.strip():
            sections[cur].append(line.rstrip())
    return sections


def _errors(lines):
    out = {}
    for l in lines:
        parts = l.split()
        if len(parts) == 2 and parts[1].isdigit():
            out[parts[0]] = int(parts[1])
    return out


REPO = os.path.expanduser("~/S15P21E202")


HASH_RE = re.compile(r"^[0-9a-f]{7,40}$")


def _known_commit(h):
    """서버2 저장소가 그 커밋을 알고 있나. 서버1이 먼저 배포한 커밋은 서버2가 pull 하기 전이면 모른다."""
    out = _run(["git", "-C", REPO, "cat-file", "-t", h], timeout=10)
    return out is not None and out.strip() == "commit"


def _is_ancestor(a, b):
    """a 가 b 의 조상인가. 판단 불가(git 오류·타임아웃·모르는 커밋)면 None — 호출자가 "비교 불가" 로 표시한다."""
    if not (HASH_RE.match(a or "") and HASH_RE.match(b or "")) or not (_known_commit(a) and _known_commit(b)):
        return None
    try:
        r = subprocess.run(["git", "-C", REPO, "merge-base", "--is-ancestor", a, b], capture_output=True, text=True, timeout=10, check=False)
    except (OSError, subprocess.TimeoutExpired):
        return None
    return r.returncode == 0


def _changed_paths(old, new):
    out = _run(["git", "-C", REPO, "diff", "--name-only", old, new]) or ""
    return [l.strip() for l in out.splitlines() if l.strip()]


def cert_days_left(enddate):
    """openssl 의 'Dec  1 03:12:44 2026 GMT' 를 남은 일수로."""
    try:
        dt = datetime.strptime(enddate.strip(), "%b %d %H:%M:%S %Y %Z").replace(tzinfo=timezone.utc)
        return (dt - datetime.now(timezone.utc)).days
    except ValueError:
        return None


def _parse_inspect_lines(text):
    res = {}
    for line in text.splitlines():
        parts = line.strip().lstrip("/").split()
        if len(parts) >= 2 and parts[1].isdigit():
            res[parts[0]] = (int(parts[1]), parts[2] if len(parts) > 2 else "?")
    return res


def _run(cmd, timeout=30):
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, check=False).stdout
    except (OSError, subprocess.TimeoutExpired):
        return None


def last_backup():
    try:
        with open(BACKUP_LOG, encoding="utf-8") as f:
            lines = [l.strip() for l in f if l.strip()]
        return lines[-1] if lines else "기록 없음"
    except OSError:
        return "로그 없음"


def _docker_ts(text):
    """도커의 StartedAt(예 2026-09-15T04:53:18.123456789Z)을 epoch 초로. 나노초 자리는 버린다. 못 읽으면 None."""
    m = re.match(r"(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)", text.strip())
    if not m:
        return None
    return datetime.strptime(m.group(1), "%Y-%m-%dT%H:%M:%S").replace(tzinfo=timezone.utc).timestamp()


def _stale_spark_jobs(rem):
    """Spark 실행 코드(streaming·common·batch·submit.sh·requirements.txt)의 마지막 커밋보다 먼저 시작된
    스트리밍 잡 이름 목록. tools·tests·examples 처럼 돌고 있는 잡과 무관한 파일이 바뀐 것은 세지 않는다.
    (9/17: tools 파일 하나가 바뀐 것을 "realtime 재시작 필요" 로 잘못 잡아 폴더 전체 비교를 실행 코드만으로 좁힘)
    자료가 없거나 이상하면 빈 목록(조용히 넘어가지 않도록 호출 쪽에서 조회 실패는 따로 표시)."""
    try:
        commit_ts = int((rem.get("sparkjobs_commit") or [""])[0].strip())
    except (ValueError, AttributeError):
        return []
    stale = []
    for line in rem.get("spark_started") or []:
        parts = line.strip().split()
        if len(parts) != 2:
            continue
        started = _docker_ts(parts[1])
        if started is not None and started < commit_ts:
            stale.append(parts[0].lstrip("/"))
    return stale


def build(env):
    now = datetime.now(KST)
    label = "아침" if now.hour < 12 else "오후"
    AREAS = ("서비스", "데이터", "저장", "자원", "안정성", "운영")
    warns = {a: [] for a in AREAS}

    def w(area, msg):
        warns[area].append(msg)

    def short(x):
        return x.replace("server1-data", "서버1").replace("server2-service", "서버2")

    # --- 수집 대상·알림 ---
    up_total = scalar("count(up)")
    up_ok = scalar("sum(up)")
    firing_n, firing = grafana_firing(env)
    if up_ok is not None and up_total is not None and up_ok < up_total:
        w("안정성", "수집 대상 일부 DOWN")
    if firing_n:
        w("안정성", f"알림 발동 {firing_n}건")

    # --- 서비스 ---
    backend_up = scalar('up{job="backend"}')
    # ml-serving 은 blackbox-exporter 가 /health/ready 를 확인한 결과(1 준비, 0 죽음·미준비). 지표가 없으면 ? 로 둔다
    ml_up = scalar('probe_success{job="ml-serving"}')
    mysql_conn = scalar("mysql_global_status_threads_connected")
    mysql_max = scalar("mysql_global_variables_max_connections")
    redis_pct = scalar("redis_memory_used_bytes / redis_memory_max_bytes * 100")
    redis_keys = scalar("sum(redis_db_keys)")
    # actuator(/actuator/*) 는 Prometheus 가 15초마다 긁는 호출이라 사용자 요청 수에서 뺀다
    http_5xx = scalar(f'sum(increase(http_server_requests_seconds_count{{status=~"5..",uri!~"/actuator.*"}}[{WINDOW_H}h]))') or 0
    http_all = scalar(f'sum(increase(http_server_requests_seconds_count{{uri!~"/actuator.*"}}[{WINDOW_H}h]))') or 0
    if backend_up is not None and backend_up < 1:
        w("서비스", "backend 다운")
    # "다운" 이라는 말은 light() 가 빨간불로 올리므로 쓰지 않는다. ML 은 보조 축이라 노란불이 맞다
    if ml_up is not None and ml_up < 1:
        w("서비스", "ML 예측 미동작(카카오 값으로 대체 중)")
    if http_5xx > 0:
        w("서비스", f"5xx {int(http_5xx)}건")

    # --- 데이터 ---
    intake = by_label(f'sum by (topic) (increase(kafka_topic_partition_current_offset{{topic=~"city.raw|transport.raw|segment.observation|segment.status"}}[{WINDOW_H}h]))', "topic")
    lag = scalar("sum(kafka_consumergroup_lag)")
    spark_apps = scalar('metrics_master_apps_Value{type="gauges"}')
    spark_workers = scalar('metrics_master_aliveWorkers_Value{type="gauges"}')
    latency = by_label('{__name__=~"metrics_.*_driver_spark_streaming_.*_latency_Value"}', "job_name")
    hdfs_live = scalar("hdfs_namenode_live_datanodes")
    hdfs_under = scalar("hdfs_namenode_under_replicated_blocks")
    hdfs_missing = scalar("hdfs_namenode_missing_blocks")
    hdfs_used = scalar("hdfs_namenode_capacity_used_bytes")
    hdfs_total = scalar("hdfs_namenode_capacity_total_bytes")
    if up_total is not None and (intake.get("city.raw") or 0) == 0:  # Prometheus 자체가 죽었을 땐 "조회 실패" 로만 경고한다
        w("데이터", "city.raw 유입 0")
    if (intake.get("segment.observation") or 0) > 0 and (intake.get("segment.status") or 0) == 0:
        w("데이터", "구간 실측은 들어오지만 segment.status 생성 0")
    if spark_apps is not None and spark_apps < SPARK_APPS_EXPECTED:
        w("데이터", "Spark 잡 부족")
    if hdfs_live is not None and hdfs_live < 2:
        w("저장", "HDFS 데이터노드 부족")
    if (hdfs_missing or 0) > 0:
        w("저장", "HDFS Missing 블록")

    # --- 자원 ---
    disk = by_label('(1 - node_filesystem_avail_bytes{mountpoint="/",fstype="ext4"} / node_filesystem_size_bytes{mountpoint="/",fstype="ext4"}) * 100', "server")
    mem = by_label("(1 - node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes) * 100", "server")
    for s, v in disk.items():
        if v >= 70:
            w("자원", f"디스크 {short(s)} {v:.0f}%")

    # --- 서버1·2 에서 직접 모은 것(재시작·오류 로그·배포·Kafka 크기·Redis TTL·인증서) ---
    rem = collect_remote()
    loc = collect_local()
    # 조회 자체가 실패하면 "정상" 으로 보이면 안 된다. 실패는 🔴 경고로 표에 드러낸다 (9/14 — Prometheus·SSH 다운 때 🟢 로 나가던 구멍)
    if up_total is None:
        w("안정성", "지표 조회 실패(Prometheus)")
    if rem is None:
        w("안정성", "서버1 조회 실패(SSH)")
    if not loc:
        w("안정성", "서버2 조회 실패(docker)")
    r1 = _parse_inspect_lines("\n".join(rem.get("restarts", []))) if rem is not None else None
    r2 = _parse_inspect_lines("\n".join(loc.get("restarts", []))) if loc else None
    errors = {}
    errors.update(_errors(rem.get("errors", [])) if rem else {})
    errors.update(_errors(loc.get("errors", [])))
    err_total = sum(v for k, v in errors.items())
    if err_total:
        w("안정성", f"오류 로그 {err_total}건")
    kafka_bytes = None
    if rem and rem.get("kafka"):
        try:
            kafka_bytes = float(rem["kafka"][0])
        except ValueError:
            pass
    deploy1 = (rem.get("deploy") or ["?"])[0] if rem else "조회 실패"
    deploy2 = (loc.get("deploy") or ["?"])[0]
    # 두 서버는 각자 필요할 때만 배포하므로 커밋이 다른 것 자체는 정상이다. 뒤처진 서버에 영향을 주는 파일이
    # 그 사이에 바뀌었을 때만 "배포 필요" 로 본다 (서버1: infra·collector·archiver·sparkjobs·CI, 서버2: infra·backend·CI)
    deploy_note = ""
    h1, h2 = deploy1.split()[0], deploy2.split()[0]
    if rem and h1 != h2:
        # 서버1 커밋이 서버2 커밋의 조상이면 서버1 이 뒤처진 것 (9/14 수정: 3원소 튜플을 2변수에 풀던 버그로 9/11~9/14 브리핑 7회 실패)
        anc = _is_ancestor(h1, h2)
        if anc is None:
            # 해시가 아니거나(git log 실패) 서버2 저장소가 모르는 커밋(서버1이 먼저 배포). 추측하지 않고 비교 불가로 표시
            deploy_note = " — 커밋 비교 불가(서버2 저장소에 없는 커밋 또는 조회 실패)"
        else:
            behind = "서버1" if anc else "서버2"
            paths = ("infra/", "collector/", "archiver/", "sparkjobs/", ".gitlab-ci.yml") if behind == "서버1" else ("infra/", "backend/", ".gitlab-ci.yml")
            changed = _changed_paths(h1 if behind == "서버1" else h2, h2 if behind == "서버1" else h1)
            hit = sorted({c.split("/")[0] + ("/" if "/" in c else "") for c in changed if c.startswith(paths)})
            if hit:
                w("운영", f"{behind} 배포 필요({', '.join(hit)} 변경)")
                deploy_note = f" — {behind} 뒤처짐, 관련 변경 있음"
            else:
                deploy_note = f" — {behind} 뒤처짐, 관련 변경 없음(정상)"
    redis_nottl = None
    if loc.get("redis_nottl"):
        try:
            redis_nottl = int(loc["redis_nottl"][0])
        except ValueError:
            pass
    if redis_nottl:
        w("서비스", f"Redis TTL 없는 키 {redis_nottl}")
    segment_status_keys = None
    segment_status_bad_ttl = None
    try:
        if loc.get("segment_status_keys"):
            segment_status_keys = int(loc["segment_status_keys"][0])
        if loc.get("segment_status_bad_ttl"):
            segment_status_bad_ttl = int(loc["segment_status_bad_ttl"][0])
    except ValueError:
        pass
    if segment_status_keys and segment_status_bad_ttl:
        w("데이터", f"segment status TTL 오류 {segment_status_bad_ttl}건")
    cert_days = cert_days_left(loc["cert"][0]) if loc.get("cert") else None
    if cert_days is not None and cert_days < 30:
        w("운영", f"인증서 {cert_days}일 남음")
    # 아카이브 새 파일 = 아카이버가 "wrote" 로그를 남긴 횟수. HDFS 파일 수 증가는 Spark 체크포인트(10초마다)가 섞여 쓸 수 없다
    new_files = None
    if rem and rem.get("archive_files"):
        try:
            new_files = int(rem["archive_files"][0])
        except ValueError:
            pass
    # 아카이버는 첫 배치를 채우는 데 최대 1시간이 걸리므로, 재생성 뒤 2시간 안에는 0 이어도 경고하지 않는다
    archiver_age_h = None
    if rem and rem.get("archiver_started"):
        try:
            started = datetime.fromisoformat(rem["archiver_started"][0].strip().replace("Z", "+00:00")[:26] + "+00:00"
                                             if rem["archiver_started"][0].strip().endswith("Z") else rem["archiver_started"][0].strip())
            archiver_age_h = (datetime.now(timezone.utc) - started).total_seconds() / 3600
        except ValueError:
            pass
    if new_files is not None and new_files < 1 and (archiver_age_h is None or archiver_age_h > 2):
        w("저장", "아카이브 새 파일 없음")
    # 일회성·실험용은 Exited 가 정상이라 제외. kafka-exporter 는 Kafka 가 잠깐 없으면 스스로 종료·재시작하므로 표시만 하고 경고는 내지 않는다
    EXEMPT = ("spark-streaming-replay", "spark-batch", "replay-producer", "certbot")
    KNOWN = ("kafka-exporter",)
    restarted, unexpected = [], []
    for server, res in (("서버1", r1), ("서버2", r2)):
        if res is None:
            restarted.append(f"{server} 조회 실패")
            continue
        for name, (cnt, status) in sorted(res.items()):
            if name in EXEMPT:
                continue
            if cnt > 0 or status != "running":
                restarted.append(f"{server} {name} {cnt}회{'' if status == 'running' else ' ' + status}")
                if name not in KNOWN or status != "running":
                    unexpected.append(name)
    if unexpected:
        w("안정성", "재시작 있음")

    backup = last_backup()
    # 마지막 줄이 OK 가 아니면 백업이 실패한 것이고, OK 라도 WARN 이 붙어 있으면 서버1 로 사본을 못 보낸 것이다. 둘 다 알린다.
    if "OK" not in backup or "WARN" in backup:
        w("운영", "백업 확인 필요")
    # 서버1의 네임노드 목차 백업(backup-namenode.sh, 매일 04:20) 결과를 본다. 서버1 조회 자체가 실패한 경우는 위에서 이미
    # 빨간불로 처리했으므로 여기서는 건너뛴다. 로그가 없거나 마지막 줄이 OK 가 아니면 "백업 확인 필요" 로, OK 라도 WARN 이 붙어 있으면
    # 세컨더리 네임노드가 26시간 넘게 사본을 안 만든 것이므로 "fsimage 오래됨" 으로 알린다.
    nn_backup = (rem.get("nn_backup") or ["로그 없음"])[0].strip() if rem else None
    if nn_backup is not None and ("OK" not in nn_backup or "WARN" in nn_backup):
        w("운영", "네임노드 백업 확인 필요" if "WARN" not in nn_backup else "네임노드 fsimage 오래됨")
    # Spark 스트리밍 잡은 켜질 때 코드를 읽어 두기 때문에, sparkjobs 코드가 배포돼도 잡을 다시 켜지 않으면 옛 코드로 계속 돈다.
    # 9/15 에 dt 가 올린 Redis TTL 이 나흘 동안 적용되지 않은 채 발견됐다. 잡 시작 시각이 sparkjobs 마지막 커밋 시각보다 앞서면 알린다.
    stale_jobs = _stale_spark_jobs(rem) if rem else []
    if stale_jobs:
        w("데이터", "Spark 잡 재시작 필요(" + ", ".join(stale_jobs) + " — Spark 실행 코드 변경 뒤 미재시작)")

    def light(area):
        return "🔴" if any(k in " ".join(warns[area]) for k in ("다운", "Missing", "부족", "유입 0", "조회 실패")) else ("🟡" if warns[area] else "🟢")

    def status(area):
        return "정상" if not warns[area] else " / ".join(warns[area])

    all_warn = [m for a in AREAS for m in warns[a]]
    head_icon = "🔴" if any(light(a) == "🔴" for a in AREAS) else ("🟡" if all_warn else "🟢")
    restarts_txt = ", ".join(restarted) if restarted else "0"
    errors_txt = " · ".join(f"{k} {v}" for k, v in errors.items() if v) or "0"
    intake_txt = " · ".join(f"{t} {fmt(v)}" for t, v in sorted(intake.items())) or "?"
    latency_txt = " / ".join(f"{fmt(v)}ms" for _, v in sorted(latency.items())) if latency else "?"
    disk_txt = " / ".join(f"{v:.0f}%" for _, v in sorted(disk.items()))
    mem_txt = " / ".join(f"{v:.0f}%" for _, v in sorted(mem.items()))
    same = rem and h1 == h2
    deploy_txt = f"{deploy1} (두 서버 동일)" if same else f"서버1 {deploy1} · 서버2 {deploy2}{deploy_note}"
    backup_txt = backup.split(" OK: ")[-1].split("/")[-1] if " OK: " in backup else backup

    rows = [
        ("서비스", f"backend {'✓' if backend_up == 1 else '✗'} · ML {'✓' if ml_up == 1 else ('✗' if ml_up is not None else '?')} · MySQL 연결 {fmt(mysql_conn)}/{fmt(mysql_max)} · Redis {fmt(redis_pct, 1, '%')} (키 {fmt(redis_keys)}, TTL 없는 키 {fmt(redis_nottl)}) · 요청 {fmt(http_all)} / 5xx {fmt(http_5xx)}"),
        ("데이터", f"유입 {intake_txt} · segment Redis 키 {fmt(segment_status_keys)} (TTL 오류 {fmt(segment_status_bad_ttl)}) · 아카이버 랙 {fmt(lag)} · Spark 잡 {fmt(spark_apps)}/{SPARK_APPS_EXPECTED} worker {fmt(spark_workers)}/2 · 배치 지연 {latency_txt}"),
        ("저장", f"HDFS Live {fmt(hdfs_live)} · Under-rep {fmt(hdfs_under)} · Missing {fmt(hdfs_missing)} · 사용 {human(hdfs_used)} · 아카이브 새 파일 {fmt(new_files)} · Kafka {human(kafka_bytes)}"),
        ("자원", f"디스크 서버1/2 {disk_txt} · 메모리 서버1/2 {mem_txt}"),
        ("안정성", f"수집 대상 {fmt(up_ok)}/{fmt(up_total)} · 알림 발동 {fmt(firing_n) if firing_n is not None else '?'}{(' (' + ', '.join(firing) + ')') if firing else ''} · 재시작 {restarts_txt} · 오류 로그 {errors_txt}"),
        ("운영", f"배포 {deploy_txt} · 백업 {backup_txt} · 인증서 {fmt(cert_days)}일"),
    ]
    lines = [
        f"### {head_icon} 뚜비 인프라 {label} 브리핑 · {now:%m-%d %H:%M}" + ("" if not all_warn else "  —  ⚠️ " + " · ".join(all_warn)),
        f"| 영역 | 상태 | 지난 {WINDOW_H}시간 핵심 수치 |",
        "|:--|:--|:--|",
    ]
    for area, text in rows:
        lines.append(f"| {light(area)} {area} | {status(area)} | {text} |")
    return "\n".join(lines)


def send(env, text, retries=1, wait=20):
    """Mattermost 웹훅으로 보낸다. 예외를 밖으로 던지지 않고 0(성공)/1(실패)만 돌려준다.
    네트워크 순단·5xx 는 한 번 더 시도한다. URL 문제(401/404)는 재시도해도 같으니 바로 실패."""
    url = env.get("GRAFANA_ALERT_WEBHOOK_URL", "")
    if not url.startswith("http"):
        print("웹훅 URL 없음 (GRAFANA_ALERT_WEBHOOK_URL)", file=sys.stderr)
        return 1
    body = json.dumps({"username": "뚜비 브리핑", "text": text}).encode("utf-8")
    for attempt in range(retries + 1):
        try:
            req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=10) as r:
                if r.status == 200:
                    return 0
                print(f"웹훅 응답 {r.status}", file=sys.stderr)
        except urllib.error.HTTPError as e:
            print(f"웹훅 HTTP 오류 {e.code}", file=sys.stderr)
            if 400 <= e.code < 500:
                return 1
        except (urllib.error.URLError, OSError, TimeoutError) as e:  # DNS·연결 거부·타임아웃
            print(f"웹훅 연결 실패: {type(e).__name__}", file=sys.stderr)
        if attempt < retries:
            time.sleep(wait)
    return 1


def main():
    env = load_env(ENV_PATH)
    try:
        text = build(env)
    except Exception:  # noqa: BLE001 — 브리핑이 죽으면 조용히 사라지지 않고 채널에 한 줄 남긴다 (9/14, 사흘 결손 뒤 추가)
        err = traceback.format_exc()
        print(err, file=sys.stderr)
        if "--dry-run" not in sys.argv:
            last = err.strip().splitlines()[-1][:200]
            msg = chr(10).join([
                f"### 🔴 뚜비 인프라 브리핑 실패 · {datetime.now(KST):%m-%d %H:%M}",
                "스크립트 예외로 브리핑을 만들지 못했습니다. 서버2 `~/brief.log` 확인.",
                "```", last, "```",
            ])
            send(env, msg)
        sys.exit(1)
    if "--dry-run" in sys.argv:
        print(text)
        return 0
    rc = send(env, text)
    print(f"{datetime.now(KST):%F %T} {'sent' if rc == 0 else 'FAIL'}: {text.splitlines()[0]}")
    return rc


if __name__ == "__main__":
    sys.exit(main())
