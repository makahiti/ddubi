"""파이프라인 점검용 소스. 외부 API 없이 합성 이벤트 1건을 city.raw 에 보낸다.

운영 compose 에는 넣지 않는다. API 키 없이 수집기→Kafka 경로를 확인할 때만 쓴다:
  docker compose run --rm --no-deps -e COLLECTOR_SOURCE=sample -e COLLECTOR_RUN_ONCE=true collector-weather
"""

from __future__ import annotations

import itertools

import httpx

from config import Settings
from sources.base import Record, SourceSpec, now_kst

_seq = itertools.count(1)


def fetch(settings: Settings, client: httpx.Client) -> list[Record]:
    return [
        Record(
            key="sample",
            event_type="sample.heartbeat",
            occurred_at=now_kst(),
            payload={"seq": next(_seq), "appEnv": settings.app_env, "message": "pipeline check"},
        )
    ]


SOURCE = SourceSpec(
    name="sample",
    topic="city.raw",
    schema="city",
    default_interval_seconds=10,
    fetch=fetch,
)
