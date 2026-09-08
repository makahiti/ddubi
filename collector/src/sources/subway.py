"""기존 ``collector-subway``와 호환되는 열차 위치·도착 통합 수집기.

호선 또는 역 목록 중 설정된 소스를 호출한다. 둘 다 비어 있으면 1차 운영 대상인 전체 호선
열차 위치를 기본 수집한다. 서로 다른 주기가 필요하면 개별 소스 모듈로 프로세스를 나눈다.
"""

from __future__ import annotations

import httpx

from config import Settings
from sources import subway_arrival, subway_position
from sources.api_utils import collect_sources
from sources.base import Record, SourceSpec


def fetch(settings: Settings, client: httpx.Client) -> list[Record]:
    fetchers = []
    if settings.extra.get("SUBWAY_LINES"):
        fetchers.append(("subway_position", lambda: subway_position.fetch(settings, client)))
    if settings.extra.get("SUBWAY_STATIONS"):
        fetchers.append(("subway_arrival", lambda: subway_arrival.fetch(settings, client)))
    if not fetchers:
        fetchers.append(("subway_position", lambda: subway_position.fetch(settings, client)))
    return collect_sources(fetchers, collector_name="서울 지하철 통합")


SOURCE = SourceSpec("seoul.subway.realtime", "transport.raw", "transport", 30, fetch)
