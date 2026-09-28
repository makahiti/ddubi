"""역방향 사고 링크 인덱스의 누락·중복 방지 검증."""

import json
import tempfile
import unittest
from pathlib import Path

from preview_reverse_index import build_index, match_snapshot, preview, segment_preview_features


def edge(segment, order, links=None, missing=None):
    return {
        "namespace": "SEOUL_BUS",
        "routeId": "r",
        "fromOrder": order,
        "segmentId": segment,
        "topisServiceLinkIds": links or [],
        "unmappedBisLinkIds": missing or [],
    }


class ReverseIndexTest(unittest.TestCase):
    def setUp(self):
        self.candidates = [
            edge("safe", 1, ["L1", "L2"]),
            edge("safe", 2, ["L1", "L2"]),
            edge("ambiguous", 3, ["L1"]),
            edge("ambiguous", 4, ["L2"]),
            edge("partial", 5, ["L1"]),
            edge("partial", 6, ["L1"], ["BIS-MISSING"]),
        ]
        self.rejected = [edge("unmapped", 7)]
        self.audit = {
            "seoulEdgeCount": 7,
            "counts": {"BIS_DIRECTIONAL_PATH": 6},
            "masterVersion": "test-v1",
            "busCrosswalkSha256": "a" * 64,
        }

    def test_only_identical_complete_occurrence_paths_are_indexed(self):
        reverse, statuses, counts = build_index(self.audit, self.candidates, self.rejected)
        self.assertEqual(reverse, {"L1": ["safe"], "L2": ["safe"]})
        self.assertEqual(statuses["ambiguous"], "AMBIGUOUS")
        self.assertEqual(statuses["partial"], "PARTIAL")
        self.assertEqual(statuses["unmapped"], "UNMAPPED")
        self.assertEqual(counts["CANDIDATE_COMPLETE"], 1)

    def test_unknown_is_not_encoded_as_zero_incidents(self):
        reverse, statuses, _ = build_index(self.audit, self.candidates, self.rejected)
        matches = match_snapshot(
            reverse,
            {"reportedCount": "2", "rows": [
                {"incidentId": "i1", "linkId": "L1"},
                {"incidentId": "i2", "linkId": "OTHER"},
            ]},
        )
        features = segment_preview_features(statuses, matches)
        self.assertEqual(matches[0]["candidateSegmentIds"], ["safe"])
        self.assertEqual(matches[1]["candidateSegmentIds"], [])
        self.assertEqual(features["safe"]["candidateIncidentCountInSnapshot"], 1)
        self.assertEqual(features["partial"]["candidateIncidentCountInSnapshot"], None)
        self.assertEqual(features["unmapped"]["state"], "UNKNOWN_MAPPING")

    def test_preview_reads_audit_outputs_end_to_end(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "mapping-audit.json").write_text(json.dumps(self.audit), encoding="utf-8")
            for name, rows in (("mapping-candidates.jsonl", self.candidates), ("mapping-rejected.jsonl", self.rejected)):
                (root / name).write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")
            snapshot = root / "snapshot.json"
            snapshot.write_text(json.dumps({"reportedCount": 1, "rows": [{"incidentId": "i1", "linkId": "L2"}]}), encoding="utf-8")
            result = preview(root, snapshot)
        self.assertEqual(result["reverseIndex"]["L2"], ["safe"])
        self.assertEqual(result["matchedSegmentCount"], 1)
        self.assertFalse(result["temporalAndIncidentDirectionVerified"])

    def test_collector_output_is_supported_only_when_complete(self):
        collector = {"isComplete": True, "apiReportedTotalCount": 1,
                     "items": [{"incidentId": "i1", "linkId": "L1"}]}
        self.assertEqual(match_snapshot({"L1": ["safe"]}, collector)[0]["candidateSegmentIds"], ["safe"])
        with self.assertRaisesRegex(ValueError, "incomplete"):
            match_snapshot({}, {**collector, "isComplete": False})

    def test_mismatched_audit_or_snapshot_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "candidate count"):
            build_index({**self.audit, "counts": {"BIS_DIRECTIONAL_PATH": 5}}, self.candidates, self.rejected)
        with self.assertRaisesRegex(ValueError, "incomplete"):
            match_snapshot({}, {"reportedCount": 2, "rows": [{"incidentId": "i1", "linkId": "L1"}]})


if __name__ == "__main__":
    unittest.main()
