import gzip
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

from pyproj import Transformer

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from common.traffic_incident_enrichment import VerifiedLinkMaster, normalize_incident, transform_grs80


class TrafficIncidentEnrichmentTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.geometry = [[127.0228, 37.5755], [127.0237, 37.5757]]
        self.master_data = {
            "schemaVersion": 1,
            "namespace": "SEOUL_ACCINFO_LINK",
            "idSystem": "SEOUL_TOPIS_ACCINFO_LINK_ID",
            "sourceCrs": "EPSG:4326",
            "accInfoLinkIdVerified": True,
            "reviewStatus": "APPROVED",
            "sourceDataset": "OA-13310",
            "version": "official-test-v1",
            "evidenceRef": "fixture-only",
            "validFrom": "2026-01-01T00:00:00+09:00",
            "validUntil": "2027-01-01T00:00:00+09:00",
            "links": [
                {
                    "linkId": "1000000100",
                    "roadName": "세종대로",
                    "districtName": "종로구",
                    "startNodeId": "n1",
                    "endNodeId": "n2",
                    "geometry": self.geometry,
                }
            ],
        }
        raw = gzip.compress(json.dumps(self.master_data, ensure_ascii=False).encode())
        self.path = self.root / "master.json.gz"
        self.path.write_bytes(raw)
        self.master = VerifiedLinkMaster.load(self.path, hashlib.sha256(raw).hexdigest())

    def event(
        self,
        link="1000000100",
        x="202051.8241",
        y="452892.967",
        description="세종대로 종로 방향 2개 차로 통제",
        expected=True,
    ):
        raw = {
            "incidentId": "i1",
            "occurredDate": "20260917",
            "occurredTime": "090000",
            "incidentTypeCode": "A01",
            "incidentDetailTypeCode": "A0101",
            "linkId": link,
            "grs80tmX": x,
            "grs80tmY": y,
            "description": description,
        }
        if expected:
            raw.update(expectedClearDate="20260917", expectedClearTime="110000")
        return {
            "eventId": "e1",
            "eventType": "traffic.incident",
            "source": "traffic_incident",
            "collectedAt": "2026-09-17T10:00:00+09:00",
            "payload": {
                "providerIncidentId": "i1",
                "providerLinkNamespace": "SEOUL_ACCINFO_LINK",
                "providerLinkId": link,
                "contentHash": "hash",
                "raw": raw,
            },
        }

    def projected(self, longitude, latitude):
        return Transformer.from_crs(4326, 5181, always_xy=True).transform(longitude, latitude)

    def test_epsg_5181_matches_official_topis_node_sample(self):
        lat, lon, status = transform_grs80("202051.8241", "452892.967")
        self.assertEqual(status, "TRANSFORMED")
        self.assertAlmostEqual(lat, 37.5755809, places=5)
        self.assertAlmostEqual(lon, 127.0232274, places=5)

    def test_exact_link_and_consistent_coordinate_are_direct(self):
        row = normalize_incident(self.event(), self.master)
        self.assertEqual(row["locationMatchType"], "DIRECT")
        self.assertEqual(row["matchedLinkId"], "1000000100")
        self.assertEqual(row["coordinateStatus"], "MATCHED_LINK")
        self.assertEqual(row["processingStatus"], "VALID")
        self.assertEqual(row["descriptionRoadName"], "세종대로")
        self.assertEqual(row["descriptionLaneControl"], "2개 차로 통제")

    def test_link_coordinate_conflict_is_not_auto_mapped(self):
        x, y = self.projected(127.20, 37.70)
        row = normalize_incident(self.event(x=x, y=y), self.master)
        self.assertEqual(row["locationMatchType"], "CONFLICT")
        self.assertIsNone(row["matchedLinkId"])
        self.assertEqual(row["coordinateStatus"], "LINK_CONFLICT")
        self.assertIn("LINK_COORDINATE_CONFLICT", row["qualityFlags"])

    def test_missing_link_can_use_unique_nearby_geometry(self):
        event = self.event(link=None)
        event["payload"]["providerLinkId"] = None
        row = normalize_incident(event, self.master)
        self.assertEqual(row["locationMatchType"], "NEARBY")
        self.assertEqual(row["matchedLinkId"], "1000000100")

    def test_region_text_is_context_but_road_name_never_overrides_location(self):
        regional = self.event(link=None, x=None, y=None, description="종로구 일대 세종대로 통제")
        regional["payload"]["providerLinkId"] = None
        row = normalize_incident(regional, self.master)
        self.assertEqual(row["locationMatchType"], "REGIONAL_CONTEXT")
        self.assertIsNone(row["matchedLinkId"])
        x, y = self.projected(127.20, 37.70)
        far = self.event(link=None, x=x, y=y, description="세종대로 통제")
        far["payload"]["providerLinkId"] = None
        row = normalize_incident(far, self.master)
        self.assertEqual(row["locationMatchType"], "UNMATCHED")
        self.assertIsNone(row["matchedLinkId"])

    def test_missing_expected_clear_remains_active_observed(self):
        row = normalize_incident(self.event(expected=False), self.master)
        self.assertIsNone(row["expectedClearAt"])
        self.assertEqual(row["lifecycleStatus"], "ACTIVE_OBSERVED")
        self.assertIn("expectedClearAt", row["missingFields"])

    def test_unavailable_master_preserves_row_without_mapping(self):
        row = normalize_incident(self.event(), VerifiedLinkMaster.unavailable())
        self.assertEqual(row["linkMasterStatus"], "UNAVAILABLE")
        self.assertEqual(row["locationMatchType"], "UNMATCHED")
        self.assertEqual(row["processingStatus"], "PARTIAL")

    def test_candidate_master_is_rejected(self):
        data = dict(self.master_data, reviewStatus="REVIEW_REQUIRED", accInfoLinkIdVerified=False)
        raw = json.dumps(data).encode()
        path = self.root / "candidate.json"
        path.write_bytes(raw)
        with self.assertRaisesRegex(ValueError, "not an approved"):
            VerifiedLinkMaster.load(path, hashlib.sha256(raw).hexdigest())

    def test_master_outside_validity_period_is_not_used(self):
        event = self.event()
        event["collectedAt"] = "2028-09-17T10:00:00+09:00"
        row = normalize_incident(event, self.master)
        self.assertEqual(row["linkMasterStatus"], "VERSION_OUT_OF_RANGE")
        self.assertEqual(row["locationMatchType"], "UNMATCHED")
        self.assertIsNone(row["matchedLinkId"])

    def test_master_requires_timezone_aware_validity_interval(self):
        data = dict(self.master_data, validFrom="2026-01-01T00:00:00")
        raw = json.dumps(data).encode()
        path = self.root / "invalid-validity.json"
        path.write_bytes(raw)
        with self.assertRaisesRegex(ValueError, "validity interval"):
            VerifiedLinkMaster.load(path, hashlib.sha256(raw).hexdigest())


if __name__ == "__main__":
    unittest.main()
