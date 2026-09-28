import sys
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from bus_collection_policy import plan_bus_collection

KST = timezone(timedelta(hours=9))


class BusCollectionPolicyTest(unittest.TestCase):
    def setUp(self):
        self.targets = [f"r{i:03d}" for i in range(100)]

    def test_arrival_uses_peak_offpeak_and_overnight_cadence(self):
        monday = datetime(2026, 9, 21, tzinfo=KST)
        self.assertEqual(
            plan_bus_collection("bus_arrival", self.targets, monday.replace(hour=7), {}).interval_seconds, 300
        )
        self.assertEqual(
            plan_bus_collection("bus_arrival", self.targets, monday.replace(hour=12), {}).interval_seconds, 900
        )
        self.assertEqual(
            plan_bus_collection("bus_arrival", self.targets, monday.replace(hour=2), {}).interval_seconds, 1800
        )

    def test_location_rotates_dense_panel_and_includes_sparse_panel_every_five_minutes(self):
        monday = datetime(2026, 9, 21, 7, 2, 30, tzinfo=KST)
        tuesday = monday.replace(day=22)
        monday_plan = plan_bus_collection("bus_location", self.targets, monday, {})
        tuesday_plan = plan_bus_collection("bus_location", self.targets, tuesday, {})
        self.assertEqual(monday_plan.interval_seconds, 150)
        self.assertEqual(len(monday_plan.targets), 50)
        self.assertEqual(len(tuesday_plan.targets), 50)
        self.assertTrue(set(monday_plan.targets).isdisjoint(tuesday_plan.targets))
        full = plan_bus_collection("bus_location", self.targets, monday.replace(minute=5, second=0), {})
        self.assertEqual(len(full.targets), 100)

    def test_weekend_keeps_low_frequency_full_coverage(self):
        saturday = datetime(2026, 9, 26, 12, 0, tzinfo=KST)
        plan = plan_bus_collection("bus_location", self.targets, saturday, {})
        self.assertEqual(plan.interval_seconds, 900)
        self.assertEqual(plan.targets, self.targets)


if __name__ == "__main__":
    unittest.main()
