import fnmatch
import json
import sys
import unittest
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from common.topis_redis import build_reconciliation_plans, reconcile_topis_latest


def row(event_id, collected, status="VALID", event_status="ACTIVE", valid_until="2026-09-20T00:00:00+09:00"):
    return {
        "noticeId": "6155",
        "eventId": event_id,
        "collectedAt": collected,
        "processingStatus": status,
        "eventStatus": event_status,
        "validUntil": valid_until,
    }


class FakeRedis:
    def __init__(self, state, **_):
        self.state = state

    def scan_iter(self, match, count=100):
        self.asserted_count = count
        yield from [key for key in list(self.state) if fnmatch.fnmatch(key, match)]

    def get(self, key):
        return self.state.get(key)

    def eval(self, script, key_count, key, cutoff):
        assert "current_collected > ARGV[1]" in script
        assert key_count == 1
        current = json.loads(self.state[key])
        observed = current.get("_latestOrderAt") or current.get("collectedAt") or ""
        if not observed or observed > cutoff:
            return 0
        del self.state[key]
        return 1

    def close(self):
        return None


class TopisRedisTests(unittest.TestCase):
    def test_latest_notice_version_controls_active_event_ids(self):
        rows = [
            row("6155-1", "2026-09-17T09:00:00+09:00"),
            row("6155-2", "2026-09-17T09:00:00+09:00"),
            row("6155-1", "2026-09-17T10:00:00+09:00"),
            row("6155-2", "2026-09-17T10:00:00+09:00", event_status="ENDED"),
        ]
        plans = build_reconciliation_plans(rows, datetime(2026, 9, 17, tzinfo=timezone.utc))
        self.assertEqual(plans[0]["activeEventIds"], ["6155-1"])

    def test_invalid_ai_result_never_deletes_existing_keys(self):
        plans = build_reconciliation_plans(
            [row("6155-error", "2026-09-17T10:00:00+09:00", status="INVALID_AI_RESPONSE")]
        )
        self.assertEqual(plans, [])

    def test_reconcile_deletes_removed_old_key_but_not_newer_or_other_notice(self):
        def value(notice, event, collected):
            return json.dumps({"noticeId": notice, "eventId": event, "collectedAt": collected})

        state = {
            "traffic:latest:topis-event:6155-1": value("6155", "6155-1", "2026-09-17T10:00:00+09:00"),
            "traffic:latest:topis-event:6155-2": value("6155", "6155-2", "2026-09-17T09:00:00+09:00"),
            "traffic:latest:topis-event:6155-3": value("6155", "6155-3", "2026-09-17T11:00:00+09:00"),
            "traffic:latest:topis-event:9999-1": value("9999", "9999-1", "2026-09-17T09:00:00+09:00"),
        }
        fake = SimpleNamespace(Redis=lambda **kwargs: FakeRedis(state, **kwargs))
        plan = {"noticeId": "6155", "collectedAt": "2026-09-17T10:00:00+09:00", "activeEventIds": ["6155-1"]}
        with patch.dict(sys.modules, {"redis": fake}):
            deleted = reconcile_topis_latest([plan], "redis", 6379, None)
        self.assertEqual(deleted, 1)
        self.assertNotIn("traffic:latest:topis-event:6155-2", state)
        self.assertIn("traffic:latest:topis-event:6155-3", state)
        self.assertIn("traffic:latest:topis-event:9999-1", state)


if __name__ == "__main__":
    unittest.main()
