"""기존 ``collector-bus`` 실행명과 호환되는 버스 위치·도착 통합 수집기.

한 노선에 대해 위치와 도착을 연속 호출하므로 호출량은 ``노선 수 × 2 × 실행 횟수``다.
운영 한도가 작으면 ``bus_position``과 ``bus_arrival``을 별도 프로세스로 실행해 주기를 나눈다.
"""

from __future__ import annotations

import httpx

from config import Settings
from sources import bus_arrival, bus_position
from sources.api_utils import collect_sources
from sources.base import Record, SourceSpec


def fetch(settings: Settings, client: httpx.Client) -> list[Record]:
    return collect_sources(
        [
            ("bus_position", lambda: bus_position.fetch(settings, client)),
            ("bus_arrival", lambda: bus_arrival.fetch(settings, client)),
        ],
        collector_name="서울 버스 통합",
    )


SOURCE = SourceSpec("seoul.bus.realtime", "transport.raw", "transport", 30, fetch)
