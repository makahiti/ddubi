"""TOPIS AI 결과의 날짜·경로·오류 정책을 외부 호출 없이 검증한다."""

import sys
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from common.topis_enrichment import ExternalServiceError, InvalidAIResponse, KakaoGeocoder, normalize_notice


class FakeGms:
    model = "fake-model"

    def __init__(self, events=None, error=None):
        self.events, self.error = events, error

    def extract(self, _notice):
        if self.error:
            raise self.error
        return {"events": self.events}

    def rewrite_place_queries(self, _place, _notice):
        return []


class FakeGeocoder:
    def __init__(self, ambiguous=False):
        self.ambiguous = ambiguous

    def search(self, place, extra_queries=None):
        return {
            "geocodeQuery": place,
            "matchedPlaceName": place,
            "kakaoPlaceId": "1",
            "latitude": 37.57,
            "longitude": 126.97,
            "geocodeStatus": "AMBIGUOUS" if self.ambiguous else "MATCHED",
            "geocodeCandidateCount": 2 if self.ambiguous else 1,
            "geocodeMatchBasis": "FIRST_OF_MULTIPLE" if self.ambiguous else "EXACT_PLACE_NAME",
            "usableForBusMapping": not self.ambiguous,
        }


def envelope(text="9월 19일 14시부터 17시까지 세종대로 일부 구간 차량 통제"):
    return {
        "eventId": "source-1",
        "source": "topis_notice",
        "collectedAt": "2026-09-17T10:00:00+09:00",
        "payload": {"providerNoticeId": "6155", "raw": {"noticeId": "6155", "title": "교통 통제", "contentText": text}},
    }


def event(**changes):
    value = {
        "eventType": "ASSEMBLY",
        "impactType": "VEHICLE_CONTROL",
        "eventStatus": "PLANNED",
        "startDateText": "9월 19일",
        "startTimeText": "14시",
        "endDateText": "9월 19일",
        "endTimeText": "17시",
        "roadNames": ["세종대로"],
        "routePoints": [],
        "affectedBusRoutes": [],
        "affectedBusStops": [],
        "evidenceText": "9월 19일 14시부터 17시까지 세종대로 일부 구간 차량 통제",
    }
    value.update(changes)
    return value


class TopisEnrichmentTests(unittest.TestCase):
    @patch("common.topis_enrichment.requests.get")
    def test_geocoder_rejects_single_non_seoul_candidate(self, get):
        response = Mock(status_code=200)
        response.json.return_value = {
            "documents": [
                {
                    "id": "busan",
                    "place_name": "중앙시장",
                    "address_name": "부산광역시 중구 중앙동",
                    "road_address_name": "",
                    "x": "129.03",
                    "y": "35.10",
                }
            ]
        }
        get.return_value = response
        result = KakaoGeocoder("key").search("중앙시장")
        self.assertEqual(result["geocodeStatus"], "NOT_FOUND")
        self.assertEqual(result["geocodeMatchBasis"], "NO_SEOUL_CANDIDATE")
        self.assertFalse(result["usableForBusMapping"])
        self.assertIsNone(result["latitude"])

    @patch("common.topis_enrichment.requests.get")
    def test_geocoder_uses_only_seoul_candidates(self, get):
        response = Mock(status_code=200)
        response.json.return_value = {
            "documents": [
                {
                    "id": "busan",
                    "place_name": "중앙시장",
                    "address_name": "부산광역시 중구",
                    "road_address_name": "",
                    "x": "129.03",
                    "y": "35.10",
                },
                {
                    "id": "seoul",
                    "place_name": "중앙시장",
                    "address_name": "서울특별시 중구",
                    "road_address_name": "",
                    "x": "127.00",
                    "y": "37.56",
                },
            ]
        }
        get.return_value = response
        result = KakaoGeocoder("key").search("중앙시장")
        self.assertEqual(result["kakaoPlaceId"], "seoul")
        self.assertEqual(result["geocodeStatus"], "MATCHED")
        self.assertTrue(result["usableForBusMapping"])

    def test_infers_year_and_uses_road_representative_radius(self):
        row = normalize_notice(envelope(), FakeGms([event()]), FakeGeocoder())[0]
        self.assertEqual(row["startAt"], "2026-09-19T14:00:00+09:00")
        self.assertEqual(row["durationMinutes"], 180)
        self.assertEqual(row["validUntil"], "2026-09-19T23:00:00+09:00")
        self.assertTrue(row["yearInferred"])
        self.assertEqual(row["routePoints"][0]["role"], "AFFECTED_AREA")
        self.assertEqual(row["routePoints"][0]["radiusMeters"], 1000)
        self.assertTrue(row["routeMappingReady"])

    def test_ordered_points_make_ordered_route_legs(self):
        points = [
            {"order": 1, "placeText": "광화문광장", "role": "START"},
            {"order": 2, "placeText": "서울시청", "role": "WAYPOINT"},
            {"order": 3, "placeText": "서울역", "role": "END"},
        ]
        row = normalize_notice(envelope(), FakeGms([event(routePoints=points)]), FakeGeocoder())[0]
        self.assertEqual([leg["legOrder"] for leg in row["routeLegs"]], [1, 2])
        self.assertEqual(row["routeLegs"][1]["fromPlaceText"], "서울시청")
        self.assertEqual(row["mappedRoutePointCount"], 3)

    def test_accepts_iso_date_and_colon_time_from_ai_without_trusting_invented_year(self):
        iso = event(startDateText="2026-09-19", startTimeText="14:00", endDateText="2026-09-19", endTimeText="17:00")
        row = normalize_notice(envelope(), FakeGms([iso]), FakeGeocoder())[0]
        self.assertEqual(row["startAt"], "2026-09-19T14:00:00+09:00")
        self.assertEqual(row["endAt"], "2026-09-19T17:00:00+09:00")
        self.assertTrue(row["yearInferred"])

    def test_ambiguous_geocode_is_kept_but_not_bus_ready(self):
        row = normalize_notice(envelope(), FakeGms([event()]), FakeGeocoder(ambiguous=True))[0]
        self.assertEqual(row["processingStatus"], "PARTIAL")
        self.assertFalse(row["routeMappingReady"])
        self.assertIn("ROUTE_POINT_NOT_EXACT", row["qualityFlags"])

    def test_empty_events_are_auditable_not_relevant_row(self):
        row = normalize_notice(envelope(), FakeGms([]), FakeGeocoder())[0]
        self.assertEqual(row["processingStatus"], "NOT_RELEVANT")
        self.assertEqual(row["eventId"], "6155-0")

    def test_invalid_ai_is_data_but_transient_outage_is_retried(self):
        invalid = normalize_notice(envelope(), FakeGms(error=InvalidAIResponse("bad")), FakeGeocoder())[0]
        self.assertEqual(invalid["processingStatus"], "INVALID_AI_RESPONSE")
        with self.assertRaises(ExternalServiceError):
            normalize_notice(envelope(), FakeGms(error=ExternalServiceError("down")), FakeGeocoder())


if __name__ == "__main__":
    unittest.main()
