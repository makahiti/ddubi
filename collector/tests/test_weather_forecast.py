import unittest
from datetime import datetime
from unittest.mock import patch

import httpx

from config import Settings
from sources import weather_forecast
from sources.base import KST


KMA_FORECAST_OK = {
    "response": {
        "header": {"resultCode": "00", "resultMsg": "NORMAL_SERVICE"},
        "body": {"items": {"item": [
            {"fcstDate": "20260903", "fcstTime": "1200", "category": "TMP", "fcstValue": "29"},
            {"fcstDate": "20260903", "fcstTime": "1200", "category": "POP", "fcstValue": "30"},
            {"fcstDate": "20260903", "fcstTime": "1300", "category": "TMP", "fcstValue": "30"},
        ]}},
    }
}


class WeatherForecastTest(unittest.TestCase):
    def test_latest_issue_datetime_respects_publication_delay(self):
        cases = [
            (datetime(2026, 9, 3, 11, 14, tzinfo=KST), datetime(2026, 9, 3, 8, 0, tzinfo=KST)),
            (datetime(2026, 9, 3, 11, 15, tzinfo=KST), datetime(2026, 9, 3, 11, 0, tzinfo=KST)),
            (datetime(2026, 9, 3, 1, 0, tzinfo=KST), datetime(2026, 9, 2, 23, 0, tzinfo=KST)),
        ]
        for now, expected in cases:
            with self.subTest(now=now):
                self.assertEqual(weather_forecast.latest_issue_datetime(now), expected)

    def test_group_forecasts_keeps_valid_time_separate(self):
        grouped = weather_forecast.group_forecasts(
            KMA_FORECAST_OK["response"]["body"]["items"]["item"]
        )
        self.assertEqual(grouped[datetime(2026, 9, 3, 12, 0, tzinfo=KST)], {"TMP": "29", "POP": "30"})
        self.assertEqual(grouped[datetime(2026, 9, 3, 13, 0, tzinfo=KST)], {"TMP": "30"})

    def test_fetch_preserves_issued_at_and_valid_at(self):
        calls = []

        def handler(request: httpx.Request) -> httpx.Response:
            calls.append(dict(request.url.params))
            return httpx.Response(200, json=KMA_FORECAST_OK)

        settings = Settings(
            source="weather_forecast", kafka_bootstrap_servers="kafka:9092",
            api_keys={"DATA_GO_KR_API_KEY": "test-key"},
            extra={"WEATHER_GRID_POINTS": "종로구:60:127,중구:60:127"},
        )
        with patch.object(weather_forecast, "now_kst", return_value=datetime(2026, 9, 3, 11, 20, tzinfo=KST)):
            with httpx.Client(transport=httpx.MockTransport(handler)) as client:
                records = weather_forecast.fetch(settings, client)

        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0]["base_date"], "20260903")
        self.assertEqual(calls[0]["base_time"], "1100")
        self.assertEqual(len(records), 2)
        self.assertEqual(records[0].occurred_at, datetime(2026, 9, 3, 11, 0, tzinfo=KST))
        self.assertEqual(records[0].payload["issuedAt"], "2026-09-03T11:00:00+09:00")
        self.assertEqual(records[0].payload["validAt"], "2026-09-03T12:00:00+09:00")
        self.assertEqual(records[0].payload["districts"], ["종로구", "중구"])
        self.assertEqual(records[0].payload["forecasts"], {"TMP": "29", "POP": "30"})

    def test_fetch_does_not_leak_key_in_error(self):
        settings = Settings(
            source="weather_forecast", kafka_bootstrap_servers="kafka:9092",
            api_keys={"KMA_API_KEY": "secret-key"},
            extra={"WEATHER_GRID_POINTS": "종로구:60:127"},
        )
        transport = httpx.MockTransport(lambda _: httpx.Response(403, text="approval required"))
        with httpx.Client(transport=transport) as client:
            with self.assertRaisesRegex(RuntimeError, "전부 실패") as caught:
                weather_forecast.fetch(settings, client)
        self.assertNotIn("secret-key", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
