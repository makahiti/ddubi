"""서울교통공사 지하철 이례상황 알림 → ``city.raw``.

공공데이터포털 활용신청 후 표시되는 실제 요청 URL을 ``SUBWAY_ALERT_API_URL``에 넣는다.
URL에는 인증키를 포함하지 않고 ``DATA_GO_KR_API_KEY``가 query parameter로 추가된다. 알림은
지연의 정확한 분 단위 정답이 아니라 위험 원인이므로 원문과 시작·종료시각을 보존한다.
"""

from __future__ import annotations

from typing import Any

import httpx

from config import ConfigError, Settings
from sources.api_utils import as_list, first_value, page_size, parse_kst, safe_json
from sources.base import Record, SourceSpec, now_kst


def _items(body: dict[str, Any], service: str | None) -> list[dict[str, Any]]:
    """공공데이터 게이트웨이와 서울시 경로형 JSON 응답을 모두 수용한다."""
    gateway = body.get("response", {}).get("body", {})
    if isinstance(gateway, dict):
        items = gateway.get("items", {})
        if isinstance(items, dict):
            rows = as_list(items.get("item"))
            if rows:
                return rows
    if service and isinstance(body.get(service), dict):
        return as_list(body[service].get("row"))
    for value in body.values():
        if isinstance(value, dict) and "row" in value:
            return as_list(value.get("row"))
    return []


def records_from_body(body: dict[str, Any], service: str | None = None) -> list[Record]:
    received = now_kst()
    records = []
    for item in _items(body, service):
        alert_id = str(first_value(item, ("noticeId", "ntcId", "alimId", "id", "sn")) or "unknown")
        start = first_value(item, ("startDt", "bgngDt", "startDate", "regDt", "createdAt"))
        end = first_value(item, ("endDt", "endDate", "endAt"))
        records.append(
            Record(
                key=alert_id,
                event_type="subway.alert",
                occurred_at=parse_kst(start, fallback=received),
                payload={"providerAlertId": alert_id, "activeUntil": end, "raw": item},
            )
        )
    return records


def fetch(settings: Settings, client: httpx.Client) -> list[Record]:
    url = settings.extra.get("SUBWAY_ALERT_API_URL")
    if not url:
        raise ConfigError("SUBWAY_ALERT_API_URL에 활용신청 후 확인한 요청 URL을 설정하세요")
    if "serviceKey=" in url:
        raise ConfigError("SUBWAY_ALERT_API_URL에는 인증키를 넣지 마세요")
    key = settings.api_key("DATA_GO_KR_API_KEY")
    params = {"serviceKey": key, "pageNo": 1, "numOfRows": page_size(settings), "type": "json"}
    body = safe_json(client.get(url, params=params), provider="서울교통공사 지하철 알림")
    return records_from_body(body, settings.extra.get("SUBWAY_ALERT_SERVICE_NAME"))


SOURCE = SourceSpec("seoul.subway.alert", "city.raw", "city", 60, fetch)
