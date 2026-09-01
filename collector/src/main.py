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

import httpx
from tenacity import retry, retry_if_exception_type, stop_after_attempt, wait_exponential

from config import ConfigError, Settings
from events import build_event
from publisher import Publisher
from sources.base import SourceSpec, now_kst

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


@retry(
    retry=retry_if_exception_type((httpx.TransportError, httpx.HTTPStatusError)),
    stop=stop_after_attempt(3),
    wait=wait_exponential(multiplier=1, min=1, max=10),
    reraise=True,
)
def fetch_with_retry(spec: SourceSpec, settings: Settings, client: httpx.Client):
    return spec.fetch(settings, client)


def run_cycle(spec: SourceSpec, settings: Settings, client: httpx.Client, publisher: Publisher) -> int:
    records = fetch_with_retry(spec, settings, client)
    collected_at = now_kst()
    for record in records:
        event = build_event(spec, record, collected_at)
        publisher.publish(spec.topic, record.key, event)
    return len(records)


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
    except (ConfigError, SourceNotImplemented) as exc:
        log.error("%s", exc)
        return 2

    interval = settings.interval_seconds or spec.default_interval_seconds
    log.info(
        "start source=%s topic=%s interval=%ss kafka=%s run_once=%s",
        spec.name, spec.topic, interval, settings.kafka_bootstrap_servers, settings.run_once,
    )

    stop = threading.Event()
    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, lambda *_: stop.set())

    publisher = Publisher(settings.kafka_bootstrap_servers, client_id=f"collector-{settings.source}")
    exit_code = 0
    with httpx.Client(timeout=httpx.Timeout(10.0, connect=5.0)) as client:
        while not stop.is_set():
            try:
                count = run_cycle(spec, settings, client, publisher)
                log.info("cycle ok records=%d sent=%d failed=%d", count, publisher.sent, publisher.failed)
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
            stop.wait(interval)

    if publisher.flush() > 0:
        exit_code = exit_code or 1
    log.info("stop sent=%d failed=%d", publisher.sent, publisher.failed)
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
