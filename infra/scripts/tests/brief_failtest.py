"""daily-brief.py 실패 경로 재현 (서버2에서 실행). 실제 Mattermost 발송은 하지 않는다(send 를 가로채거나 잘못된 URL 사용).

  python3 infra/scripts/tests/brief_failtest.py [daily-brief.py 경로]   # 기본: 옆 폴더의 운영 스크립트
검사: 정상 / Prometheus 다운 / 서버1 SSH 실패 / 서버2 docker 실패 / 커밋 비교(모르는 해시·해시 아님·앞뒤) /
      발송 실패(연결 거부·URL 없음) / NaN 표기 / build 예외 시 실패 통보 / 발송 실패 시 종료 코드.
모든 항목이 [OK] 여야 한다. [CRASH] 는 그 경로에서 스크립트가 예외로 죽는다는 뜻이다.
"""
import importlib.util, os, sys, time, math

TARGET = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "daily-brief.py")
spec = importlib.util.spec_from_file_location("b", TARGET)
b = importlib.util.module_from_spec(spec); spec.loader.exec_module(b)
env = b.load_env(b.ENV_PATH)
results = []

def case(name, fn):
    t = time.time()
    try:
        out = fn()
        results.append((name, "OK", out, round(time.time() - t, 1)))
    except Exception as e:  # 여기서 예외가 나면 그 경로는 아직 크래시한다는 뜻
        results.append((name, "CRASH", f"{type(e).__name__}: {e}", round(time.time() - t, 1)))

def head_and_warn(text):
    first = text.splitlines()[0]
    return first[:160]

# 1. 정상
case("1 정상 dry-run", lambda: head_and_warn(b.build(env)))

# 2. Prometheus 다운
orig_prom = b.PROM
def prom_down():
    b.PROM = "http://localhost:9";
    try: return head_and_warn(b.build(env))
    finally: b.PROM = orig_prom
case("2 Prometheus 다운", prom_down)

# 3. 서버1 SSH 실패
orig_key = b.SERVER1_KEY
def ssh_fail():
    b.SERVER1_KEY = "/nonexistent/key"
    try: return head_and_warn(b.build(env))
    finally: b.SERVER1_KEY = orig_key
case("3 서버1 SSH 실패", ssh_fail)

# 4. 서버2 로컬 수집 실패
orig_loc = b.collect_local
def loc_fail():
    b.collect_local = lambda: {}
    try: return head_and_warn(b.build(env))
    finally: b.collect_local = orig_loc
case("4 서버2 로컬 수집 실패", loc_fail)

# 5. 커밋 비교: 모르는 해시 / 해시 아님 / 서버1 뒤처짐
case("5a 모르는 해시", lambda: b._is_ancestor("deadbeef0", "5a60392"))
case("5b 해시 아님(날짜)", lambda: b._is_ancestor("2026-09-13", "5a60392"))
# 저장소에 있는 develop 이력 두 커밋(9/11 Spark OFF → 9/13 dt 머지). 이력이 재작성되면 두 해시를 갱신한다
case("5c 서버1 뒤처짐(03dd71e→5a60392)", lambda: b._is_ancestor("03dd71e", "5a60392"))
case("5d 서버2 뒤처짐(반대)", lambda: b._is_ancestor("5a60392", "03dd71e"))

# 6. 발송 실패: 연결 거부 / 404 / URL 없음  (재시도 대기 0초)
case("6a 웹훅 연결 거부", lambda: b.send({"GRAFANA_ALERT_WEBHOOK_URL": "http://127.0.0.1:9/hooks/x"}, "t", retries=1, wait=0))
case("6b 웹훅 404", lambda: b.send({"GRAFANA_ALERT_WEBHOOK_URL": "http://localhost:3000/hooks/does-not-exist"}, "t", retries=1, wait=0))
case("6c 웹훅 URL 없음", lambda: b.send({}, "t"))

# 7. NaN / Inf 표기
case("7 fmt NaN/Inf", lambda: (b.fmt(float("nan"), 1, "%"), b.fmt(float("inf")), b.human(float("nan"))))

# 8. build 예외 → main 이 실패 통보를 보내고 exit 1 (send 를 가로채 기록만)
sent = []
def build_crash():
    orig_build, orig_send = b.build, b.send
    b.build = lambda e: (_ for _ in ()).throw(RuntimeError("simulated crash"))
    b.send = lambda e, t, **k: (sent.append(t.splitlines()[0]), 0)[1]
    sys.argv = ["x"]
    try:
        try: b.main()
        except SystemExit as ex: return f"exit={ex.code} sent={sent}"
    finally: b.build, b.send = orig_build, orig_send
case("8 build 예외 → 실패 통보", build_crash)

# 9. 발송 실패해도 main 이 예외 없이 rc 1 (send 가로채 실패 반환)
def send_fail_main():
    orig_send = b.send
    b.send = lambda e, t, **k: 1
    sys.argv = ["x"]
    try: return f"rc={b.main()}"
    finally: b.send = orig_send
case("9 발송 실패 시 main rc", send_fail_main)

# 10. Spark 잡이 sparkjobs 코드보다 먼저 시작됨 → 데이터 행에 "재시작 필요" 노란불 (9/15 실제 사고: TTL 코드가 나흘간 미적용)
orig_rem = b.collect_remote
def spark_stale():
    def fake():
        r = dict(orig_rem() or {})
        r["spark_started"] = ["/spark-streaming-realtime 2026-09-01T00:00:00.000000000Z", "/spark-streaming-weather 2026-09-20T00:00:00Z"]
        r["sparkjobs_commit"] = ["1789000000"]   # 2026-09-10 무렵
        return r
    b.collect_remote = fake
    try:
        t = b.build(env)
        hit = [l for l in t.splitlines() if "재시작 필요" in l]
        return hit[0][:130] if hit else "경고 없음 — 실패"
    finally: b.collect_remote = orig_rem
case("10 Spark 잡이 코드보다 오래됨", spark_stale)

# 11. 같은 자료가 깨져 있으면(커밋 시각이 숫자가 아님) 크래시 없이 조용히 넘어가야 한다
def spark_stale_bad():
    def fake():
        r = dict(orig_rem() or {})
        r["spark_started"] = ["이상한 줄"]; r["sparkjobs_commit"] = ["fatal: not a git repo"]
        return r
    b.collect_remote = fake
    try:
        t = b.build(env); return "크래시 없음, 재시작 경고 " + ("있음(오탐)" if any("재시작 필요" in l for l in t.splitlines()) else "없음")
    finally: b.collect_remote = orig_rem
case("11 Spark 시각 자료 깨짐", spark_stale_bad)

for name, st, out, sec in results:
    print(f"[{st}] {name} ({sec}s) -> {out}")
