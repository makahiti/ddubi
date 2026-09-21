"""No-service polling, special-operation overrides and no-data HTTP responses."""

import sys
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from config import ConfigError, Settings
from sources.kafka_adapter import collect
from sources.subway_arrival import SubwayArrivalError, fetch_all_subway_arrivals
from sources.subway_location import SubwayLocationError, fetch_subway_location
from sources.subway_schedule import SkippedNonService, pause_until


def moment(clock):
    return datetime.fromisoformat("2026-09-16T" + clock + "+09:00")


class SubwayPolicyTests(unittest.TestCase):
    def test_narrow_window_and_boundaries(self):
        for source in ("subway_location", "subway_arrival"):
            for clock in ("01:20:00", "02:20:00", "03:20:00", "03:29:59", "04:30:00", "05:20:00"):
                self.assertIsNone(pause_until(source, {}, moment(clock)))
            for clock in ("03:30:00", "04:20:00", "04:29:59"):
                self.assertEqual(pause_until(source, {}, moment(clock)), moment("04:30:00"))
        self.assertIsNone(pause_until("subway_alert", {}, moment("04:20:00")))
        self.assertIsNone(pause_until("bus_location", {}, moment("04:20:00")))

    def test_timezone_special_dates_and_disable(self):
        now = moment("04:20:00")
        self.assertEqual(pause_until("subway_location", {}, now.astimezone(timezone.utc)), moment("04:30:00"))
        self.assertIsNone(pause_until("subway_location", {"SUBWAY_SKIP_ENABLED": "false"}, now))
        self.assertIsNone(pause_until("subway_location", {"SUBWAY_SKIP_OVERRIDE_DATES": "2026-09-15,2026-09-16"}, now))
        self.assertIsNotNone(pause_until("subway_location", {"SUBWAY_SKIP_OVERRIDE_DATES": "2026-09-15"}, now))

    def test_invalid_settings_fail_instead_of_silently_skipping(self):
        for extra in (
            {"SUBWAY_SKIP_ENABLED": "yes"},
            {"SUBWAY_SKIP_START": "25:00"},
            {"SUBWAY_SKIP_END": "03:30"},
            {"SUBWAY_SKIP_OVERRIDE_DATES": "2026-02-30"},
        ):
            with self.assertRaises(ConfigError):
                pause_until("subway_location", extra, moment("12:00:00"))

    def test_skip_spends_no_http_budget_and_resumes_at_boundary(self):
        calls = []
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "budget.sqlite3"
            settings = Settings(
                source="subway_location",
                kafka_bootstrap_servers="unused",
                api_keys={"SUBWAY_API_KEY": "fixture-key"},
                extra={"API_BUDGET_PATH": str(path), "SUBWAY_LINES": "2호선"},
            )

            def handler(request):
                calls.append(request)
                return httpx.Response(200, json={"RESULT": {"CODE": "INFO-200"}})

            with httpx.Client(transport=httpx.MockTransport(handler)) as client:
                with patch("sources.kafka_adapter.now_kst", return_value=moment("04:20:00")):
                    with self.assertRaises(SkippedNonService) as result:
                        collect("subway_location", settings, client)
                self.assertEqual(result.exception.wait_seconds, 600)
                self.assertEqual(calls, [])
                self.assertFalse(path.exists())
                with patch("sources.kafka_adapter.now_kst", return_value=moment("04:30:00")):
                    self.assertEqual(collect("subway_location", settings, client), [])
                self.assertEqual(len(calls), 1)
                self.assertEqual(client.event_hooks["request"], [])

    def test_override_collects_during_pause(self):
        with tempfile.TemporaryDirectory() as directory:
            settings = Settings(
                source="subway_arrival",
                kafka_bootstrap_servers="unused",
                api_keys={"SUBWAY_API_KEY": "fixture-key"},
                extra={
                    "API_BUDGET_PATH": str(Path(directory) / "budget.db"),
                    "SUBWAY_SKIP_OVERRIDE_DATES": "2026-09-16",
                },
            )
            calls = []

            def handler(request):
                calls.append(request)
                return httpx.Response(
                    200, json={"errorMessage": {"code": "INFO-200", "total": None}, "realtimeArrivalList": None}
                )

            with httpx.Client(transport=httpx.MockTransport(handler)) as client, patch(
                "sources.kafka_adapter.now_kst", return_value=moment("04:20:00")
            ):
                self.assertEqual(collect("subway_arrival", settings, client), [])
            self.assertEqual(len(calls), 1)

    def test_empty_formats_for_both_apis(self):
        for fetch, list_name in (
            (lambda client: fetch_subway_location("key", "2호선", client=client), "realtimePositionList"),
            (lambda client: fetch_all_subway_arrivals("key", client=client), "realtimeArrivalList"),
        ):
            for response in (
                {"errorMessage": {"code": "INFO-200"}},
                {"errorMessage": {"code": "INFO-200", "total": None}, list_name: None},
                {"RESULT": {"CODE": "INFO-200"}},
            ):
                with self.subTest(list_name=list_name, response=response), httpx.Client(
                    transport=httpx.MockTransport(lambda request, response=response: httpx.Response(200, json=response))
                ) as client:
                    result = fetch(client)
                    self.assertEqual(result["receivedCount"], 0)
                    self.assertEqual(result["response"][list_name], [])

    def test_errors_and_contradictions_are_not_swallowed(self):
        for fetch, list_name, error_type in (
            (
                lambda client: fetch_subway_location("key", "2호선", client=client),
                "realtimePositionList",
                SubwayLocationError,
            ),
            (lambda client: fetch_all_subway_arrivals("key", client=client), "realtimeArrivalList", SubwayArrivalError),
        ):
            cases = [
                {"errorMessage": {"code": "INFO-100"}},
                {"errorMessage": {"code": "INFO-200", "total": 1}},
                {"errorMessage": {"code": "INFO-200", "total": 0}, list_name: [{}]},
                {"errorMessage": {"code": "INFO-000", "total": 0}, list_name: None},
                {"errorMessage": {"code": "INFO-000", "total": -1}, list_name: []},
            ]
            for response in cases:
                with self.subTest(list_name=list_name, response=response), httpx.Client(
                    transport=httpx.MockTransport(lambda request, response=response: httpx.Response(200, json=response))
                ) as client:
                    with self.assertRaises(error_type):
                        fetch(client)

    def test_http_error_does_not_become_empty_or_leak_key(self):
        with httpx.Client(transport=httpx.MockTransport(lambda request: httpx.Response(503))) as client:
            with self.assertRaises(SubwayLocationError) as caught:
                fetch_subway_location("SECRET_API_KEY", "2호선", client=client)
        self.assertNotIn("SECRET_API_KEY", str(caught.exception))

    def test_adapter_preserves_safe_line_and_error_code(self):
        with tempfile.TemporaryDirectory() as directory:
            settings = Settings(
                source="subway_location",
                kafka_bootstrap_servers="unused",
                api_keys={"SUBWAY_API_KEY": "SECRET_API_KEY"},
                extra={"API_BUDGET_PATH": str(Path(directory) / "budget.db"), "SUBWAY_LINES": "2호선"},
            )
            response = {"RESULT": {"CODE": "INFO-100", "MESSAGE": "SECRET_API_KEY"}}
            with httpx.Client(
                transport=httpx.MockTransport(lambda request, response=response: httpx.Response(200, json=response))
            ) as client, patch("sources.kafka_adapter.now_kst", return_value=moment("12:00:00")):
                with self.assertRaisesRegex(RuntimeError, "호선=2호선.*INFO-100") as caught:
                    collect("subway_location", settings, client)
            self.assertNotIn("SECRET_API_KEY", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
