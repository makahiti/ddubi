from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from sources.subway_location import (  # noqa: E402
    SubwayLocationError,
    fetch_all_subway_locations,
    fetch_subway_location,
    load_subway_lines,
    save_json,
)

SAMPLE_RESPONSE = {
    "errorMessage": {
        "status": 200,
        "code": "INFO-000",
        "message": "정상 처리되었습니다.",
        "total": 1,
    },
    "realtimePositionList": [
        {
            "subwayId": "1002",
            "subwayNm": "2호선",
            "statnId": "1002000222",
            "statnNm": "강남",
            "trainNo": "2250",
            "trainSttus": "2",
        }
    ],
}


class SubwayLocationTest(unittest.TestCase):
    def test_default_line_file_contains_official_lines(self) -> None:
        lines = load_subway_lines()

        self.assertEqual(len(lines), 16)
        self.assertEqual(len(set(lines)), 16)
        self.assertIn("1호선", lines)
        self.assertIn("GTX-A", lines)
        self.assertIn("우이신설선", lines)

    def test_fetch_requests_json_positions_for_line(self) -> None:
        requests: list[httpx.Request] = []

        def handler(request: httpx.Request) -> httpx.Response:
            requests.append(request)
            return httpx.Response(200, json=SAMPLE_RESPONSE)

        with httpx.Client(transport=httpx.MockTransport(handler)) as client:
            result = fetch_subway_location("secret-key", "2호선", client=client)

        self.assertEqual(len(requests), 1)
        self.assertIn("/json/realtimePosition/0/1000/", str(requests[0].url))
        self.assertEqual(requests[0].headers["Accept"], "application/json")
        self.assertEqual(result["receivedCount"], 1)
        self.assertTrue(result["isComplete"])

    def test_fetch_all_requests_each_line_once(self) -> None:
        requests: list[httpx.Request] = []

        def handler(request: httpx.Request) -> httpx.Response:
            requests.append(request)
            return httpx.Response(200, json=SAMPLE_RESPONSE)

        lines = ["1호선", "2호선"]
        with httpx.Client(transport=httpx.MockTransport(handler)) as client:
            result = fetch_all_subway_locations(
                "key",
                lines,
                client=client,
                requests_per_second=5,
            )

        self.assertEqual(len(requests), 2)
        self.assertEqual(result["lineCount"], 2)
        self.assertEqual(result["positionCount"], 2)
        self.assertEqual(result["emptyLines"], [])

    def test_fetch_rejects_partial_response(self) -> None:
        partial = {
            **SAMPLE_RESPONSE,
            "errorMessage": {**SAMPLE_RESPONSE["errorMessage"], "total": 2},
        }

        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(200, json=partial)

        with httpx.Client(transport=httpx.MockTransport(handler)) as client:
            with self.assertRaisesRegex(SubwayLocationError, "전체 2건 중 1건"):
                fetch_subway_location("key", "2호선", client=client)

    def test_fetch_accepts_empty_info_200(self) -> None:
        empty = {
            "errorMessage": {
                "status": 200,
                "code": "INFO-200",
                "message": "해당하는 데이터가 없습니다.",
                "total": 0,
            }
        }

        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(200, json=empty)

        with httpx.Client(transport=httpx.MockTransport(handler)) as client:
            result = fetch_subway_location("key", "GTX-A", client=client)

        self.assertEqual(result["receivedCount"], 0)
        self.assertTrue(result["isComplete"])

    def test_save_json_uses_utf8(self) -> None:
        data = {
            "collectedAt": "2026-09-08T00:00:00+00:00",
            "lineCount": 1,
            "positionCount": 1,
            "emptyLines": [],
            "lineResults": {"2호선": {"response": SAMPLE_RESPONSE}},
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "subway_location.json"
            save_json(data, path)
            saved = json.loads(path.read_text(encoding="utf-8"))

        self.assertEqual(saved, data)


if __name__ == "__main__":
    unittest.main()
