from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from sources.city_realtime import (  # noqa: E402
    CityRealtimeError,
    fetch_city_area,
    fetch_city_areas,
    load_city_areas,
    save_json,
    select_city_areas,
)

AREA = {
    "category": "고궁·문화유산",
    "areaCode": "POI009",
    "areaName": "광화문·덕수궁",
    "englishName": "Gwanghwamun & Deoksugung Palace",
}
SAMPLE_RESPONSE = {
    "list_total_count": 1,
    "RESULT": {
        "RESULT.CODE": "INFO-000",
        "RESULT.MESSAGE": "정상 처리되었습니다.",
    },
    "CITYDATA": {
        "AREA_NM": "광화문·덕수궁",
        "AREA_CD": "POI009",
        "LIVE_PPLTN_STTS": [{"AREA_PPLTN_MIN": "38000"}],
        "ROAD_TRAFFIC_STTS": {"AVG_ROAD_DATA": {"ROAD_TRAFFIC_SPD": "22.5"}},
        "PRK_STTS": [],
    },
}


class CityRealtimeTest(unittest.TestCase):
    def test_default_area_file_contains_121_official_areas(self) -> None:
        areas = load_city_areas()

        self.assertEqual(len(areas), 121)
        self.assertEqual(len({area["areaCode"] for area in areas}), 121)
        self.assertIn("POI009", {area["areaCode"] for area in areas})
        self.assertIn("숭례문", {area["areaName"] for area in areas})

    def test_select_area_accepts_code_or_name_and_removes_duplicates(self) -> None:
        selected = select_city_areas([AREA], ["POI009", "광화문·덕수궁"])

        self.assertEqual(selected, [AREA])

    def test_fetch_requests_json_for_one_area(self) -> None:
        requests: list[httpx.Request] = []

        def handler(request: httpx.Request) -> httpx.Response:
            requests.append(request)
            return httpx.Response(200, json=SAMPLE_RESPONSE)

        with httpx.Client(transport=httpx.MockTransport(handler)) as client:
            result = fetch_city_area("secret-key", AREA, client=client)

        self.assertEqual(len(requests), 1)
        self.assertIn("/json/citydata/1/5/POI009", str(requests[0].url))
        self.assertEqual(requests[0].headers["Accept"], "application/json")
        self.assertEqual(result["areaCode"], "POI009")
        self.assertEqual(result["response"], SAMPLE_RESPONSE)

    def test_fetch_all_requests_each_area_once(self) -> None:
        second_area = {**AREA, "areaCode": "POI010", "areaName": "보신각"}
        responses = {
            "POI009": SAMPLE_RESPONSE,
            "POI010": {
                **SAMPLE_RESPONSE,
                "CITYDATA": {**SAMPLE_RESPONSE["CITYDATA"], "AREA_CD": "POI010", "AREA_NM": "보신각"},
            },
        }
        requests: list[httpx.Request] = []

        def handler(request: httpx.Request) -> httpx.Response:
            requests.append(request)
            code = request.url.path.rsplit("/", 1)[-1]
            return httpx.Response(200, json=responses[code])

        with httpx.Client(transport=httpx.MockTransport(handler)) as client:
            result = fetch_city_areas("key", [AREA, second_area], client=client, requests_per_second=5)

        self.assertEqual(len(requests), 2)
        self.assertEqual(result["receivedAreaCount"], 2)
        self.assertTrue(result["isComplete"])
        self.assertEqual(set(result["areaResults"]), {"POI009", "POI010"})

    def test_fetch_rejects_mismatched_area(self) -> None:
        invalid = {
            **SAMPLE_RESPONSE,
            "CITYDATA": {**SAMPLE_RESPONSE["CITYDATA"], "AREA_CD": "POI010"},
        }

        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(200, json=invalid)

        with httpx.Client(transport=httpx.MockTransport(handler)) as client:
            with self.assertRaisesRegex(CityRealtimeError, "응답 장소가 다릅니다"):
                fetch_city_area("key", AREA, client=client)

    def test_fetch_rejects_api_error(self) -> None:
        invalid = {
            "RESULT": {
                "RESULT.CODE": "ERROR-301",
                "RESULT.MESSAGE": "인증키가 유효하지 않습니다.",
            }
        }

        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(200, json=invalid)

        with httpx.Client(transport=httpx.MockTransport(handler)) as client:
            with self.assertRaisesRegex(CityRealtimeError, "ERROR-301"):
                fetch_city_area("key", AREA, client=client)

    def test_save_json_uses_utf8(self) -> None:
        data = {
            "collectedAt": "2026-09-08T00:00:00+00:00",
            "requestedAreaCount": 1,
            "receivedAreaCount": 1,
            "isComplete": True,
            "areaResults": {"POI009": {"areaName": "광화문·덕수궁"}},
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "city_realtime.json"
            save_json(data, path)
            saved = json.loads(path.read_text(encoding="utf-8"))

        self.assertEqual(saved, data)


if __name__ == "__main__":
    unittest.main()
