"""Redis 최신값 sink의 원자 비교 호출 계약을 실제 Redis 없이 검증한다.

Spark 변환 테스트만으로는 다음 micro-batch의 과거 이벤트가 기존 Redis 값을 덮는 회귀를 잡을
수 없다. 여기서는 redis-py pipeline을 작은 메모리 가짜 구현으로 바꿔 Lua에 전달되는 키·값·TTL·
시각 순서와, 새 이벤트 뒤에 과거 이벤트가 도착해도 최종 상태가 유지되는 시나리오를 검증한다.
실제 원자성은 Redis의 단일 Lua 실행이 보장한다.
"""

import json
import sys
from types import SimpleNamespace

import pytest
from common.redis_sink import (
    COLLECTED_TIME_COL,
    EVENT_TIME_COL,
    KEY_COL,
    MAX_ITEMS_COL,
    TTL_COL,
    VALUE_COL,
    write_latest_with_row_ttl,
    write_subway_snapshots,
)


class _FakePipeline:
    """Lua 인자 순서를 사용해 테스트에 필요한 최신값 비교만 재현한다."""

    def __init__(self, state):
        self.state = state
        self.pending = []

    def eval(self, script, key_count, key, value, ttl, occurred_at, collected_at):
        assert "current_occurred > incoming_occurred" in script
        assert key_count == 1
        self.pending.append((key, value, ttl, occurred_at, collected_at))

    def execute(self):
        results = []
        for key, value, ttl, occurred_at, collected_at in self.pending:
            current = json.loads(self.state[key][0]) if key in self.state else None
            should_write = current is None or (
                (
                    current.get("occurredAt", current.get("computedAt", "")),
                    current.get("collectedAt", current.get("computedAt", "")),
                )
                <= (occurred_at, collected_at)
            )
            if should_write:
                self.state[key] = (value, ttl)
                results.append(1)
            else:
                results.append(0)
        self.pending.clear()
        return results


class _FakeRedis:
    def __init__(self, state, **_):
        self.state = state

    def pipeline(self, transaction=False):
        assert transaction is False
        return _FakePipeline(self.state)

    def close(self):
        return None


class _FakeDataFrame:
    def __init__(self, rows):
        self.rows = rows

    def select(self, *columns):
        assert columns == (KEY_COL, VALUE_COL, TTL_COL, EVENT_TIME_COL, COLLECTED_TIME_COL)
        return self

    def foreachPartition(self, writer):
        writer(iter(self.rows))


def _row(event_id, occurred_at, collected_at, key="traffic:latest:flow:L1", ttl=900):
    value = json.dumps(
        {"eventId": event_id, "occurredAt": occurred_at, "collectedAt": collected_at},
        separators=(",", ":"),
    )
    return {
        KEY_COL: key,
        VALUE_COL: value,
        TTL_COL: ttl,
        EVENT_TIME_COL: occurred_at,
        COLLECTED_TIME_COL: collected_at,
    }


def test_late_event_cannot_replace_newer_redis_value(monkeypatch):
    """새 관측 저장 뒤 다음 배치의 과거 관측이 와도 Redis 값과 TTL을 유지한다."""
    state = {}
    fake_module = SimpleNamespace(Redis=lambda **kwargs: _FakeRedis(state, **kwargs))
    monkeypatch.setitem(sys.modules, "redis", fake_module)

    newer = _row("new", "2026-09-04T12:00:00.000+09:00", "2026-09-04T12:00:10.000+09:00")
    older = _row("old", "2026-09-04T11:55:00.000+09:00", "2026-09-04T12:00:20.000+09:00")
    write_latest_with_row_ttl(_FakeDataFrame([newer]), "redis", 6379, None)
    write_latest_with_row_ttl(_FakeDataFrame([older]), "redis", 6379, None)

    stored_value, stored_ttl = state[newer[KEY_COL]]
    assert json.loads(stored_value)["eventId"] == "new"
    assert stored_ttl == 900


def test_late_city_snapshot_cannot_replace_newer_area_value(monkeypatch):
    state = {}
    monkeypatch.setitem(sys.modules, "redis", SimpleNamespace(Redis=lambda **kwargs: _FakeRedis(state, **kwargs)))
    key = "city:latest:area:POI009"
    newer = _row("city-new", "2026-09-17T16:25:00.000+09:00", "2026-09-17T16:30:00.000+09:00", key, 64800)
    older = _row("city-old", "2026-09-17T10:25:00.000+09:00", "2026-09-17T16:31:00.000+09:00", key, 64800)
    write_latest_with_row_ttl(_FakeDataFrame([newer]), "redis", 6379, None)
    write_latest_with_row_ttl(_FakeDataFrame([older]), "redis", 6379, None)
    assert json.loads(state[key][0])["eventId"] == "city-new"
    assert state[key][1] == 64800


def test_latest_sink_rejects_missing_order_timestamp(monkeypatch):
    """최신성 증거가 없는 행을 임의로 현재값으로 쓰지 않는다."""
    state = {}
    fake_module = SimpleNamespace(Redis=lambda **kwargs: _FakeRedis(state, **kwargs))
    monkeypatch.setitem(sys.modules, "redis", fake_module)
    invalid = _row("invalid", "", "2026-09-04T12:00:20.000+09:00")

    with pytest.raises(ValueError, match="occurredAtOrder"):
        write_latest_with_row_ttl(_FakeDataFrame([invalid]), "redis", 6379, None)
    assert state == {}


def test_old_status_replay_cannot_replace_newer_computed_state(monkeypatch):
    """구간 판정은 occurredAt을 추가하지 않아도 computedAt으로 과거 Replay를 거절해야 한다."""
    state = {}
    monkeypatch.setitem(sys.modules, "redis", SimpleNamespace(Redis=lambda **kwargs: _FakeRedis(state, **kwargs)))
    new_time, old_time = "2026-09-10T12:10:00+09:00", "2026-09-10T12:09:00+09:00"
    newer = _row("new", new_time, new_time)
    older = _row("old", old_time, old_time)
    for row, time, status in [(newer, new_time, "delayed"), (older, old_time, "normal")]:
        row[VALUE_COL] = json.dumps({"computedAt": time, "status": status})
        write_latest_with_row_ttl(_FakeDataFrame([row]), "redis", 6379, None)
    assert json.loads(state[newer[KEY_COL]][0])["status"] == "delayed"


class _FakeSnapshotPipeline:
    """Snapshot Lua의 열차별 최신 병합 결과를 메모리에서 재현한다."""

    def __init__(self, state):
        self.state = state
        self.pending = []

    def eval(self, script, key_count, key, value, ttl, occurred_at, collected_at, max_items):
        assert "by_train" in script
        assert key_count == 1
        self.pending.append((key, value, ttl, occurred_at, collected_at, max_items))

    def execute(self):
        results = []
        for key, value, ttl, occurred_at, _collected_at, max_items in self.pending:
            incoming = json.loads(value)
            current = json.loads(self.state[key][0]) if key in self.state else None
            items = {}
            changed = current is None
            if current:
                for item in current["items"]:
                    if item.get("validUntil", "") > occurred_at:
                        items[item["trainId"]] = item
                    else:
                        changed = True
            for item in incoming["items"]:
                previous = items.get(item["trainId"])
                previous_order = (previous.get("occurredAt", ""), previous.get("collectedAt", "")) if previous else None
                item_order = (item.get("occurredAt", ""), item.get("collectedAt", ""))
                if previous is None or previous_order <= item_order:
                    items[item["trainId"]] = item
                    if (
                        previous is None
                        or previous_order != item_order
                        or previous.get("eventId") != item.get("eventId")
                    ):
                        changed = True
            if current and not changed:
                results.append(0)
                continue
            incoming["items"] = sorted(
                items.values(),
                key=lambda item: (item.get("arrivalSeconds") is None, item.get("arrivalSeconds", 0), item["trainId"]),
            )[:max_items]
            incoming["itemCount"] = len(incoming["items"])
            if incoming["items"]:
                incoming["validUntil"] = max(item["validUntil"] for item in incoming["items"])
            if current:
                incoming["observedAt"] = max(incoming["observedAt"], current["observedAt"])
                incoming["collectedAt"] = max(incoming["collectedAt"], current["collectedAt"])
            self.state[key] = (json.dumps(incoming), ttl)
            results.append(1)
        self.pending.clear()
        return results


class _FakeSnapshotRedis(_FakeRedis):
    def pipeline(self, transaction=False):
        assert transaction is False
        return _FakeSnapshotPipeline(self.state)


class _FakeSnapshotDataFrame(_FakeDataFrame):
    def select(self, *columns):
        assert columns == (KEY_COL, VALUE_COL, TTL_COL, EVENT_TIME_COL, COLLECTED_TIME_COL, MAX_ITEMS_COL)
        return self


def _snapshot_row(train_id, observed_at, *, event_id=None, arrival_seconds=60):
    valid_until = observed_at.replace("12:00:00", "12:02:00").replace("11:55:00", "11:57:00")
    item = {
        "trainId": train_id,
        "arrivalSeconds": arrival_seconds,
        "occurredAt": observed_at,
        "collectedAt": observed_at,
        "eventId": event_id or train_id,
        "validUntil": valid_until,
    }
    value = {
        "schemaVersion": "subway-snapshot-v1",
        "snapshotType": "SUBWAY_ARRIVALS_BY_STATION",
        "scope": {"lineId": "1002", "stationId": "0201"},
        "observedAt": observed_at,
        "collectedAt": observed_at,
        "validUntil": valid_until,
        "items": [item],
        "itemCount": 1,
    }
    return {
        KEY_COL: "transit:snapshot:subway-arrivals:1002:0201",
        VALUE_COL: json.dumps(value),
        TTL_COL: 1080,
        EVENT_TIME_COL: observed_at,
        COLLECTED_TIME_COL: observed_at,
        MAX_ITEMS_COL: 128,
    }


def test_subway_snapshot_merges_partial_batches_and_rejects_old_replay(monkeypatch):
    """부분 batch의 다른 열차를 보존하고 과거 replay로 값·TTL을 갱신하지 않는다."""
    state = {}
    monkeypatch.setitem(
        sys.modules, "redis", SimpleNamespace(Redis=lambda **kwargs: _FakeSnapshotRedis(state, **kwargs))
    )
    first = _snapshot_row("T1", "2026-09-04T12:00:00.000+09:00", arrival_seconds=60)
    second = _snapshot_row("T2", "2026-09-04T12:00:00.000+09:00", arrival_seconds=120)
    old = _snapshot_row("T1", "2026-09-04T11:55:00.000+09:00", event_id="old", arrival_seconds=300)

    write_subway_snapshots(_FakeSnapshotDataFrame([first]), "redis", 6379, None)
    write_subway_snapshots(_FakeSnapshotDataFrame([second]), "redis", 6379, None)
    before_old = state[first[KEY_COL]]
    write_subway_snapshots(_FakeSnapshotDataFrame([old]), "redis", 6379, None)

    stored = json.loads(state[first[KEY_COL]][0])
    assert [item["trainId"] for item in stored["items"]] == ["T1", "T2"]
    assert stored["items"][0]["arrivalSeconds"] == 60
    assert state[first[KEY_COL]] == before_old
