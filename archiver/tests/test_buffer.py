import json
from datetime import datetime, timezone

import pyarrow.parquet as pq

from buffer import KST, Batch, Row, hour_of
from storage import Storage

TS_MS = int(datetime(2026, 8, 28, 15, 49, 53, tzinfo=KST).timestamp() * 1000)


def _event_bytes(event_id="e1", occurred="2026-08-28T15:00:00+09:00"):
    return json.dumps(
        {
            "eventId": event_id,
            "eventType": "weather.observation",
            "source": "kma.ultra-srt-ncst",
            "occurredAt": occurred,
            "collectedAt": "2026-08-28T15:49:53+09:00",
            "payload": {"nx": 60, "ny": 127},
        },
        ensure_ascii=False,
    ).encode()


def test_hour_of_floors_to_kst_hour():
    assert hour_of(TS_MS) == datetime(2026, 8, 28, 15, 0, tzinfo=KST)


def test_row_extracts_envelope_and_keeps_raw_value():
    row = Row.from_message(b"60:127", _event_bytes(), partition=1, offset=42, timestamp_ms=TS_MS)
    assert row.kafka_key == "60:127"
    assert row.event_id == "e1"
    assert row.event_type == "weather.observation"
    assert row.occurred_at == datetime(2026, 8, 28, 15, 0, tzinfo=KST)
    assert json.loads(row.value)["payload"]["nx"] == 60
    assert row.size_bytes == len(_event_bytes()) + len(b"60:127")


def test_row_keeps_garbage_with_null_envelope():
    row = Row.from_message(None, b"not json at all", partition=0, offset=0, timestamp_ms=TS_MS)
    assert row.value == "not json at all"
    assert row.event_id is None and row.occurred_at is None


def test_batch_due_by_size_or_age(monkeypatch):
    batch = Batch(topic="city.raw", hour=hour_of(TS_MS))
    assert not batch.is_due(flush_seconds=3600, max_bytes=10_000)  # 비어 있으면 아님
    batch.add(Row.from_message(b"k", _event_bytes(), 0, 1, TS_MS))
    assert not batch.is_due(flush_seconds=3600, max_bytes=10_000)
    assert batch.is_due(flush_seconds=3600, max_bytes=10)  # 용량 초과
    batch.opened_at -= 3601
    assert batch.is_due(flush_seconds=3600, max_bytes=10_000)  # 시간 초과


def test_batch_tracks_next_offset_per_partition():
    batch = Batch(topic="city.raw", hour=hour_of(TS_MS))
    batch.add(Row.from_message(b"k", _event_bytes("a"), 0, 5, TS_MS))
    batch.add(Row.from_message(b"k", _event_bytes("b"), 0, 7, TS_MS))
    batch.add(Row.from_message(b"k", _event_bytes("c"), 2, 1, TS_MS))
    assert batch.next_offsets == {0: 8, 2: 2}


def test_batch_relative_path_is_hive_partitioned():
    batch = Batch(topic="city.raw", hour=hour_of(TS_MS))
    batch.add(Row.from_message(b"k", _event_bytes(), 1, 42, TS_MS))
    path = batch.relative_path()
    assert path.startswith("city.raw/date=2026-08-28/hour=15/city.raw-20260828T15-000000000042-")
    assert path.endswith(".parquet")


def test_storage_writes_readable_parquet(tmp_path):
    storage = Storage.from_uri(tmp_path.as_uri())
    batch = Batch(topic="city.raw", hour=hour_of(TS_MS))
    batch.add(Row.from_message(b"60:127", _event_bytes("a"), 1, 42, TS_MS))
    batch.add(Row.from_message(None, b"garbage", 1, 43, TS_MS))

    final = storage.write_parquet(batch.relative_path(), batch.to_table())

    assert not final.endswith(".tmp")
    assert not list(tmp_path.rglob("*.tmp"))
    table = pq.read_table(final)
    assert table.num_rows == 2
    assert table.column("event_id").to_pylist() == ["a", None]
    assert table.column("kafka_offset").to_pylist() == [42, 43]
    occurred = table.column("occurred_at").to_pylist()[0]
    assert occurred.astimezone(timezone.utc) == datetime(2026, 8, 28, 6, 0, tzinfo=timezone.utc)
    assert str(table.schema.field("occurred_at").type) == "timestamp[us, tz=Asia/Seoul]"
