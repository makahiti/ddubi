import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from common.traffic_incident_policy import (
    TrafficIncidentPolicy,
    TrafficIncidentPolicyError,
    policy,
)


class TrafficIncidentPolicyTests(unittest.TestCase):
    def test_defaults_have_named_fields(self):
        self.assertEqual(policy({}), TrafficIncidentPolicy(2700, 200, 100))

    def test_explicit_values_are_assigned_to_the_correct_policy(self):
        result = policy(
            {
                "TRAFFIC_INCIDENT_STALE_SECONDS": "3600",
                "TRAFFIC_LINK_COORD_TOLERANCE_METERS": "250",
                "TRAFFIC_NEARBY_MAX_METERS": "75",
            }
        )
        self.assertEqual(result.stale_seconds, 3600)
        self.assertEqual(result.direct_tolerance_meters, 250)
        self.assertEqual(result.nearby_max_meters, 75)

    def test_non_integer_reports_the_exact_environment_variable(self):
        with self.assertRaisesRegex(
            TrafficIncidentPolicyError,
            "TRAFFIC_LINK_COORD_TOLERANCE_METERS must be an integer",
        ):
            policy({"TRAFFIC_LINK_COORD_TOLERANCE_METERS": "two hundred"})

    def test_each_out_of_range_value_is_rejected_separately(self):
        cases = (
            ("TRAFFIC_INCIDENT_STALE_SECONDS", "899", "between 900 and 86400"),
            ("TRAFFIC_LINK_COORD_TOLERANCE_METERS", "2001", "between 10 and 2000"),
            ("TRAFFIC_NEARBY_MAX_METERS", "9", "between 10 and 1000"),
        )
        for name, value, message in cases:
            with self.subTest(name=name):
                with self.assertRaisesRegex(TrafficIncidentPolicyError, f"{name} must be {message}"):
                    policy({name: value})


if __name__ == "__main__":
    unittest.main()
