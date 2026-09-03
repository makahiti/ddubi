"""서울 버스 노선별 정류장 도착예정 정보 → ``transport.raw``.

도착예정시간은 현재 상태를 설명하는 원본 관측이며 학습 정답인 실제 구간 운행시간과 다르다.
응답 전체를 ``raw``에 보존하여 API 필드 변경과 추후 예측오차 분석을 재현할 수 있게 한다.
"""

from __future__ import annotations

from typing import Any

import httpx

from config import Settings
from sources.api_utils import bus_rows, collect_scopes, csv_values, first_value, parse_kst, safe_json
from sources.base import Record, SourceSpec, now_kst

API_URL = "https://ws.bus.go.kr/api/rest/arrive/getArrInfoByRouteAll"


def records_from_rows(route_id: str, rows: list[dict[str, Any]]) -> list[Record]:
    """노선 응답을 정류장별 이벤트로 변환한다. provider 정류장 ID가 Kafka 순서 키다."""
    records = []
    received = now_kst()
    for item in rows:
        stop_id = str(first_value(item, ("stId", "station", "arsId")) or "unknown")
        occurred = parse_kst(first_value(item, ("dataTm", "timestamp")), fallback=received)
        records.append(
            Record(
                key=f"{route_id}:{stop_id}",
                event_type="bus.arrival",
                occurred_at=occurred,
                region="SEOUL",
                payload={"providerRouteId": route_id, "providerStopId": stop_id, "raw": item},
            )
        )
    return records


def fetch(settings: Settings, client: httpx.Client) -> list[Record]:
    key = settings.api_key("DATA_GO_KR_API_KEY")

    def fetch_route(route_id: str) -> list[Record]:
        response = client.get(API_URL, params={"serviceKey": key, "busRouteId": route_id, "resultType": "json"})
        body = safe_json(response, provider="서울 버스도착")
        return records_from_rows(route_id, bus_rows(body))

    return collect_scopes(
        csv_values(settings, "BUS_ROUTE_IDS"), fetch_route, provider="서울 버스도착", scope_name="노선"
    )


SOURCE = SourceSpec("seoul.bus.arrival", "transport.raw", "transport", 30, fetch)
