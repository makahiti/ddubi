from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from sources.subway_arrival import (  # noqa: E402
    SubwayArrivalError,
    fetch_all_subway_arrivals,
    save_json,
)

SAMPLE_RESPONSE = {
    "errorMessage": {
        "status": 200,
        "code": "INFO-000",
        "message": "정상 처리되었습니다.",
        "total": 2,
    },
    "realtimeArrivalList": [
        {"statnNm": "서울", "subwayId": "1001", "btrainNo": "0123"},
        {"statnNm": "강남", "subwayId": "1002", "btrainNo": "2250"},
    ],
}


class SubwayArrivalTest(unittest.TestCase):
    def test_fetch_requests_json_all_and_returns_complete_result(self) -> None:
        requests: list[httpx.Request] = []

        def handler(request: httpx.Request) -> httpx.Response:
            requests.append(request)
            return httpx.Response(200, json=SAMPLE_RESPONSE)

        with httpx.Client(transport=httpx.MockTransport(handler)) as client:
            result = fetch_all_subway_arrivals("secret-key", client=client)

        self.assertEqual(len(requests), 1)
        self.assertTrue(str(requests[0].url).endswith("/json/realtimeStationArrival/ALL/"))
        self.assertEqual(requests[0].headers["Accept"], "application/json")
        self.assertEqual(result["totalCount"], 2)
        self.assertEqual(result["receivedCount"], 2)
        self.assertTrue(result["isComplete"])
        self.assertEqual(result["response"], SAMPLE_RESPONSE)

    def test_fetch_rejects_partial_all_response(self) -> None:
        partial = {
            **SAMPLE_RESPONSE,
            "errorMessage": {**SAMPLE_RESPONSE["errorMessage"], "total": 3},
        }

        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(200, json=partial)

        with httpx.Client(transport=httpx.MockTransport(handler)) as client:
            with self.assertRaisesRegex(SubwayArrivalError, "전체 3건 중 2건"):
                fetch_all_subway_arrivals("key", client=client)

    def test_fetch_rejects_api_error_inside_http_200(self) -> None:
        error_response = {
            "errorMessage": {
                "status": 200,
                "code": "ERROR-300",
                "message": "필수 값이 누락되어 있습니다.",
                "total": 0,
            }
        }

        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(200, json=error_response)

        with httpx.Client(transport=httpx.MockTransport(handler)) as client:
            with self.assertRaisesRegex(SubwayArrivalError, r"오류\(ERROR-300\)"):
                fetch_all_subway_arrivals("key", client=client)

    def test_fetch_rejects_non_json_response(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(200, text="<realtimeStationArrival />")

        with httpx.Client(transport=httpx.MockTransport(handler)) as client:
            with self.assertRaisesRegex(SubwayArrivalError, "올바른 JSON"):
                fetch_all_subway_arrivals("key", client=client)

    def test_save_json_uses_utf8(self) -> None:
        data = {
            "collectedAt": "2026-09-08T00:00:00+00:00",
            "totalCount": 2,
            "receivedCount": 2,
            "isComplete": True,
            "response": SAMPLE_RESPONSE,
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "subway_arrival.json"
            save_json(data, path)
            saved = json.loads(path.read_text(encoding="utf-8"))

        self.assertEqual(saved, data)


if __name__ == "__main__":
    unittest.main()
