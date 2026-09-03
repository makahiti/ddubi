"""TOPIS 실시간 돌발정보 → ``city.raw``.

사고·통제는 속도 관측과 의미가 달라 별도 이벤트로 발행한다. 기본 서비스명은 공식 TOPIS의
``AccInfo``이며, 제공처 명세 변경 시 ``TRAFFIC_INCIDENT_SERVICE_NAME``으로 교체할 수 있다.
좌표 체계가 명확하지 않은 X/Y 값은 위경도로 오인하지 않고 원본 payload에만 남긴다.
"""

from __future__ import annotations

from typing import Any
from urllib.parse import quote

import httpx

from config import Settings
from sources.api_utils import first_value, page_size, parse_kst, xml_rows
from sources.base import Record, SourceSpec, now_kst

HOST = "https://openapi.seoul.go.kr:8088"


def records_from_rows(rows: list[dict[str, Any]]) -> list[Record]:
    received = now_kst()
    records = []
    for item in rows:
        incident_id = str(first_value(item, ("ACC_ID", "INCIDENT_ID", "ID")) or "unknown")
        occurred = parse_kst(first_value(item, ("OCC_DATE", "OCC_DT", "START_DATE")), fallback=received)
        records.append(
            Record(
                key=incident_id,
                event_type="traffic.incident",
                occurred_at=occurred,
                payload={"providerIncidentId": incident_id, "raw": item},
            )
        )
    return records


def fetch(settings: Settings, client: httpx.Client) -> list[Record]:
    key = settings.api_key("SEOUL_API_KEY")
    service = settings.extra.get("TRAFFIC_INCIDENT_SERVICE_NAME", "AccInfo")
    size = page_size(settings)
    url = f"{HOST}/{quote(key, safe='')}/xml/{quote(service, safe='')}/1/{size}"
    return records_from_rows(xml_rows(client.get(url), provider="TOPIS 실시간 돌발"))


SOURCE = SourceSpec("seoul.topis.incident", "city.raw", "city", 60, fetch)
