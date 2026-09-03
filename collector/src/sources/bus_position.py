"""서울 버스 노선별 실시간 위치 → ``transport.raw``.

``BUS_ROUTE_IDS``에 지정한 서울시 provider 노선 ID마다 버스위치 API를 호출한다. 출력의
노선·정류장·차량 ID와 좌표는 원형을 보존하며, 아직 배포 계약이 없는 DDUBI canonical
``segment_id``는 만들지 않는다. 후속 Spark 매핑이 정류장 순서와 마스터 버전을 검증한다.
"""

from __future__ import annotations

from typing import Any

import httpx

from config import Settings
from sources.api_utils import bus_rows, collect_scopes, csv_values, first_value, parse_kst, safe_json
from sources.base import Record, SourceSpec, now_kst

API_URL = "https://ws.bus.go.kr/api/rest/buspos/getBusPosByRtid"


def records_from_rows(route_id: str, rows: list[dict[str, Any]]) -> list[Record]:
    """버스 위치 응답을 차량별 원본 이벤트로 바꾼다. 좌표 오류는 위치만 생략한다."""
    records = []
    received = now_kst()
    for item in rows:
        vehicle_id = str(first_value(item, ("vehId", "plainNo")) or "unknown")
        x = first_value(item, ("gpsX", "x"))
        y = first_value(item, ("gpsY", "y"))
        try:
            location = (float(y), float(x)) if x is not None and y is not None else None
        except (TypeError, ValueError):
            location = None
        occurred = parse_kst(first_value(item, ("dataTm", "lastStTm", "timestamp")), fallback=received)
        records.append(
            Record(
                key=vehicle_id,
                event_type="bus.position",
                occurred_at=occurred,
                region="SEOUL",
                location=location,
                payload={"providerRouteId": route_id, "providerVehicleId": vehicle_id, "raw": item},
            )
        )
    return records


def fetch(settings: Settings, client: httpx.Client) -> list[Record]:
    """설정된 노선들을 순회한다. 노선 일부가 비어 있어도 다른 노선 결과는 보존한다."""
    key = settings.api_key("DATA_GO_KR_API_KEY")

    def fetch_route(route_id: str) -> list[Record]:
        response = client.get(API_URL, params={"serviceKey": key, "busRouteId": route_id, "resultType": "json"})
        body = safe_json(response, provider="서울 버스위치")
        return records_from_rows(route_id, bus_rows(body))

    return collect_scopes(
        csv_values(settings, "BUS_ROUTE_IDS"), fetch_route, provider="서울 버스위치", scope_name="노선"
    )


SOURCE = SourceSpec("seoul.bus.position", "transport.raw", "transport", 30, fetch)
