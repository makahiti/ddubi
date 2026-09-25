import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from bus_key_pool import bus_dense_policy_enabled, bus_key_candidates
from config import Settings
from request_budget import RequestBudget
from sources.kafka_adapter import collect


class BusKeyPoolTest(unittest.TestCase):
    def test_whole_cycles_alternate_between_two_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            settings = Settings(
                source="bus_arrival",
                kafka_bootstrap_servers="unused",
                api_keys={"BUS_API_KEY_A": "key-a", "BUS_API_KEY_B": "key-b"},
                extra={
                    "API_BUDGET_PATH": str(Path(directory) / "budget.db"),
                    "BUS_ROUTE_IDS": "100100118",
                    "BUS_ARRIVAL_DAILY_BUDGET_PER_KEY": "9000",
                    "BUS_ARRIVAL_PEAK_INTERVAL_SECONDS": "1",
                    "BUS_ARRIVAL_OFFPEAK_INTERVAL_SECONDS": "1",
                    "BUS_ARRIVAL_WEEKEND_INTERVAL_SECONDS": "1",
                    "BUS_ARRIVAL_OVERNIGHT_INTERVAL_SECONDS": "1",
                },
            )
            used = []
            clock = [1000.0]

            def budget_factory(*args, **kwargs):
                return RequestBudget(*args, **kwargs, clock=lambda: clock[0])

            def fetch(key, targets, **kwargs):
                used.append((key, tuple(targets)))
                kwargs["client"].get("https://test/request")
                return {
                    "routeCount": len(targets),
                    "routeResults": {route: {"msgBody": {"itemList": []}} for route in targets},
                }

            with patch("sources.kafka_adapter.RequestBudget", side_effect=budget_factory):
                with patch("sources.bus_arrival.fetch_all_bus_arrivals", side_effect=fetch):
                    with httpx.Client(transport=httpx.MockTransport(lambda request: httpx.Response(200))) as client:
                        collect("bus_arrival", settings, client)
                        clock[0] += 1
                        collect("bus_arrival", settings, client)
                        self.assertEqual(collect("bus_arrival", settings, client), [])

            self.assertEqual([item[0] for item in used], ["key-a", "key-b"])
            self.assertEqual(used[0][1], used[1][1])

    def test_arrival_and_location_share_the_same_pool(self):
        settings = Settings(
            source="bus_location",
            kafka_bootstrap_servers="unused",
            api_keys={
                "BUS_API_KEY_A": "shared-a",
                "BUS_API_KEY_B": "shared-b",
            },
        )

        expected = [("A", "shared-a"), ("B", "shared-b")]
        self.assertEqual(bus_key_candidates(settings, "bus_location"), expected)
        self.assertEqual(bus_key_candidates(settings, "bus_arrival"), expected)
        self.assertTrue(bus_dense_policy_enabled(settings, "bus_location"))
        self.assertTrue(bus_dense_policy_enabled(settings, "bus_arrival"))

    def test_duplicate_pool_credentials_do_not_enable_dense_policy(self):
        settings = Settings(
            source="bus_location",
            kafka_bootstrap_servers="unused",
            api_keys={
                "BUS_API_KEY_A": "same-key",
                "BUS_API_KEY_B": "same-key",
            },
        )

        self.assertEqual(bus_key_candidates(settings, "bus_location"), [("A", "same-key")])
        self.assertFalse(bus_dense_policy_enabled(settings, "bus_location"))

    def test_encoding_and_decoding_forms_count_as_one_credential(self):
        settings = Settings(
            source="bus_location",
            kafka_bootstrap_servers="unused",
            api_keys={"BUS_API_KEY_A": "abc%2Bdef%3D", "BUS_API_KEY_B": "abc+def="},
        )

        self.assertEqual(bus_key_candidates(settings, "bus_location"), [("A", "abc%2Bdef%3D")])
        self.assertFalse(bus_dense_policy_enabled(settings, "bus_location"))


if __name__ == "__main__":
    unittest.main()
