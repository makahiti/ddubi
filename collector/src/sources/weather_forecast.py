"""기상청 단기예보를 발행시각 기준으로 보존한다.

학습 시 미래 관측값을 섞지 않도록 예보 생산시각(issuedAt)과 예측 대상시각
(validAt)을 함께 기록한다. 실시간 수집을 시작한 뒤 쌓인 원본은 Kafka 아카이버가
그대로 보존하며, 학습 데이터는 반드시 issuedAt <= 예측 기준시각 조건으로 고른다.
"""

from __future__ import annotations

import logging
from datetime import datetime, timedelta
from typing import Any

import httpx

from config import ConfigError, Settings
from sources.base import KST, Record, SourceSpec, now_kst
from sources.weather import DEFAULT_GRID_POINTS, group_by_grid, parse_grid_points

log = logging.getLogger(__name__)

ENDPOINTS = (
    (
        "KMA_API_KEY",
        "https://apihub.kma.go.kr/api/typ02/openApi/VilageFcstInfoService_2.0/getVilageFcst",
        "authKey",
    ),
    (
        "DATA_GO_KR_API_KEY",
        "https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getVilageFcst",
        "serviceKey",
    ),
)

ISSUE_HOURS = (2, 5, 8, 11, 14, 17, 20, 23)
PUBLICATION_DELAY = timedelta(minutes=15)


def latest_issue_datetime(now: datetime) -> datetime:
    """공개 지연을 감안해 현재 사용할 수 있는 가장 최근 발표 회차를 고른다."""
    local_now = now.astimezone(KST)
    day = local_now.date()
    candidates = [
        datetime(day.year, day.month, day.day, hour, tzinfo=KST)
        for hour in ISSUE_HOURS
    ]
    candidates.extend(candidate - timedelta(days=1) for candidate in candidates[:])
    available = [candidate for candidate in candidates if candidate + PUBLICATION_DELAY <= local_now]
    if not available:  # 방어 코드: 전날 회차가 있으므로 정상적으로는 도달하지 않는다.
        raise RuntimeError("사용 가능한 단기예보 발표 회차를 찾지 못했습니다")
    return max(available)


def select_endpoint(settings: Settings) -> tuple[str, str, str]:
    for env_name, url, parameter in ENDPOINTS:
        if env_name in settings.api_keys:
            return url, parameter, settings.api_keys[env_name]
    raise ConfigError(
        "API 키가 없습니다: KMA_API_KEY(기상청 API 허브) 또는 "
        "DATA_GO_KR_API_KEY(공공데이터포털) 를 .env 에 설정하세요"
    )


def parse_response(body: dict[str, Any]) -> list[dict[str, Any]]:
    header = body.get("response", {}).get("header", {})
    code = header.get("resultCode")
    if code != "00":
        raise ValueError(f"KMA resultCode={code} msg={header.get('resultMsg')}")
    items = body.get("response", {}).get("body", {}).get("items", {}).get("item", [])
    if not isinstance(items, list):
        raise ValueError("KMA 단기예보 item 형식이 목록이 아닙니다")
    return items


def group_forecasts(items: list[dict[str, Any]]) -> dict[datetime, dict[str, str]]:
    """API의 category별 행을 예측 대상시각별 한 레코드로 묶는다."""
    grouped: dict[datetime, dict[str, str]] = {}
    for item in items:
        try:
            valid_at = datetime.strptime(
                f"{item['fcstDate']}{item['fcstTime']}", "%Y%m%d%H%M"
            ).replace(tzinfo=KST)
            category = str(item["category"])
            value = str(item["fcstValue"])
        except (KeyError, TypeError, ValueError):
            log.warning("단기예보 행 파싱 실패: %s", item)
            continue
        grouped.setdefault(valid_at, {})[category] = value
    return grouped


def fetch(settings: Settings, client: httpx.Client) -> list[Record]:
    api_url, key_param, service_key = select_endpoint(settings)
    grids = group_by_grid(
        parse_grid_points(settings.extra.get("WEATHER_GRID_POINTS", DEFAULT_GRID_POINTS))
    )
    issued_at = latest_issue_datetime(now_kst())
    records: list[Record] = []
    failures = 0

    for nx, ny, districts in grids:
        params = {
            key_param: service_key,
            "pageNo": 1,
            "numOfRows": 1000,
            "dataType": "JSON",
            "base_date": issued_at.strftime("%Y%m%d"),
            "base_time": issued_at.strftime("%H%M"),
            "nx": nx,
            "ny": ny,
        }
        try:
            response = client.get(api_url, params=params)
            if response.status_code != 200:
                raise ValueError(
                    f"HTTP {response.status_code}: {' '.join(response.text.split())[:200]}"
                )
            try:
                items = parse_response(response.json())
            except ValueError as exc:
                if "KMA resultCode" in str(exc) or "item 형식" in str(exc):
                    raise
                raise ValueError(f"JSON 이 아닌 응답: {response.text[:200]}") from exc
            grouped = group_forecasts(items)
            if not grouped:
                raise ValueError("파싱 가능한 단기예보가 없습니다")
        except httpx.HTTPError as exc:
            failures += 1
            log.warning(
                "%s (nx=%s ny=%s) 단기예보 수집 실패: %s",
                ",".join(districts), nx, ny, type(exc).__name__,
            )
            continue
        except ValueError as exc:
            failures += 1
            log.warning(
                "%s (nx=%s ny=%s) 단기예보 수집 실패: %s",
                ",".join(districts), nx, ny, exc,
            )
            continue

        for valid_at, forecasts in sorted(grouped.items()):
            records.append(
                Record(
                    key=f"{nx}:{ny}",
                    event_type="weather.forecast",
                    occurred_at=issued_at,
                    payload={
                        "districts": districts,
                        "nx": nx,
                        "ny": ny,
                        "issuedAt": issued_at.isoformat(),
                        "validAt": valid_at.isoformat(),
                        "forecasts": forecasts,
                    },
                )
            )

    if failures and not records:
        raise RuntimeError(f"기상청 단기예보 수집 전부 실패 ({failures}/{len(grids)} 격자)")
    if failures:
        log.warning("기상청 단기예보 일부 실패 %d/%d 격자", failures, len(grids))
    return records


SOURCE = SourceSpec(
    name="kma.vilage-fcst",
    topic="city.raw",
    schema="city",
    default_interval_seconds=10800,
    fetch=fetch,
)
