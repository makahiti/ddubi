"""DDUBI collector entry point.

COLLECTOR_SOURCE 로 지정한 소스 모듈(sources/<name>.py)의 SOURCE 를 불러와
주기적으로 fetch → 계약 이벤트 변환 → Kafka 발행을 반복한다.
"""

from __future__ import annotations

import importlib
import logging
import signal
import sys
import threading
import time
from pathlib import Path

import httpx
from bus_key_pool import bus_dense_policy_enabled, bus_key_candidates
from config import ConfigError, Settings
from events import build_event
from notice_dedup import NoticeDedup, publish_notices
from poll_status import build_poll_status
from publisher import Publisher
from request_budget import RequestBudget, RetryScheduled, RetryWait
from sources.base import SourceSpec, now_kst
from sources.subway_schedule import SkippedNonService, pause_until
from tenacity import retry, retry_if_exception_type, stop_after_attempt, wait_exponential
from topis_notice_policy import plan_topis_notice
from traffic_incident_poll_policy import plan_traffic_incident

log = logging.getLogger("collector")


class SourceNotImplemented(RuntimeError):
    pass


def load_source(name: str) -> SourceSpec:
    try:
        module = importlib.import_module(f"sources.{name}")
    except ModuleNotFoundError as exc:
        raise SourceNotImplemented(f"알 수 없는 소스: {name}") from exc
    spec = getattr(module, "SOURCE", None)
    if not isinstance(spec, SourceSpec):
        raise SourceNotImplemented(f"소스 '{name}' 은 아직 구현되지 않았습니다 (SOURCE 없음)")
    return spec


def resume_wait(next_at: float | None, now: float, wait_seconds: float) -> float:
    """다음 수집이 허용되는 시각까지만 기다린다. 원래 기다릴 시간보다 늘리지는 않는다.

    쉬는 시간(cooldown) 안에 수집기가 다시 켜지면 요청을 건너뛴다. 예전에는 그 뒤에 한 주기를
    통째로 다시 기다려서, 배포로 재시작될 때마다 다음 수집이 밀렸다. 2026-09-24 15:04 서버1 배포 뒤
    혼잡도는 16:21 에 받을 것을 21:04 까지 못 받았다(6시간 주기, 공백 약 10시간 40분).
    1초를 더하는 이유: 깨어나는 시각이 허용 시각보다 아주 조금 이르면 다시 쉬는 시간으로 판정된다.
    """
    if next_at is None or next_at <= now:
        return wait_seconds
    return min(wait_seconds, next_at - now + 1.0)


def _next_cycle_at(settings: Settings) -> float | None:
    """호출 한도 기록에 남은 이 수집기의 다음 수집 허용 시각. 한도를 쓰지 않는 수집기면 None."""
    from sources.kafka_adapter import POLICIES, make_request_budget

    policy = POLICIES.get(settings.source)
    if policy is None:
        return None
    try:
        budget = make_request_budget(settings.source, settings, settings.api_key(policy[0]))
        return budget.next_cycle_at(settings.source)
    except Exception:
        log.warning("source=%s could not read cycle deadline; using normal polling interval", settings.source)
        return None


@retry(
    retry=retry_if_exception_type((httpx.TransportError, httpx.HTTPStatusError)),
    stop=stop_after_attempt(3),
    wait=wait_exponential(multiplier=1, min=1, max=10),
    reraise=True,
)
def fetch_with_retry(spec: SourceSpec, settings: Settings, client: httpx.Client):
    return spec.fetch(settings, client)


def _run_cycle(spec: SourceSpec, settings: Settings, client: httpx.Client, publisher: Publisher, notice_gate=None):
    records = fetch_with_retry(spec, settings, client)
    collected_at = now_kst()
    if settings.source == "topis_notice":
        gate = notice_gate or NoticeDedup(
            settings.extra.get(
                "TOPIS_NOTICE_DEDUP_PATH",
                str(Path(__file__).resolve().parents[1] / "data" / "topis_notice_dedup.sqlite3"),
            ),
            spec.topic,
        )
        stats = publish_notices(records, gate, publisher, lambda record: build_event(spec, record, collected_at))
        log.info("topis dedup fetched=%d new=%d changed=%d unchanged=%d pending=%d missingId=%d", *stats.values())
        return stats["new"] + stats["changed"], records
    for record in records:
        event = build_event(spec, record, collected_at)
        publisher.publish(spec.topic, record.key, event)
    if spec.name == "seoul.traffic_incident" and getattr(records, "snapshot", None) is not None:
        from sources.base import Record

        snapshot = Record(
            key="traffic.incident.snapshot:all-current",
            event_type="traffic.incident.snapshot",
            occurred_at=collected_at,
            payload=records.snapshot,
        )
        publisher.publish(spec.topic, snapshot.key, build_event(spec, snapshot, collected_at))
    return len(records), records


def run_cycle(
    spec: SourceSpec, settings: Settings, client: httpx.Client, publisher: Publisher, notice_gate=None
) -> int:
    count, _ = _run_cycle(spec, settings, client, publisher, notice_gate)
    return count


def run_cycle_with_status(
    spec: SourceSpec, settings: Settings, client: httpx.Client, publisher: Publisher, notice_gate=None
) -> int:
    """한 사이클의 데이터와 별도로 SUCCESS/EMPTY/실패 상태를 항상 남긴다."""
    started_at = now_kst()
    try:
        count, records = _run_cycle(spec, settings, client, publisher, notice_gate)
    except SkippedNonService as exc:
        publisher.publish(
            "source.poll-status",
            f"{spec.name}:{settings.source}",
            build_poll_status(
                source=spec.name,
                scope_key=settings.source,
                started_at=started_at,
                finished_at=now_kst(),
                status="SKIPPED_NON_SERVICE",
                row_count=0,
                error_code="SKIPPED_NON_SERVICE",
                next_allowed_at=exc.resume_at,
            ),
        )
        raise
    except (RetryScheduled, RetryWait) as exc:
        publisher.publish(
            "source.poll-status",
            f"{spec.name}:{settings.source}",
            build_poll_status(
                source=spec.name,
                scope_key=settings.source,
                started_at=started_at,
                finished_at=now_kst(),
                status="RATE_LIMITED" if isinstance(exc, RetryWait) else "FAILED",
                row_count=0,
                error_code=type(exc).__name__.upper(),
            ),
        )
        raise
    except Exception as exc:
        publisher.publish(
            "source.poll-status",
            f"{spec.name}:{settings.source}",
            build_poll_status(
                source=spec.name,
                scope_key=settings.source,
                started_at=started_at,
                finished_at=now_kst(),
                status="FAILED",
                row_count=0,
                error_code=type(exc).__name__.upper(),
            ),
        )
        raise
    publisher.publish(
        "source.poll-status",
        f"{spec.name}:{settings.source}",
        build_poll_status(
            source=spec.name,
            scope_key=getattr(records, "scope_key", settings.source),
            started_at=started_at,
            finished_at=now_kst(),
            status=getattr(records, "status", "SUCCESS"),
            row_count=len(records),
            complete_snapshot=bool(getattr(records, "complete_snapshot", False)),
            page_count=int(getattr(records, "page_count", 0)),
            error_code=getattr(records, "error_code", None),
        ),
    )
    return count


def main() -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
        stream=sys.stdout,
    )
    # httpx 는 INFO 에서 요청 URL 전체(API 키 포함)를 찍는다. 키가 로그에 남지 않게 WARNING 으로 올린다
    logging.getLogger("httpx").setLevel(logging.WARNING)
    logging.getLogger("httpcore").setLevel(logging.WARNING)
    try:
        settings = Settings.from_env()
        spec = load_source(settings.source)
        pause_until(settings.source, settings.extra, now_kst())
    except (ConfigError, SourceNotImplemented) as exc:
        log.error("%s", exc)
        return 2

    pooled_bus = settings.source in ("bus_arrival", "bus_location") and bus_dense_policy_enabled(
        settings, settings.source
    )
    topis_dynamic = settings.source == "topis_notice"
    incident_dynamic = settings.source == "traffic_incident"
    time_window_dynamic = topis_dynamic or incident_dynamic
    interval = settings.interval_seconds or (
        plan_topis_notice(now_kst(), settings.extra).interval_seconds
        if topis_dynamic
        else plan_traffic_incident(now_kst(), settings.extra).interval_seconds
        if incident_dynamic
        else 300
        if settings.source == "bus_arrival" and pooled_bus
        else int(settings.extra.get("BUS_LOCATION_DENSE_INTERVAL_SECONDS", "150"))
        if pooled_bus
        else spec.default_interval_seconds
    )
    if settings.source != "weather" and not pooled_bus and not time_window_dynamic:
        interval = max(interval, spec.default_interval_seconds)
    log.info(
        "start source=%s topic=%s interval=%ss kafka=%s run_once=%s",
        spec.name,
        spec.topic,
        interval,
        settings.kafka_bootstrap_servers,
        settings.run_once,
    )

    stop = threading.Event()
    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, lambda *_: stop.set())

    publisher = Publisher(settings.kafka_bootstrap_servers, client_id=f"collector-{settings.source}")
    notice_gate = (
        NoticeDedup(
            settings.extra.get(
                "TOPIS_NOTICE_DEDUP_PATH",
                str(Path(__file__).resolve().parents[1] / "data" / "topis_notice_dedup.sqlite3"),
            ),
            spec.topic,
        )
        if settings.source == "topis_notice"
        else None
    )
    # 재부팅 직후 Kafka 가 늦게 뜨는 경우를 위해 첫 사이클 전에 브로커를 기다린다 (최대 5분)
    publisher.wait_ready(timeout_seconds=300)
    exit_code = 0
    with httpx.Client(timeout=httpx.Timeout(10.0 if settings.source == "weather" else 60.0, connect=5.0)) as client:
        while not stop.is_set():
            if topis_dynamic:
                interval = max(
                    plan_topis_notice(now_kst(), settings.extra).interval_seconds,
                    settings.interval_seconds or 0,
                )
            elif incident_dynamic:
                interval = max(
                    plan_traffic_incident(now_kst(), settings.extra).interval_seconds,
                    settings.interval_seconds or 0,
                )
            cycle_started = time.monotonic()
            wait_seconds = interval
            waiting_for_retry = False
            try:
                count = run_cycle_with_status(spec, settings, client, publisher, notice_gate)
                # 사이클마다 전송 확인을 기다려 sent/failed 가 이 사이클의 실제 결과를 반영하게 한다.
                # pending 은 브로커 응답을 아직 못 받은 건수(큐에서 재시도 중) — 유실이 아니다
                pending = publisher.flush(timeout_seconds=10.0)
                log.info(
                    "cycle ok records=%d sent=%d failed=%d pending=%d",
                    count,
                    publisher.sent,
                    publisher.failed,
                    pending,
                )
            except SkippedNonService as exc:
                log.info(
                    "cycle skipped reason=SKIPPED_NON_SERVICE source=%s resumeAt=%s",
                    settings.source,
                    exc.resume_at.isoformat(),
                )
                wait_seconds = exc.wait_seconds
            except RetryScheduled as exc:
                log.error("cycle failed: %s; retry scheduled in %.0fs", exc, exc.wait_seconds)
                wait_seconds = exc.wait_seconds
                waiting_for_retry = True
            except RetryWait as exc:
                log.info("cycle waiting reason=RETRY_PENDING resumeIn=%.0fs", exc.wait_seconds)
                wait_seconds = exc.wait_seconds
                waiting_for_retry = True
            except ConfigError as exc:
                log.error("%s", exc)
                exit_code = 2
                break
            except Exception:  # 한 사이클 실패로 수집기가 죽지 않게 한다
                log.exception("cycle failed")
                if settings.run_once:
                    exit_code = 1
            if settings.run_once:
                break
            if (pooled_bus or time_window_dynamic) and not waiting_for_retry:
                # Time-window policies use start-to-start cadence. In particular,
                # a 100-route bus scan must not add the full interval again after
                # that work completes.
                wait_seconds = max(1, interval - (time.monotonic() - cycle_started))
            if settings.source == "bus_location" and not waiting_for_retry and not pooled_bus:
                # After either regular/retry completion, keep the original 8h deadline.
                try:
                    slot, key = bus_key_candidates(settings, "bus_location")[0]
                    pool = settings.extra.get("BUS_LOCATION_QUOTA_POOL", "data-go-kr:15000332")
                    budget = RequestBudget(
                        settings.extra.get(
                            "API_BUDGET_PATH",
                            str(Path(__file__).resolve().parents[1] / "data" / "request_budget.sqlite3"),
                        ),
                        key,
                        int(
                            settings.extra.get(
                                "BUS_LOCATION_DAILY_BUDGET_PER_KEY",
                                settings.extra.get("API_DAILY_LIMIT", "900"),
                            )
                        ),
                        namespace=f"{pool}:{slot}",
                    )
                    next_at = budget.next_cycle_at("bus_location")
                    if next_at is not None and next_at > time.time():
                        wait_seconds = next_at - time.time()
                except Exception:
                    log.warning("Could not read bus regular deadline; using normal polling interval")
            if not settings.source.startswith("bus_") and not waiting_for_retry and wait_seconds == interval:
                # 정상 수집이든 쉬는 시간이라 건너뛴 것이든, 다음 허용 시각까지만 기다린다.
                # 멈춤 시간대·재시도처럼 따로 정한 대기(wait_seconds != interval)는 건드리지 않는다.
                # 버스는 키 풀별 이름표(namespace)로 호출 기록을 따로 남겨 이 조회로는 다음 시각을 읽을 수 없다.
                # 버스 대기는 위의 키 풀 규칙이 정한다.
                wait_seconds = resume_wait(_next_cycle_at(settings), time.time(), wait_seconds)
            stop.wait(wait_seconds)

    if publisher.flush() > 0 or publisher.failed > 0:
        exit_code = exit_code or 1
    log.info("stop sent=%d failed=%d", publisher.sent, publisher.failed)
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
