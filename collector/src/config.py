"""환경변수에서 수집기 설정을 읽는다. 주소·키는 전부 환경변수로만 받는다."""

from __future__ import annotations

import os
from dataclasses import dataclass, field


class ConfigError(RuntimeError):
    """필수 설정이 없거나 잘못됐을 때."""


def _env(name: str, default: str | None = None) -> str | None:
    """빈 문자열은 '설정 안 함'으로 본다 (compose 의 ${VAR:-} 가 빈 값을 넘기기 때문)."""
    value = os.environ.get(name)
    if value is None or value.strip() == "":
        return default
    return value.strip()


@dataclass(frozen=True)
class Settings:
    source: str
    kafka_bootstrap_servers: str
    app_env: str = "local"
    interval_seconds: int | None = None  # None 이면 소스별 기본값 사용
    run_once: bool = False  # 한 사이클만 돌고 종료 (점검·테스트용)
    api_keys: dict[str, str] = field(default_factory=dict)
    extra: dict[str, str] = field(default_factory=dict)  # 소스별 추가 설정 (예: WEATHER_GRID_POINTS)

    # 소스별 전용 키가 있으면 그것을 먼저 쓰고, 없을 때만 포털별 공통 키를 쓴다.
    # 운영 .env 에 남아 있는 전용 키를 조용히 다른 키로 바꾸지 않기 위한 순서다.
    # 공통 키로 옮길 때는 .env 에서 해당 전용 변수를 지우면 그 소스만 전환된다.
    # 서울 실시간 지하철은 별도 전용 키이므로 일반키로 대체하지 않는다.
    API_KEY_NAMES = (
        "SEOUL_OPEN_DATA_API_KEY",
        "GBIS_API_KEY",
        "INCHEON_API_KEY",
        "KMA_API_KEY",  # weather (우선)
        "DATA_GO_KR_API_KEY",  # weather (KMA 없을 때)
        "BUS_API_KEY_A",  # short-term bus collection pool A
        "BUS_API_KEY_B",  # short-term bus collection pool B
        "BUS_ARRIVAL_API_KEY",  # bus_arrival
        "BUS_LOCATION_API_KEY",  # bus_location
        "SUBWAY_API_KEY",  # subway_arrival, subway_location
        "SUBWAY_ALERT_API_KEY",  # subway_alert
        "CITY_REALTIME_API_KEY",  # city_realtime
        "TOPIS_NOTICE_API_KEY",  # topis_notice
        "TRAFFIC_INCIDENT_API_KEY",  # traffic_incident
    )
    PORTAL_KEY_BY_SOURCE = {
        "SUBWAY_ALERT_API_KEY": "SEOUL_OPEN_DATA_API_KEY",
        "CITY_REALTIME_API_KEY": "SEOUL_OPEN_DATA_API_KEY",
        "TOPIS_NOTICE_API_KEY": "SEOUL_OPEN_DATA_API_KEY",
        "TRAFFIC_INCIDENT_API_KEY": "SEOUL_OPEN_DATA_API_KEY",
        "BUS_ARRIVAL_API_KEY": "DATA_GO_KR_API_KEY",
        "BUS_LOCATION_API_KEY": "DATA_GO_KR_API_KEY",
        "GBIS_API_KEY": "DATA_GO_KR_API_KEY",
        "INCHEON_API_KEY": "DATA_GO_KR_API_KEY",
    }
    # 수집 범위는 코드에 박지 않는다. 운영 환경마다 대상 노선·역·도로 링크와 호출 한도가
    # 다르므로 쉼표 구분 환경변수로 주입하고, 비밀값은 위 API_KEY_NAMES에만 둔다.
    # 소스 코드가 실제로 읽는 것만 남긴다 (2026-09-10 정리: SUBWAY_STATIONS·TRAFFIC_LINK_IDS·CITY_AREA_CODES·SDOT_*·
    # SUBWAY_ALERT_API_URL/SERVICE_NAME·TRAFFIC_INCIDENT_SERVICE_NAME·SEOUL_API_PAGE_SIZE 는 어느 소스도 읽지 않아 제거)
    EXTRA_NAMES = (
        "TOPIS_NOTICE_DEDUP_PATH",
        "REGIONAL_BUS_TARGET_IDS",
        "BUS_CROSSWALK_PATH",
        "BUS_CROSSWALK_SHA256",
        "API_BUDGET_PATH",
        "API_DAILY_LIMIT",
        "BUS_ARRIVAL_DAILY_BUDGET_PER_KEY",
        "BUS_LOCATION_DAILY_BUDGET_PER_KEY",
        "BUS_ARRIVAL_QUOTA_POOL",
        "BUS_LOCATION_QUOTA_POOL",
        "BUS_WEEKDAY_PEAK_WINDOWS",
        "BUS_SERVICE_WINDOWS",
        "BUS_ARRIVAL_PEAK_INTERVAL_SECONDS",
        "BUS_ARRIVAL_OFFPEAK_INTERVAL_SECONDS",
        "BUS_ARRIVAL_WEEKEND_INTERVAL_SECONDS",
        "BUS_ARRIVAL_OVERNIGHT_INTERVAL_SECONDS",
        "BUS_LOCATION_DENSE_INTERVAL_SECONDS",
        "BUS_LOCATION_SPARSE_INTERVAL_SECONDS",
        "BUS_LOCATION_DENSE_ROUTE_COUNT",
        "BUS_LOCATION_OFFPEAK_INTERVAL_SECONDS",
        "BUS_LOCATION_WEEKEND_INTERVAL_SECONDS",
        "BUS_LOCATION_OVERNIGHT_INTERVAL_SECONDS",
        "WEATHER_GRID_POINTS",
        "BUS_ROUTE_IDS",
        "BUS_ROUTE_LIMIT",
        "SUBWAY_LINES",
        "SUBWAY_SKIP_ENABLED",
        "SUBWAY_SKIP_START",
        "SUBWAY_SKIP_END",
        "SUBWAY_SKIP_OVERRIDE_DATES",
        "CITY_AREA_IDENTIFIERS",
        "CITY_AREA_REGISTRY_PATH",
    )

    @classmethod
    def from_env(cls) -> Settings:
        source = _env("COLLECTOR_SOURCE")
        if not source:
            raise ConfigError(
                "COLLECTOR_SOURCE 가 필요합니다 "
                "(bus|bus_position|bus_arrival|subway|subway_position|subway_arrival|"
                "traffic|traffic_realtime|traffic_incident|weather|event|subway_alert|"
                "city_realtime|sdot_population|sample)"
            )
        bootstrap = _env("KAFKA_BOOTSTRAP_SERVERS")
        if not bootstrap:
            raise ConfigError("KAFKA_BOOTSTRAP_SERVERS 가 필요합니다")

        interval_raw = _env("COLLECTOR_INTERVAL_SECONDS")
        interval = int(interval_raw) if interval_raw else None
        if interval is not None and interval <= 0:
            raise ConfigError("COLLECTOR_INTERVAL_SECONDS 는 1 이상이어야 합니다")

        return cls(
            source=source,
            kafka_bootstrap_servers=bootstrap,
            app_env=_env("APP_ENV", "local") or "local",
            interval_seconds=interval,
            run_once=(_env("COLLECTOR_RUN_ONCE", "false") or "false").lower() in ("1", "true", "yes"),
            api_keys={n: v for n in cls.API_KEY_NAMES if (v := _env(n))},
            extra={n: v for n in cls.EXTRA_NAMES if (v := _env(n))},
        )

    def api_key(self, *names: str) -> str:
        """이름 순서대로 처음 발견되는 키를 돌려준다. 없으면 ConfigError."""
        for name in names:
            if name in self.api_keys:
                return self.api_keys[name]
            portal_name = self.PORTAL_KEY_BY_SOURCE.get(name)
            if portal_name and portal_name in self.api_keys:
                return self.api_keys[portal_name]
        raise ConfigError(f"API 키가 없습니다: {' 또는 '.join(names)} 를 .env 에 설정하세요")
