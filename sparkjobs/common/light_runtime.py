"""작은 스트리밍 작업 여러 개를 한 Spark 앱에서 감독한다. Spark 없이 시험할 수 있다.

2026-09-25 서버1 메모리가 92~96% 에 머물렀다. 날씨·도시·TOPIS·돌발 작업이 각자 Spark 앱으로 떠서, 작업마다
드라이버(JVM+파이썬)와 익스큐터가 따로 약 1.5GB 씩 썼다. 한 앱으로 모으면 드라이버·익스큐터가 하나씩만 뜬다.

감독 규칙은 작업별 감독(common/*_runtime.py)과 같다. Redis 쿼리는 필수라 하나라도 멈추면 앱을 끝내고
컨테이너가 다시 띄운다. HDFS Silver 쿼리는 선택이라 멈추면 기록만 하고 나머지는 계속 돈다.
"""

from __future__ import annotations

import logging

log = logging.getLogger(__name__)

# 한 앱으로 합칠 수 있는 작업. 모두 city.raw 를 읽고 30초 주기 Redis + 10분 주기 Silver 로 가볍다.
# realtime_latest(버스·지하철)는 무거워 따로 둔다.
LIGHT_JOBS = ("weather_latest", "city_realtime_silver", "topis_enriched", "traffic_incident_silver")


def parse_jobs(raw: str) -> list[str]:
    """쉼표로 적은 작업 이름을 확인한다. 모르는 이름·중복·빈 목록은 시작 전에 막는다."""
    jobs = [item.strip() for item in (raw or "").split(",") if item.strip()]
    if not jobs:
        raise ValueError("LIGHT_STREAMING_JOBS 가 비어 있습니다")
    unknown = [job for job in jobs if job not in LIGHT_JOBS]
    if unknown:
        raise ValueError(f"합칠 수 없는 작업: {unknown} (가능: {list(LIGHT_JOBS)})")
    if len(set(jobs)) != len(jobs):
        raise ValueError(f"같은 작업을 두 번 적었습니다: {jobs}")
    return jobs


def _stop(query, name):
    try:
        if query is not None and query.isActive:
            query.stop()
    except Exception:
        log.exception("could not stop query of %s", name)


def supervise_all(jobs, wait=None, poll_seconds: int = 30) -> None:
    """jobs: (이름, 필수 Redis 시작 함수, 선택 Silver 시작 함수 또는 None) 목록.

    필수 쿼리가 멈추면 RuntimeError 로 끝낸다(컨테이너 재시작). 선택 쿼리가 멈추면 기록하고 빼고 계속 돈다.
    wait(query, seconds) 는 기다리는 방법이다. 기본은 첫 필수 쿼리의 awaitTermination 이다.
    """
    required: dict[str, object] = {}
    optional: dict[str, object] = {}
    try:
        for name, start_redis, start_silver in jobs:
            required[name] = start_redis()
            log.info("light stream started job=%s redis=%s", name, getattr(required[name], "name", "?"))
            if start_silver is not None:
                try:
                    optional[name] = start_silver()
                except Exception:
                    log.exception("%s Silver failed to start; Redis remains active", name)
        if not required:
            raise RuntimeError("no light stream to supervise")
        while True:
            for name, query in list(required.items()):
                if not query.isActive:
                    error = query.exception()
                    raise RuntimeError(
                        f"required Redis stream of {name} stopped" + (f": {error}" if error is not None else "")
                    )
            for name, query in list(optional.items()):
                try:
                    if not query.isActive:
                        log.error("%s Silver stopped; Redis remains active: %s", name, query.exception())
                        optional.pop(name)
                except Exception:
                    log.exception("could not inspect %s Silver; Redis remains active", name)
                    optional.pop(name, None)
            first = next(iter(required.values()))
            if wait is None:
                first.awaitTermination(poll_seconds)
            else:
                wait(first, poll_seconds)
    finally:
        for name, query in list(optional.items()):
            _stop(query, name)
        for name, query in list(required.items()):
            _stop(query, name)
