from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from sources.traffic_incident import (  # noqa: E402
    TrafficIncidentError,
    fetch_incident_page,
    fetch_incidents,
    parse_incident_xml,
    save_json,
)

FIRST_PAGE_XML = """<?xml version="1.0" encoding="UTF-8"?>
<AccInfo>
  <list_total_count>3</list_total_count>
  <RESULT><CODE>INFO-000</CODE><MESSAGE>정상 처리되었습니다</MESSAGE></RESULT>
  <row>
    <ACC_ID>202609090001</ACC_ID>
    <OCCR_DATE>20260909</OCCR_DATE>
    <OCCR_TIME>091500</OCCR_TIME>
    <EXP_CLR_DATE>20260909</EXP_CLR_DATE>
    <EXP_CLR_TIME>113000</EXP_CLR_TIME>
    <ACC_TYPE>A01</ACC_TYPE>
    <ACC_DTYPE>A0101</ACC_DTYPE>
    <LINK_ID>1000000100</LINK_ID>
    <GRS80TM_X>198123.4</GRS80TM_X>
    <GRS80TM_Y>451234.5</GRS80TM_Y>
    <ACC_INFO>세종대로 사고로 2개 차로 통제</ACC_INFO>
  </row>
  <row>
    <ACC_ID>202609090002</ACC_ID>
    <OCCR_DATE>20260909</OCCR_DATE>
    <OCCR_TIME>092000</OCCR_TIME>
    <ACC_TYPE>B01</ACC_TYPE>
    <ACC_DTYPE>B0101</ACC_DTYPE>
    <ACC_INFO>행사로 인한 도로 통제</ACC_INFO>
  </row>
</AccInfo>
"""

SECOND_PAGE_XML = """<?xml version="1.0" encoding="UTF-8"?>
<AccInfo>
  <list_total_count>3</list_total_count>
  <RESULT><CODE>INFO-000</CODE><MESSAGE>정상 처리되었습니다</MESSAGE></RESULT>
  <row>
    <ACC_ID>202609090003</ACC_ID>
    <OCCR_DATE>20260909</OCCR_DATE>
    <OCCR_TIME>093000</OCCR_TIME>
    <ACC_INFO>공사로 인한 부분 통제</ACC_INFO>
  </row>
</AccInfo>
"""


class TrafficIncidentTest(unittest.TestCase):
    def test_parse_incident_xml_normalises_fields(self) -> None:
        total, items = parse_incident_xml(FIRST_PAGE_XML)

        self.assertEqual(total, 3)
        self.assertEqual(len(items), 2)
        self.assertEqual(items[0]["incidentId"], "202609090001")
        self.assertEqual(items[0]["linkId"], "1000000100")
        self.assertIn("2개 차로 통제", items[0]["description"])
        self.assertEqual(items[0]["providerFields"]["LINK_ID"], "1000000100")
        self.assertEqual(items[0]["providerFields"]["EXP_CLR_TIME"], "113000")

    def test_parse_incident_xml_rejects_api_error(self) -> None:
        invalid = """
        <RESULT><CODE>INFO-100</CODE><MESSAGE>인증키가 유효하지 않습니다</MESSAGE></RESULT>
        """
        with self.assertRaisesRegex(TrafficIncidentError, "INFO-100"):
            parse_incident_xml(invalid)

    def test_parse_no_data_response(self) -> None:
        empty = """
        <RESULT><CODE>INFO-200</CODE><MESSAGE>해당하는 데이터가 없습니다</MESSAGE></RESULT>
        """
        total, items = parse_incident_xml(empty)
        self.assertEqual(total, 0)
        self.assertEqual(items, [])

    def test_fetch_page_requests_official_xml_endpoint(self) -> None:
        requests: list[httpx.Request] = []

        def handler(request: httpx.Request) -> httpx.Response:
            requests.append(request)
            return httpx.Response(200, text=FIRST_PAGE_XML)

        with httpx.Client(transport=httpx.MockTransport(handler)) as client:
            total, items = fetch_incident_page("secret", 1, 2, client=client)

        self.assertEqual(len(requests), 1)
        self.assertIn("/secret/xml/AccInfo/1/2/", str(requests[0].url))
        self.assertEqual(requests[0].headers["Accept"], "application/xml")
        self.assertEqual(total, 3)
        self.assertEqual(len(items), 2)

    def test_fetch_all_pages_until_short_page(self) -> None:
        responses = [FIRST_PAGE_XML, SECOND_PAGE_XML]
        requests: list[httpx.Request] = []

        def handler(request: httpx.Request) -> httpx.Response:
            requests.append(request)
            return httpx.Response(200, text=responses[len(requests) - 1])

        with httpx.Client(transport=httpx.MockTransport(handler)) as client:
            result = fetch_incidents("secret", page_size=2, client=client)

        self.assertEqual(len(requests), 2)
        self.assertIn("/1/2/", str(requests[0].url))
        self.assertIn("/3/4/", str(requests[1].url))
        self.assertEqual(result["receivedCount"], 3)
        self.assertEqual(result["apiReportedTotalCount"], 3)
        self.assertTrue(result["isComplete"])

    def test_first_page_only_marks_possible_more_data(self) -> None:
        with httpx.Client(
            transport=httpx.MockTransport(lambda request: httpx.Response(200, text=FIRST_PAGE_XML))
        ) as client:
            result = fetch_incidents("secret", page_size=2, fetch_all=False, client=client)

        self.assertEqual(result["requestCount"], 1)
        self.assertTrue(result["hasMore"])
        self.assertFalse(result["isComplete"])

    def test_page_size_cannot_exceed_api_limit(self) -> None:
        with self.assertRaises(ValueError):
            fetch_incidents("secret", page_size=1_001)

    def test_save_json_uses_utf8(self) -> None:
        data = {"items": [{"description": "행사로 인한 도로 통제"}]}
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "nested" / "incidents.json"
            save_json(data, output)
            saved = json.loads(output.read_text(encoding="utf-8"))

        self.assertEqual(saved, data)


if __name__ == "__main__":
    unittest.main()
