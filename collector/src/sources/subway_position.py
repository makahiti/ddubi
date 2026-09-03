"""서울 지하철 호선별 실시간 열차 위치 → ``transport.raw``.

기본값으로 공식 명세의 16개 호선을 조회하고, ``SUBWAY_LINES``가 있으면 해당 범위만 조회한다.
원천의 ``recptnDt``를 발생시각으로 사용해 수집 지연을 측정할 수 있게 하며, 역 ID는 provider
ID로만 저장한다. 노선 마스터와의 canonical 매핑은 이 Collector의 책임이 아니다.
"""

from __future__ import annotations

from typing import Any

import httpx

from config import Settings
from sources.api_utils import as_list, collect_scopes, csv_values, first_value, page_size, parse_kst, safe_json, seoul_url
from sources.base import Record, SourceSpec, now_kst

SERVICE = "realtimePosition"

# 공식 응답 계약에 열거된 호선명이다. 운영자가 SUBWAY_LINES를 지정하면 이 목록을 대체한다.
# 호선 추가·종료는 제공처 계약 변경이므로 테스트와 함께 갱신한다.
DEFAULT_LINES = (
    "1호선",
    "2호선",
    "3호선",
    "4호선",
    "5호선",
    "6호선",
    "7호선",
    "8호선",
    "9호선",
    "경의중앙선",
    "공항철도",
    "경춘선",
    "수인분당선",
    "신분당선",
    "우이신설선",
    "GTX-A",
)


def records_from_body(line: str, body: dict[str, Any]) -> list[Record]:
    rows = as_list(body.get("realtimePositionList"))
    received = now_kst()
    records = []
    for item in rows:
        train_id = str(first_value(item, ("trainNo", "trainId")) or "unknown")
        station_id = str(first_value(item, ("statnId", "statnNm")) or "unknown")
        records.append(
            Record(
                key=f"{line}:{train_id}",
                event_type="subway.position",
                occurred_at=parse_kst(first_value(item, ("recptnDt", "lastRecptnDt")), fallback=received),
                region="SEOUL",
                payload={
                    "providerLineId": str(first_value(item, ("subwayId",)) or line),
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

    def fetch_line(line: str) -> list[Record]:
        body = safe_json(client.get(seoul_url(key, SERVICE, 0, size, line, subway=True)), provider="서울 지하철위치")
        return records_from_body(line, body)

    return collect_scopes(
        csv_values(settings, "SUBWAY_LINES", required=False) or list(DEFAULT_LINES),
        fetch_line,
        provider="서울 지하철위치",
        scope_name="호선",
    )


SOURCE = SourceSpec("seoul.subway.position", "transport.raw", "transport", 30, fetch)
