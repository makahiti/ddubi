"""One durable delayed retry; budget, concurrent claims, and safe diagnostics."""

import sys
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import patch

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from config import Settings
from request_budget import BudgetExceeded, RequestBudget, RetryScheduled, RetryWait
from sources.bus_location import BusLocationError, fetch_bus_location
from sources.kafka_adapter import collect


class BusRetryTest(unittest.TestCase):
    def test_one_retry_preserves_deadline_and_survives_restart(self):
        clock = [1000.0]
        with tempfile.TemporaryDirectory() as directory:
            path = str(Path(directory) / "budget.db")
            budget = RequestBudget(path, "key", 10, clock=lambda: clock[0])
            self.assertTrue(budget.claim_cycle("bus_location", 28800, 1, allow_retry=True))
            budget.before_request(None)
            self.assertEqual(budget.schedule_retry("bus_location", 300, 1), 1300)
            restarted = RequestBudget(path, "key", 10, clock=lambda: clock[0])
            with self.assertRaises(RetryWait) as caught:
                restarted.claim_cycle("bus_location", 28800, 1, allow_retry=True)
            self.assertEqual(caught.exception.wait_seconds, 300)
            clock[0] = 1300
            self.assertTrue(restarted.claim_cycle("bus_location", 28800, 1, allow_retry=True))
            self.assertEqual(restarted.next_cycle_at("bus_location"), 29800)
            self.assertIsNone(restarted.schedule_retry("bus_location", 300, 1))
            self.assertFalse(restarted.claim_cycle("bus_location", 28800, 1, allow_retry=True))
            clock[0] = 29800
            self.assertTrue(restarted.claim_cycle("bus_location", 28800, 1, allow_retry=True))
            self.assertIsNotNone(restarted.schedule_retry("bus_location", 300, 1))

    def test_budget_insufficient_and_other_sources_keep_cooldown(self):
        with tempfile.TemporaryDirectory() as directory:
            budget = RequestBudget(str(Path(directory) / "budget.db"), "key", 1, clock=lambda: 1000)
            self.assertTrue(budget.claim_cycle("bus_location", 28800, 1, allow_retry=True))
            budget.before_request(None)
            self.assertIsNone(budget.schedule_retry("bus_location", 300, 1))
            with self.assertRaises(BudgetExceeded):
                budget.before_request(None)
        with tempfile.TemporaryDirectory() as directory:
            budget = RequestBudget(str(Path(directory) / "budget.db"), "key", 10, clock=lambda: 1000)
            budget.claim_cycle("bus_arrival", 28800, 1)
            self.assertIsNone(budget.schedule_retry("bus_arrival", 300, 1))
            self.assertFalse(budget.claim_cycle("bus_arrival", 28800, 1))

    def test_retry_claim_is_atomic(self):
        clock = [1000.0]
        with tempfile.TemporaryDirectory() as directory:
            path = str(Path(directory) / "budget.db")
            budget = RequestBudget(path, "key", 10, clock=lambda: clock[0])
            budget.claim_cycle("bus_location", 28800, 1, allow_retry=True)
            budget.schedule_retry("bus_location", 300, 1)
            clock[0] += 300

            def claim(_):
                return RequestBudget(path, "key", 10, clock=lambda: clock[0]).claim_cycle(
                    "bus_location", 28800, 1, allow_retry=True
                )

            with ThreadPoolExecutor(max_workers=3) as pool:
                self.assertEqual(sum(pool.map(claim, range(3))), 1)

    def test_adapter_failed_call_then_delayed_success(self):
        clock = [1000.0]
        calls = []
        with tempfile.TemporaryDirectory() as directory:
            path = str(Path(directory) / "budget.db")
            settings = Settings(
                source="bus_location",
                kafka_bootstrap_servers="unused",
                api_keys={"BUS_LOCATION_API_KEY": "secret-key"},
                extra={"API_BUDGET_PATH": path, "BUS_ROUTE_IDS": "100100118"},
            )

            def budget_factory(*args, **kwargs):
                return RequestBudget(*args, **kwargs, clock=lambda: clock[0])

            def handler(request):
                calls.append(request)
                return (
                    httpx.Response(503)
                    if len(calls) == 1
                    else httpx.Response(
                        200, json={"msgHeader": {"headerCd": "0"}, "msgBody": {"itemList": [{"vehId": "V1"}]}}
                    )
                )

            with patch("sources.kafka_adapter.RequestBudget", side_effect=budget_factory), httpx.Client(
                transport=httpx.MockTransport(handler)
            ) as client:
                with self.assertRaises(RetryScheduled) as caught:
                    collect("bus_location", settings, client)
                self.assertEqual(caught.exception.wait_seconds, 300)
                self.assertIn("HTTP 503", str(caught.exception))
                self.assertNotIn("secret-key", str(caught.exception))
                with self.assertRaises(RetryWait):
                    collect("bus_location", settings, client)
                self.assertEqual(len(calls), 1)
                clock[0] += 300
                self.assertEqual(len(collect("bus_location", settings, client)), 1)
                self.assertEqual(collect("bus_location", settings, client), [])
                self.assertEqual(len(calls), 2)
                self.assertEqual(client.event_hooks["request"], [])

    def test_retry_failure_does_not_schedule_again(self):
        clock = [1000.0]
        with tempfile.TemporaryDirectory() as directory:
            settings = Settings(
                source="bus_location",
                kafka_bootstrap_servers="unused",
                api_keys={"BUS_LOCATION_API_KEY": "key"},
                extra={"API_BUDGET_PATH": str(Path(directory) / "budget.db"), "BUS_ROUTE_IDS": "100100118"},
            )

            def factory(*args, **kwargs):
                return RequestBudget(*args, **kwargs, clock=lambda: clock[0])

            with patch("sources.kafka_adapter.RequestBudget", side_effect=factory), httpx.Client(
                transport=httpx.MockTransport(lambda request: httpx.Response(503))
            ) as client:
                with self.assertRaises(RetryScheduled):
                    collect("bus_location", settings, client)
                clock[0] += 300
                with self.assertRaises(RuntimeError) as caught:
                    collect("bus_location", settings, client)
                self.assertNotIsInstance(caught.exception, RetryScheduled)
                self.assertEqual(collect("bus_location", settings, client), [])

    def test_transient_only_and_no_key_leak(self):
        for status in (401, 403, 429, 503):
            with httpx.Client(
                transport=httpx.MockTransport(lambda request, status=status: httpx.Response(status))
            ) as client:
                with self.assertRaises(BusLocationError) as caught:
                    fetch_bus_location("secret-key", "100100118", client=client)
            self.assertEqual(caught.exception.retryable, status == 503)
            self.assertNotIn("secret-key", str(caught.exception))

        def timeout(request):
            raise httpx.ReadTimeout("secret-key", request=request)

        with httpx.Client(transport=httpx.MockTransport(timeout)) as client:
            with self.assertRaises(BusLocationError) as caught:
                fetch_bus_location("secret-key", "100100118", client=client)
        self.assertTrue(caught.exception.retryable)
        self.assertNotIn("secret-key", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
