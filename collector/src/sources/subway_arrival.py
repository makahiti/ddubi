"""서울 지하철 역별 실시간 도착정보 → ``transport.raw``.

API가 역명 단위 조회만 제공하므로 ``SUBWAY_STATIONS``에 서비스 대상 역명을 명시한다.
``recptnDt``는 원천 생성시각이며 현재시각과의 차이를 stale 판단에 사용할 수 있도록 보존한다.
"""

from __future__ import annotations

from typing import Any

import httpx

from config import Settings
from sources.api_utils import as_list, collect_scopes, csv_values, first_value, page_size, parse_kst, safe_json, seoul_url
from sources.base import Record, SourceSpec, now_kst

SERVICE = "realtimeStationArrival"


def records_from_body(station: str, body: dict[str, Any]) -> list[Record]:
    rows = as_list(body.get("realtimeArrivalList"))
    received = now_kst()
    records = []
    for item in rows:
        train_id = str(first_value(item, ("btrainNo", "trainLineNm")) or "unknown")
        station_id = str(first_value(item, ("statnId",)) or station)
        line_id = str(first_value(item, ("subwayId",)) or "unknown")
        records.append(
            Record(
                key=f"{line_id}:{station_id}:{train_id}",
                event_type="subway.arrival",
                occurred_at=parse_kst(first_value(item, ("recptnDt",)), fallback=received),
                region="SEOUL",
                payload={
                    "providerLineId": line_id,
                    "providerStationId": station_id,
                    "providerTrainId": train_id,
                    "raw": item,
                },
            )
        )
    return records


def fetch(settings: Settings, client: httpx.Client) -> list[Record]:
    key = settings.api_key("SEOUL_API_KEY")
    size = page_size(settings)

    def fetch_station(station: str) -> list[Record]:
        body = safe_json(client.get(seoul_url(key, SERVICE, 0, size, station, subway=True)), provider="서울 지하철도착")
        return records_from_body(station, body)

    return collect_scopes(
        csv_values(settings, "SUBWAY_STATIONS"), fetch_station, provider="서울 지하철도착", scope_name="역"
    )


SOURCE = SourceSpec("seoul.subway.arrival", "transport.raw", "transport", 30, fetch)
