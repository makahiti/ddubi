"""TOPIS 링크별 실시간 도로 소통정보 → ``transport.raw``.

공식 ``TrafficInfo`` API는 링크 ID가 필수이고 XML만 지원한다. ``TRAFFIC_LINK_IDS``에는
서비스 대상 링크만 넣어 호출량을 통제한다. TOPIS 링크 ID를 DDUBI 구간 ID로 간주하지 않고
payload의 provider ID로 보존해 후속 crosswalk 검증을 강제한다.
"""

from __future__ import annotations

from typing import Any
from urllib.parse import quote

import httpx

from config import Settings
from sources.api_utils import collect_scopes, csv_values, first_value, parse_kst, xml_rows
from sources.base import Record, SourceSpec, now_kst

SERVICE = "TrafficInfo"
HOST = "https://openapi.seoul.go.kr:8088"


def records_from_rows(requested_link_id: str, rows: list[dict[str, Any]]) -> list[Record]:
    received = now_kst()
    records = []
    for item in rows:
        link_id = str(first_value(item, ("LINK_ID", "linkId")) or requested_link_id)
        occurred = parse_kst(first_value(item, ("PRCS_DATE", "COLCT_DT", "CREATE_DATE")), fallback=received)
        records.append(
            Record(
                key=link_id,
                event_type="traffic.flow",
                occurred_at=occurred,
                region="SEOUL",
                payload={"providerLinkId": link_id, "raw": item},
            )
        )
    return records


def fetch(settings: Settings, client: httpx.Client) -> list[Record]:
    key = settings.api_key("SEOUL_API_KEY")

    def fetch_link(link_id: str) -> list[Record]:
        url = f"{HOST}/{quote(key, safe='')}/xml/{SERVICE}/1/5/{quote(link_id, safe='')}"
        return records_from_rows(link_id, xml_rows(client.get(url), provider="TOPIS 실시간 소통"))

    return collect_scopes(
        csv_values(settings, "TRAFFIC_LINK_IDS"), fetch_link, provider="TOPIS 실시간 소통", scope_name="링크"
    )


SOURCE = SourceSpec("seoul.topis.traffic", "transport.raw", "transport", 300, fetch)
