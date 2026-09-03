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

    API_KEY_NAMES = ("KAKAO_REST_API_KEY", "SEOUL_API_KEY", "DATA_GO_KR_API_KEY", "KMA_API_KEY")
    # 수집 범위는 코드에 박지 않는다. 운영 환경마다 대상 노선·역·도로 링크와 호출 한도가
    # 다르므로 쉼표 구분 환경변수로 주입하고, 비밀값은 위 API_KEY_NAMES에만 둔다.
    EXTRA_NAMES = (
        "WEATHER_GRID_POINTS",
        "BUS_ROUTE_IDS",
        "SUBWAY_LINES",
        "SUBWAY_STATIONS",
        "TRAFFIC_LINK_IDS",
        "CITY_AREA_IDENTIFIERS",
        "CITY_AREA_REGISTRY_PATH",
        # 기존 배포 설정과의 하위 호환용이다. 신규 설정은 CITY_AREA_IDENTIFIERS를 사용한다.
        "CITY_AREA_CODES",
        "SDOT_DISTRICTS",
        "SDOT_SERVICE_NAME",
        "SDOT_MAX_PAGES",
        "SDOT_EXPECTED_SENSOR_COUNT",
        "SUBWAY_ALERT_API_URL",
        "SUBWAY_ALERT_SERVICE_NAME",
        "TRAFFIC_INCIDENT_SERVICE_NAME",
        "SEOUL_API_PAGE_SIZE",
    )

    @classmethod
    def from_env(cls) -> "Settings":
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
        raise ConfigError(f"API 키가 없습니다: {' 또는 '.join(names)} 를 .env 에 설정하세요")
