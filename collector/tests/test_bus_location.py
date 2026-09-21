from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from sources.bus_location import (  # noqa: E402
    BusLocationError,
    fetch_all_bus_locations,
    fetch_bus_location,
    load_bus_route_ids,
    save_json,
)

SAMPLE_JSON = {
    "msgHeader": {"headerCd": "0", "headerMsg": "정상적으로 처리되었습니다."},
    "msgBody": {
        "itemList": [
            {
                "vehId": "100000001",
                "plainNo": "서울70사0001",
                "gpsX": "126.9780",
                "gpsY": "37.5665",
            }
        ]
    },
}


class BusLocationTest(unittest.TestCase):
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
            result = fetch_bus_location("encoded%2Bkey%3D", "100100118", client=client)

        self.assertEqual(len(requests), 1)
        self.assertEqual(requests[0].url.params["serviceKey"], "encoded+key=")
        self.assertEqual(requests[0].url.params["busRouteId"], "100100118")
        self.assertEqual(requests[0].url.params["resultType"], "json")
        self.assertEqual(requests[0].headers["Accept"], "application/json")
        self.assertEqual(result["msgBody"]["itemList"][0]["vehId"], "100000001")

    def test_fetch_all_requests_each_route_once_and_counts_vehicles(self) -> None:
        requests: list[httpx.Request] = []

        def handler(request: httpx.Request) -> httpx.Response:
            requests.append(request)
            return httpx.Response(200, json=SAMPLE_JSON)

        route_ids = ["100100118", "100100124"]
        with httpx.Client(transport=httpx.MockTransport(handler)) as client:
            result = fetch_all_bus_locations(
                "key",
                route_ids,
                client=client,
                requests_per_second=25,
            )

        self.assertEqual([request.url.params["busRouteId"] for request in requests], route_ids)
        self.assertEqual(result["routeCount"], 2)
        self.assertEqual(result["vehicleCount"], 2)
        self.assertEqual(set(result["routeResults"]), set(route_ids))

    def test_fetch_rejects_non_json_response(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(200, text="<ServiceResult />")

        with httpx.Client(transport=httpx.MockTransport(handler)) as client:
            with self.assertRaisesRegex(BusLocationError, "올바른 JSON"):
                fetch_bus_location("key", "100100118", client=client)

    def test_fetch_rejects_api_error_inside_http_200(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(
                200,
                json={"msgHeader": {"headerCd": "22", "headerMsg": "일일 한도 초과"}},
            )

        with httpx.Client(transport=httpx.MockTransport(handler)) as client:
            with self.assertRaisesRegex(BusLocationError, r"오류\(22\)"):
                fetch_bus_location("key", "100100118", client=client)

    def test_save_json_uses_utf8_and_preserves_korean(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "bus_location.json"
            save_json(SAMPLE_JSON, path)

            saved = json.loads(path.read_text(encoding="utf-8"))

        self.assertEqual(saved, SAMPLE_JSON)


if __name__ == "__main__":
    unittest.main()
