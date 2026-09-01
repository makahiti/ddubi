"""이벤트 모델이 docs/contracts/events/*.schema.json 과 어긋나지 않는지 검사한다."""

import json
from datetime import datetime, timezone
from pathlib import Path

import httpx
import pytest
from pydantic import ValidationError

from events import CityEvent, TransportEvent, build_event
from sources.base import KST, Record, SourceSpec

CONTRACTS = Path(__file__).resolve().parents[2] / "docs" / "contracts" / "events"


def _schema(name: str) -> dict:
    return json.loads((CONTRACTS / name).read_text(encoding="utf-8"))


def _noop_fetch(settings, client: httpx.Client):
    return []


TRANSPORT_SPEC = SourceSpec("test.bus", "transport.raw", "transport", 60, _noop_fetch)
CITY_SPEC = SourceSpec("test.weather", "city.raw", "city", 60, _noop_fetch)


@pytest.mark.parametrize(
    ("model", "schema_file"),
    [(TransportEvent, "transport-event.schema.json"), (CityEvent, "city-event.schema.json")],
)
def test_model_fields_match_contract(model, schema_file):
    schema = _schema(schema_file)
    model_fields = model.model_fields
    assert set(model_fields) == set(schema["properties"]), "필드 목록이 계약과 다름"
    required = {name for name, f in model_fields.items() if f.is_required()}
    assert required == set(schema["required"]), "필수 필드가 계약과 다름"
    assert schema.get("additionalProperties") is False
    assert model.model_config.get("extra") == "forbid"


def test_build_transport_event_has_kst_offset_and_location():
    record = Record(
        key="100100118",
        event_type="bus.position",
        occurred_at=datetime(2026, 8, 27, 0, 0, tzinfo=timezone.utc),  # 09:00 KST
        payload={"vehicleId": "1234"},
        region="seoul",
        location=(37.5665, 126.9780),
    )
    event = build_event(TRANSPORT_SPEC, record, datetime(2026, 8, 27, 9, 0, 30, tzinfo=KST))

    assert event["source"] == "test.bus"
    assert event["occurredAt"] == "2026-08-27T09:00:00+09:00"
    assert event["collectedAt"] == "2026-08-27T09:00:30+09:00"
    assert event["location"] == {"latitude": 37.5665, "longitude": 126.978}
    assert event["region"] == "seoul"
    assert len(event["eventId"]) == 36


def test_build_city_event_omits_region_and_empty_optionals():
    record = Record(
        key="60:127",
        event_type="weather.observation",
        occurred_at=datetime(2026, 8, 27, 9, tzinfo=KST),
        payload={"T1H": 25.0},
        region="ignored",  # city 계약에는 region 이 없다
    )
    event = build_event(CITY_SPEC, record, datetime(2026, 8, 27, 9, 5, tzinfo=KST))
    assert set(event) == {"eventId", "eventType", "source", "occurredAt", "collectedAt", "payload"}


def test_build_city_event_with_location_and_segment_ids():
    record = Record(
        key="incident-1",
        event_type="road.incident",
        occurred_at=datetime(2026, 8, 27, 9, tzinfo=KST),
        payload={"desc": "사고"},
        location=(37.5665, 126.9780),
        segment_ids=["seg-2", "seg-1", "seg-2"],  # 중복은 제거되고 정렬된다 (uniqueItems)
    )
    event = build_event(CITY_SPEC, record, datetime(2026, 8, 27, 9, 5, tzinfo=KST))
    assert event["location"] == {"latitude": 37.5665, "longitude": 126.978}
    assert event["segmentIds"] == ["seg-1", "seg-2"]


def test_naive_datetime_is_rejected():
    record = Record(key="k", event_type="x", occurred_at=datetime(2026, 8, 27, 9), payload={})
    with pytest.raises(ValueError):
        build_event(CITY_SPEC, record, datetime(2026, 8, 27, 9, tzinfo=KST))


def test_extra_field_is_rejected():
    with pytest.raises(ValidationError):
        CityEvent(
            eventId="e", eventType="t", source="s",
            occurredAt=datetime(2026, 8, 27, 9, tzinfo=KST),
            collectedAt=datetime(2026, 8, 27, 9, tzinfo=KST),
            payload={}, unexpected="x",
        )
