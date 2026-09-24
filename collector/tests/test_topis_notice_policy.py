import sys
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from topis_notice_policy import plan_topis_notice

KST = timezone(timedelta(hours=9))


class TopisNoticePolicyTest(unittest.TestCase):
    def test_weekday_peak_offpeak_and_overnight_cadence(self):
        monday = datetime(2026, 9, 21, tzinfo=KST)
        self.assertEqual(plan_topis_notice(monday.replace(hour=7), {}).interval_seconds, 300)
        self.assertEqual(plan_topis_notice(monday.replace(hour=12), {}).interval_seconds, 600)
        self.assertEqual(plan_topis_notice(monday.replace(hour=2), {}).interval_seconds, 3600)

    def test_weekend_does_not_use_peak_cadence(self):
        saturday = datetime(2026, 9, 26, 7, 0, tzinfo=KST)
        plan = plan_topis_notice(saturday, {})
        self.assertEqual(plan.interval_seconds, 600)
        self.assertEqual(plan.scope, "weekend-recent-notices")

    def test_window_boundaries(self):
        monday = datetime(2026, 9, 21, tzinfo=KST)
        self.assertEqual(plan_topis_notice(monday.replace(hour=5), {}).interval_seconds, 600)
        self.assertEqual(plan_topis_notice(monday.replace(hour=6, minute=29), {}).interval_seconds, 600)
        self.assertEqual(plan_topis_notice(monday.replace(hour=6, minute=30), {}).interval_seconds, 300)
        self.assertEqual(plan_topis_notice(monday.replace(hour=9, minute=30), {}).interval_seconds, 600)
        self.assertEqual(plan_topis_notice(monday.replace(hour=0), {}).interval_seconds, 3600)

    def test_custom_intervals_are_supported(self):
        monday = datetime(2026, 9, 21, 7, 0, tzinfo=KST)
        plan = plan_topis_notice(monday, {"TOPIS_NOTICE_PEAK_INTERVAL_SECONDS": "420"})
        self.assertEqual(plan.interval_seconds, 420)


if __name__ == "__main__":
    unittest.main()
