from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from sources.topis_notice import (  # noqa: E402
    TopisNoticeError,
    fetch_notice_page,
    fetch_notices,
    html_to_text,
    parse_notice_xml,
    save_json,
)


FIRST_PAGE_XML = """<?xml version="1.0" encoding="UTF-8"?>
<NoticeList>
  <list_total_count>3</list_total_count>
  <RESULT><CODE>INFO-000</CODE><MESSAGE>정상 처리되었습니다</MESSAGE></RESULT>
  <row>
    <bdwr_seq>6155</bdwr_seq>
    <bdwr_rtng_cd>0</bdwr_rtng_cd>
    <bdwr_rtng_nm>일반</bdwr_rtng_nm>
    <bdwr_div_cd>0201</bdwr_div_cd>
    <bdwr_div_nm>통제안내</bdwr_div_nm>
    <bdwr_ttl_nm>여의도 집회에 따른 도로 통제 안내</bdwr_ttl_nm>
    <bdwr_cts>&lt;p&gt;집회일시 : 13:00 ~ 16:00&lt;/p&gt;&lt;p&gt;정류장 폐쇄&lt;/p&gt;</bdwr_cts>
    <update_date>2026-09-09 09:38:07</update_date>
    <create_date>2026-09-09 09:38:07</create_date>
    <url>https://topis.seoul.go.kr/notice/6155</url>
  </row>
  <row>
    <bdwr_seq>6154</bdwr_seq>
    <bdwr_div_cd>0202</bdwr_div_cd>
    <bdwr_div_nm>버스안내</bdwr_div_nm>
    <bdwr_ttl_nm>버스 임시우회 안내</bdwr_ttl_nm>
    <bdwr_cts>&lt;p&gt;대상노선 : 162, 260&lt;/p&gt;</bdwr_cts>
    <update_date>2026-09-09 09:31:15</update_date>
    <create_date>2026-09-09 09:11:59</create_date>
    <url>https://topis.seoul.go.kr/notice/6154</url>
  </row>
</NoticeList>
"""

SECOND_PAGE_XML = """<?xml version="1.0" encoding="UTF-8"?>
<NoticeList>
  <list_total_count>3</list_total_count>
  <RESULT><CODE>INFO-000</CODE><MESSAGE>정상 처리되었습니다</MESSAGE></RESULT>
  <row>
    <bdwr_seq>6153</bdwr_seq>
    <bdwr_div_nm>기타안내</bdwr_div_nm>
    <bdwr_ttl_nm>임시개통 안내</bdwr_ttl_nm>
    <bdwr_cts>&lt;p&gt;23:00 임시개통&lt;/p&gt;</bdwr_cts>
  </row>
</NoticeList>
"""


class TopisNoticeTest(unittest.TestCase):
    def test_html_to_text_preserves_readable_lines(self) -> None:
        text = html_to_text("<p>대상노선 : 162, 260</p><p>통제구간&nbsp;안내</p>")
        self.assertEqual(text, "대상노선 : 162, 260\n통제구간 안내")

    def test_parse_notice_xml_normalises_fields(self) -> None:
        total, items = parse_notice_xml(FIRST_PAGE_XML)

        self.assertEqual(total, 3)
        self.assertEqual(len(items), 2)
        self.assertEqual(items[0]["noticeId"], "6155")
        self.assertEqual(items[0]["categoryName"], "통제안내")
        self.assertIn("정류장 폐쇄", items[0]["contentText"])
        self.assertIn("<p>", items[0]["contentHtml"])

    def test_parse_notice_xml_rejects_api_error(self) -> None:
        invalid = """
        <RESULT><CODE>INFO-100</CODE><MESSAGE>인증키가 유효하지 않습니다</MESSAGE></RESULT>
        """
        with self.assertRaisesRegex(TopisNoticeError, "INFO-100"):
            parse_notice_xml(invalid)

    def test_fetch_page_requests_official_xml_endpoint(self) -> None:
        requests: list[httpx.Request] = []

        def handler(request: httpx.Request) -> httpx.Response:
            requests.append(request)
            return httpx.Response(200, text=FIRST_PAGE_XML)

        with httpx.Client(transport=httpx.MockTransport(handler)) as client:
            total, items = fetch_notice_page("secret", 1, 2, client=client)

        self.assertEqual(len(requests), 1)
        self.assertIn("/secret/xml/NoticeList/1/2/", str(requests[0].url))
        self.assertEqual(requests[0].headers["Accept"], "application/xml")
        self.assertEqual(total, 3)
        self.assertEqual(len(items), 2)

    def test_fetch_all_pages_until_total_count(self) -> None:
        responses = [FIRST_PAGE_XML, SECOND_PAGE_XML]
        requests: list[httpx.Request] = []

        def handler(request: httpx.Request) -> httpx.Response:
            requests.append(request)
            return httpx.Response(200, text=responses[len(requests) - 1])

        with httpx.Client(transport=httpx.MockTransport(handler)) as client:
            result = fetch_notices(
                "secret", page_size=2, fetch_all=True, client=client
            )

        self.assertEqual(len(requests), 2)
        self.assertIn("/1/2/", str(requests[0].url))
        self.assertIn("/3/4/", str(requests[1].url))
        self.assertEqual(result["receivedCount"], 3)
        self.assertEqual(result["apiReportedCount"], 3)
        self.assertTrue(result["isComplete"])
        self.assertFalse(result["hasMore"])

    def test_fetch_all_does_not_trust_page_sized_reported_count(self) -> None:
        second_page = SECOND_PAGE_XML.replace(
            "<list_total_count>3</list_total_count>",
            "<list_total_count>1</list_total_count>",
        )
        page_sized_count = FIRST_PAGE_XML.replace(
            "<list_total_count>3</list_total_count>",
            "<list_total_count>2</list_total_count>",
        )
        responses = [page_sized_count, second_page]
        requests: list[httpx.Request] = []

        def handler(request: httpx.Request) -> httpx.Response:
            requests.append(request)
            return httpx.Response(200, text=responses[len(requests) - 1])

        with httpx.Client(transport=httpx.MockTransport(handler)) as client:
            result = fetch_notices(
                "secret", page_size=2, fetch_all=True, client=client
            )

        self.assertEqual(len(requests), 2)
        self.assertEqual(result["receivedCount"], 3)

    def test_page_size_cannot_exceed_api_limit(self) -> None:
        with self.assertRaises(ValueError):
            fetch_notices("secret", page_size=1_001)

    def test_save_json_uses_utf8(self) -> None:
        data = {"items": [{"title": "버스 임시우회 안내"}]}
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "nested" / "notices.json"
            save_json(data, output)
            saved = json.loads(output.read_text(encoding="utf-8"))

        self.assertEqual(saved, data)


if __name__ == "__main__":
    unittest.main()
