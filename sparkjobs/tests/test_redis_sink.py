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
    TTL_COL,
    VALUE_COL,
    write_latest_with_row_ttl,
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
                (current.get("occurredAt", current.get("computedAt", "")), current.get("collectedAt", current.get("computedAt", "")))
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


def _row(event_id, occurred_at, collected_at):
    value = json.dumps(
        {"eventId": event_id, "occurredAt": occurred_at, "collectedAt": collected_at},
        separators=(",", ":"),
    )
    return {
        KEY_COL: "traffic:latest:flow:L1",
        VALUE_COL: value,
        TTL_COL: 900,
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
