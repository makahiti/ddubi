"""서울 주요 장소 실시간 도시데이터 → ``city.raw``.

API는 한 번에 한 장소만 허용하므로 Registry의 장소명 또는 코드를 순차 호출한다. Registry는
서울시가 배포하는 장소 목록이 바뀌어도 코드 수정 없이 교체할 수 있게 UTF-8 텍스트 파일로
받는다. 통합 응답에는 갱신주기가 다른 하위 자료가 섞이므로 전체 원본과 원천 시각을 보존한다.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

import httpx

from config import ConfigError, Settings
from sources.api_utils import collect_scopes, csv_values, first_value, parse_kst, safe_json, seoul_url
from sources.base import Record, SourceSpec, now_kst

SERVICE = "citydata"
DEFAULT_AREA_REGISTRY = Path(__file__).with_name("city_areas.txt")


def area_identifiers(settings: Settings) -> list[str]:
    """환경변수 또는 Registry 파일에서 장소명/장소코드를 중복 없이 읽는다.

    운영에서는 서울시가 제공하는 최신 121장소 XLSX를 UTF-8 텍스트로 변환해 Registry 파일로
    관리한다. 한 줄에 식별자 하나를 두며 빈 줄과 ``#`` 주석은 무시한다. 환경변수 값이 있으면
    점검 범위를 빠르게 줄일 수 있도록 파일보다 우선한다. 기존 ``CITY_AREA_CODES``도 호환한다.
    """
    for name in ("CITY_AREA_IDENTIFIERS", "CITY_AREA_CODES"):
        values = csv_values(settings, name, required=False)
        if values:
            return values

    raw_path = settings.extra.get("CITY_AREA_REGISTRY_PATH")
    path = Path(raw_path).expanduser() if raw_path else DEFAULT_AREA_REGISTRY
    if not path.is_file():
        raise ConfigError(f"CITY_AREA_REGISTRY_PATH 파일을 찾을 수 없습니다: {path}")
    values = list(
        dict.fromkeys(
            line.strip()
            for line in path.read_text(encoding="utf-8-sig").splitlines()
            if line.strip() and not line.lstrip().startswith("#")
        )
    )
    if not values:
        raise ConfigError(f"도시데이터 장소 Registry가 비어 있습니다: {path}")
    return values


def _find_time(value: Any) -> Any:
    if isinstance(value, dict):
        direct = first_value(value, ("PPLTN_TIME", "ROAD_TRAFFIC_TIME", "WEATHER_TIME", "UPDATE_TIME"))
        if direct is not None:
            return direct
        for child in value.values():
            found = _find_time(child)
            if found is not None:
                return found
    elif isinstance(value, list):
        for child in value:
            found = _find_time(child)
            if found is not None:
                return found
    return None


def record_from_body(requested_area: str, body: dict[str, Any]) -> Record:
    container = body.get("CITYDATA") or body.get("citydata")
    if not isinstance(container, (dict, list)):
        raise ValueError("서울 실시간 도시데이터 응답에 CITYDATA가 없습니다")
    occurred = parse_kst(_find_time(container), fallback=now_kst())
    # 응답의 공식 장소코드가 있으면 Kafka 순서 키로 사용한다. 요청에 장소명을 썼더라도 같은
    # 장소의 키가 배포 설정에 따라 달라지지 않게 하며, 누락 시에만 요청 식별자로 fallback한다.
    area_row = container[0] if isinstance(container, list) and container else container
    provider_area_id = first_value(area_row, ("AREA_CD", "AREA_NM")) if isinstance(area_row, dict) else None
    provider_area_id = str(provider_area_id or requested_area)
    return Record(
        key=provider_area_id,
        event_type="city.realtime",
        occurred_at=occurred,
        payload={"providerAreaId": provider_area_id, "requestedArea": requested_area, "raw": container},
    )


def fetch(settings: Settings, client: httpx.Client) -> list[Record]:
    key = settings.api_key("SEOUL_API_KEY")

    def fetch_area(area_identifier: str) -> list[Record]:
        body = safe_json(
            client.get(seoul_url(key, SERVICE, 1, 5, area_identifier)),
            provider="서울 실시간 도시데이터",
        )
        return [record_from_body(area_identifier, body)]

    return collect_scopes(
        area_identifiers(settings), fetch_area, provider="서울 실시간 도시데이터", scope_name="장소"
    )


SOURCE = SourceSpec("seoul.city.realtime", "city.raw", "city", 300, fetch)
