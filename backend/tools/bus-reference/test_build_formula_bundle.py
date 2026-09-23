"""버스 수식 번들 생성기가 Java 런타임 계약과 같은 결과를 만드는지 검증한다."""

import gzip
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("build_formula_bundle.py")
SPEC = importlib.util.spec_from_file_location("build_formula_bundle", SCRIPT)
BUILDER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILDER)


class BuildFormulaBundleTest(unittest.TestCase):
    def common(self, namespace):
        """실제 장애가 수식보다 ID·표본 계약에서 먼저 차단되는지 확인할 최소 행이다."""
        prefix = {"GBIS": "GBIS.", "INCHEON_PUBLIC_CSV": "INCHEON_PUBLIC_CSV.",
                  "BUSAN_GTFS": "BUSAN_GTFS."}.get(namespace, "")
        return {
            "namespace": namespace,
            "routeId": prefix + "R1",
            "fromStopId": prefix + "S1",
            "toStopId": prefix + "S2",
            "fromOrder": 1,
            "toOrder": 2,
            "dow": 1,
            "hourSlot": 8,
            "sampleKind": "GTFS_SCHEDULE_FORMULA" if namespace != "SEOUL_BUS" else "HOURLY_SOURCE_MEAN",
            "sampleCount": 5,
            "observedDates": 0 if namespace != "SEOUL_BUS" else 3,
            "observationStart": "2026-01-01",
            "observationEnd": "2026-01-03",
        }

    def build_one(self, row):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source = root / "input.jsonl"
            source.write_text(json.dumps(row) + "\n", encoding="utf-8")
            output = root / "bundle"
            summary = BUILDER.build(source, output, "master-v1")
            with gzip.open(output / "000000.json.gz", "rt", encoding="utf-8") as stream:
                result = json.load(stream)["rows"][0]
            return summary, result

    def test_seoul_half_life_sufficient_statistics(self):
        row = self.common("SEOUL_BUS")
        row.update(weightedSeconds=600.0, weightTotal=4.0)
        summary, result = self.build_one(row)
        self.assertEqual(150.0, result["meanSeconds"])
        self.assertEqual("SEOUL_HALF_LIFE_180D", result["formulaInput"]["formula"])
        self.assertTrue(summary["formulaInputRequired"])

    def test_metropolitan_missing_hour_uses_segment_factor(self):
        row = self.common("GBIS")
        row.update(localMeanSeconds=None, localSampleCount=0,
                   segmentMeanSeconds=100.0, hourFactor=1.2)
        _, result = self.build_one(row)
        self.assertEqual(120.0, result["meanSeconds"])

    def test_busan_shrinkage_uses_fixed_alpha(self):
        row = self.common("BUSAN_GTFS")
        row.update(localMeanSeconds=80.0, localSampleCount=4,
                   segmentMeanSeconds=100.0, hourFactor=1.1, alpha=0.25)
        _, result = self.build_one(row)
        expected = (4 * 80.0 + 0.25 * 100.0 * 1.1) / 4.25
        self.assertAlmostEqual(expected, result["meanSeconds"])

    def test_raw_provider_id_is_rejected(self):
        row = self.common("GBIS")
        row.update(routeId="R1", localMeanSeconds=80.0, localSampleCount=4,
                   segmentMeanSeconds=90.0, hourFactor=1.0)
        with self.assertRaisesRegex(ValueError, "NON_CANONICAL_ID"):
            self.build_one(row)

    def test_gtfs_non_template_day_is_rejected(self):
        row = self.common("INCHEON_PUBLIC_CSV")
        row.update(dow=2, localMeanSeconds=80.0, localSampleCount=4,
                   segmentMeanSeconds=90.0, hourFactor=1.0)
        with self.assertRaisesRegex(ValueError, "INVALID_GTFS_SAMPLE"):
            self.build_one(row)

    def test_duplicate_exact_key_is_rejected(self):
        row = self.common("SEOUL_BUS")
        row.update(weightedSeconds=600.0, weightTotal=4.0)
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source = root / "input.jsonl"
            source.write_text(json.dumps(row) + "\n" + json.dumps(row) + "\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "DUPLICATE_REFERENCE_KEY"):
                BUILDER.build(source, root / "bundle", "master-v1")

    def test_multiple_sorted_sources_stream_into_one_bundle(self):
        busan = self.common("BUSAN_GTFS")
        busan.update(localMeanSeconds=80.0, localSampleCount=4,
                     segmentMeanSeconds=100.0, hourFactor=1.1, alpha=0.25)
        gbis = self.common("GBIS")
        gbis.update(localMeanSeconds=90.0, localSampleCount=2,
                    segmentMeanSeconds=95.0, hourFactor=1.0)
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            first, second = root / "busan.jsonl", root / "gbis.jsonl"
            first.write_text(json.dumps(busan) + "\n", encoding="utf-8")
            second.write_text(json.dumps(gbis) + "\n", encoding="utf-8")
            result = BUILDER.build_streaming([first, second], root / "bundle", "master-v1")
            self.assertEqual(2, result["rows"])
            self.assertEqual({"BUSAN_GTFS": 1, "GBIS": 1}, result["namespaces"])

    def test_streaming_sorts_sources_regardless_of_input_order(self):
        gbis = self.common("GBIS")
        gbis.update(localMeanSeconds=90.0, localSampleCount=2,
                    segmentMeanSeconds=95.0, hourFactor=1.0)
        busan = self.common("BUSAN_GTFS")
        busan.update(localMeanSeconds=80.0, localSampleCount=4,
                     segmentMeanSeconds=100.0, hourFactor=1.1, alpha=0.25)
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            first, second = root / "gbis.jsonl", root / "busan.jsonl"
            first.write_text(json.dumps(gbis) + "\n", encoding="utf-8")
            second.write_text(json.dumps(busan) + "\n", encoding="utf-8")
            result = BUILDER.build_streaming([first, second], root / "bundle", "master-v1")
            self.assertEqual(2, result["rows"])
            with gzip.open(root / "bundle" / "000000.json.gz", "rt", encoding="utf-8") as stream:
                namespaces = [row["namespace"] for row in json.load(stream)["rows"]]
            self.assertEqual(["BUSAN_GTFS", "GBIS"], namespaces)

    def test_streaming_removes_partial_output_on_duplicate(self):
        row = self.common("GBIS")
        row.update(localMeanSeconds=90.0, localSampleCount=2,
                   segmentMeanSeconds=95.0, hourFactor=1.0)
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            first, second = root / "one.jsonl", root / "two.jsonl"
            first.write_text(json.dumps(row) + "\n", encoding="utf-8")
            second.write_text(json.dumps(row) + "\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "DUPLICATE_REFERENCE_KEY"):
                BUILDER.build_streaming([first, second], root / "bundle", "master-v1")
            self.assertFalse((root / "bundle.partial").exists())


if __name__ == "__main__":
    unittest.main()
