from __future__ import annotations

import sys
import unittest
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from sources.bus_arrival import (  # noqa: E402
    BusArrivalError,
    fetch_all_bus_arrivals,
    fetch_bus_arrival,
    load_bus_route_ids,
)


SAMPLE_JSON = {
    "msgHeader": {"headerCd": "0", "headerMsg": "success"},
    "msgBody": {
        "itemList": [
            {"stId": "1001", "stNm": "first stop", "traTime1": "120"},
            {"stId": "1002", "stNm": "second stop", "traTime1": "240"},
        ]
    },
}


class BusArrivalTest(unittest.TestCase):
    def test_default_route_id_file_contains_all_routes(self) -> None:
        route_ids = load_bus_route_ids()

        self.assertEqual(len(route_ids), 723)
        self.assertEqual(len(set(route_ids)), 723)
        self.assertTrue(all(len(route_id) == 9 and route_id.isdigit() for route_id in route_ids))

    def test_fetch_requests_and_returns_json(self) -> None:
        requests: list[httpx.Request] = []

        def handler(request: httpx.Request) -> httpx.Response:
            requests.append(request)
            return httpx.Response(200, json=SAMPLE_JSON)

        with httpx.Client(transport=httpx.MockTransport(handler)) as client:
            result = fetch_bus_arrival("encoded%2Bkey%3D", "100100118", client=client)

        self.assertEqual(len(requests), 1)
        self.assertEqual(requests[0].url.params["serviceKey"], "encoded+key=")
        self.assertEqual(requests[0].url.params["busRouteId"], "100100118")
        self.assertEqual(requests[0].url.params["resultType"], "json")
        self.assertEqual(requests[0].headers["Accept"], "application/json")
        self.assertEqual(result["msgHeader"]["headerMsg"], "success")

    def test_fetch_all_requests_each_route_once(self) -> None:
        requests: list[httpx.Request] = []

        def handler(request: httpx.Request) -> httpx.Response:
            requests.append(request)
            return httpx.Response(200, json=SAMPLE_JSON)

        route_ids = ["100100118", "100100124"]
        with httpx.Client(transport=httpx.MockTransport(handler)) as client:
            result = fetch_all_bus_arrivals(
                "key",
                route_ids,
                client=client,
                requests_per_second=1_000_000,
            )

        self.assertEqual([request.url.params["busRouteId"] for request in requests], route_ids)
        self.assertEqual(result["routeCount"], 2)
        self.assertEqual(set(result["routeResults"]), set(route_ids))

    def test_fetch_rejects_non_json_response(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(200, text="<ServiceResult />")

        with httpx.Client(transport=httpx.MockTransport(handler)) as client:
            with self.assertRaisesRegex(BusArrivalError, "올바른 JSON"):
                fetch_bus_arrival("key", "100100118", client=client)


if __name__ == "__main__":
    unittest.main()
