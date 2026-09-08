"""기존 ``collector-event``와 호환되는 도시·지하철 이벤트 통합 수집기.

명시적으로 설정된 소스만 호출한다. 세 소스의 갱신주기가 달라 정밀 운영에서는 개별 모듈을
별도 프로세스로 실행하는 것이 좋지만, 기존 Compose의 단일 event 서비스도 계속 동작한다.
"""

from __future__ import annotations

import httpx

from config import ConfigError, Settings
from sources import city_realtime, sdot_population, subway_alert
from sources.api_utils import collect_sources
from sources.base import Record, SourceSpec


def fetch(settings: Settings, client: httpx.Client) -> list[Record]:
    fetchers = []
    if any(
        settings.extra.get(name)
        for name in ("CITY_AREA_IDENTIFIERS", "CITY_AREA_REGISTRY_PATH", "CITY_AREA_CODES")
    ):
        fetchers.append(("city_realtime", lambda: city_realtime.fetch(settings, client)))
    if settings.extra.get("SDOT_DISTRICTS") or settings.extra.get("SDOT_SERVICE_NAME"):
        fetchers.append(("sdot_population", lambda: sdot_population.fetch(settings, client)))
    if settings.extra.get("SUBWAY_ALERT_API_URL"):
        fetchers.append(("subway_alert", lambda: subway_alert.fetch(settings, client)))
    if not fetchers:
        raise ConfigError(
            "event 수집기는 CITY_AREA_IDENTIFIERS/CITY_AREA_REGISTRY_PATH, "
            "SDOT_DISTRICTS/SDOT_SERVICE_NAME, "
            "SUBWAY_ALERT_API_URL 중 하나 이상이 필요합니다"
        )
    return collect_sources(fetchers, collector_name="서울 도시 이벤트 통합")


SOURCE = SourceSpec("seoul.city.events", "city.raw", "city", 60, fetch)
