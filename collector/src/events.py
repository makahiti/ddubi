"""Kafka 이벤트 봉투. docs/contracts/events/*.schema.json 과 필드가 1:1 로 대응한다.

- additionalProperties: false 에 맞춰 extra="forbid"
- 시간은 ISO 8601 + Asia/Seoul 오프셋(+09:00)
- 위치는 원본 좌표(location)와 뚜비 내부 구간(segmentIds) 두 형태. 수집기가 변환할 수 있으면
  segmentIds 를 채우고, 아니면 Spark 전처리가 채운다. 둘 다 선택 필드.
- 계약 파일과 어긋나지 않는지는 tests/test_events.py 가 검사한다
"""

from __future__ import annotations

from datetime import datetime
from typing import Any
from uuid import uuid4

from pydantic import BaseModel, ConfigDict, Field

from sources.base import KST, Record, SourceSpec


class Location(BaseModel):
    model_config = ConfigDict(extra="forbid")

    latitude: float
    longitude: float


class TransportEvent(BaseModel):
    """docs/contracts/events/transport-event.schema.json"""

    model_config = ConfigDict(extra="forbid")

    eventId: str
    eventType: str = Field(min_length=1)
    source: str = Field(min_length=1)
    region: str | None = None
    occurredAt: datetime
    collectedAt: datetime
    location: Location | None = None
    segmentIds: list[str] | None = None
    payload: dict[str, Any]


class CityEvent(BaseModel):
    """docs/contracts/events/city-event.schema.json"""

    model_config = ConfigDict(extra="forbid")

    eventId: str
    eventType: str = Field(min_length=1)
    source: str = Field(min_length=1)
    occurredAt: datetime
    collectedAt: datetime
    location: Location | None = None
    segmentIds: list[str] | None = None
    payload: dict[str, Any]


SCHEMA_MODELS = {"transport": TransportEvent, "city": CityEvent}


def _to_kst(dt: datetime) -> datetime:
    if dt.tzinfo is None:
        raise ValueError("occurredAt/collectedAt 은 tz-aware 여야 합니다")
    return dt.astimezone(KST)


def build_event(spec: SourceSpec, record: Record, collected_at: datetime) -> dict[str, Any]:
    """Record 를 계약에 맞는 이벤트 dict 로 만든다 (JSON 직렬화 가능한 형태)."""
    location = Location(latitude=record.location[0], longitude=record.location[1]) if record.location else None
    segment_ids = sorted(set(record.segment_ids)) if record.segment_ids else None  # uniqueItems
    common = {
        "eventId": str(uuid4()),
        "eventType": record.event_type,
        "source": spec.name,
        "occurredAt": _to_kst(record.occurred_at),
        "collectedAt": _to_kst(collected_at),
        "location": location,
        "segmentIds": segment_ids,
        "payload": record.payload,
    }
    if spec.schema == "transport":
        event = TransportEvent(**common, region=record.region)
    else:
        event = CityEvent(**common)
    return event.model_dump(mode="json", exclude_none=True)
