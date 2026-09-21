import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from sources import subway_alert


class SubwayAlertTest(unittest.TestCase):
    def test_parse_seoul_open_api_response(self) -> None:
        payload = {
            "getNtceList": {
                "list_total_count": 2,
                "RESULT": {
                    "CODE": "INFO-000",
                    "MESSAGE": "정상 처리되었습니다",
                },
                "row": [{"noftTtl": "알림 1"}, {"noftTtl": "알림 2"}],
            }
        }
        total, items = subway_alert._parse_response(payload)
        self.assertEqual(total, 2)
        self.assertEqual([item["noftTtl"] for item in items], ["알림 1", "알림 2"])

    def test_parse_wrapped_items_response(self) -> None:
        payload = {
            "response": {
                "header": {"resultCode": "00", "resultMsg": "NORMAL SERVICE"},
                "body": {
                    "totalCount": 1,
                    "items": {"item": {"noftTtl": "단일 알림"}},
                },
            }
        }
        total, items = subway_alert._parse_response(payload)
        self.assertEqual(total, 1)
        self.assertEqual(items, [{"noftTtl": "단일 알림"}])

    def test_fetch_recent_requests_only_one_page(self) -> None:
        payload = {
            "getNtceList": {
                "list_total_count": 250,
                "RESULT": {"CODE": "INFO-000", "MESSAGE": "OK"},
                "row": [{"noftTtl": "최근 알림"}],
            }
        }
        with patch.object(subway_alert, "_request_json", return_value=payload) as request:
            result = subway_alert.fetch_alerts("secret", page_size=100)
        request.assert_called_once_with("secret", 1, 100)
        self.assertEqual(result["requestCount"], 1)
        self.assertTrue(result["hasMore"])

    def test_fetch_all_uses_multiple_pages(self) -> None:
        first = {
            "getNtceList": {
                "list_total_count": 3,
                "RESULT": {"CODE": "INFO-000", "MESSAGE": "OK"},
                "row": [{"noftTtl": "1"}, {"noftTtl": "2"}],
            }
        }
        second = {
            "getNtceList": {
                "list_total_count": 3,
                "RESULT": {"CODE": "INFO-000", "MESSAGE": "OK"},
                "row": [{"noftTtl": "3"}],
            }
        }
        with patch.object(subway_alert, "_request_json", side_effect=[first, second]) as request:
            result = subway_alert.fetch_alerts("secret", page_size=2, fetch_all=True)
        self.assertEqual(request.call_args_list[0].args, ("secret", 1, 2))
        self.assertEqual(request.call_args_list[1].args, ("secret", 3, 4))
        self.assertEqual(result["receivedCount"], 3)
        self.assertEqual(result["requestCount"], 2)
        self.assertFalse(result["hasMore"])

    def test_page_size_cannot_exceed_api_limit(self) -> None:
        with self.assertRaises(ValueError):
            subway_alert.fetch_alerts("secret", page_size=1_001)

    def test_save_json_creates_parent_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            output = Path(temp_dir) / "nested" / "alerts.json"
            subway_alert.save_json({"items": []}, output)
            self.assertTrue(output.read_text(encoding="utf-8").startswith("{"))


if __name__ == "__main__":
    unittest.main()
