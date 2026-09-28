"""부산 GTFS 패턴 분리와 24시간 수식 키 생성을 손계산 자료로 검증한다."""

import importlib.util
from pathlib import Path
import unittest


SCRIPT = Path(__file__).with_name("build_busan_gtfs_integration.py")
SPEC = importlib.util.spec_from_file_location("build_busan_gtfs_integration", SCRIPT)
BUILDER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILDER)


class BuildBusanGtfsIntegrationTest(unittest.TestCase):
    def test_same_route_with_reverse_pattern_gets_distinct_ids(self):
        rows = [
            {"trip_id": "T1", "route_id": "R", "from_stop_id": "A", "to_stop_id": "B",
             "from_sequence": 1, "to_sequence": 2},
            {"trip_id": "T2", "route_id": "R", "from_stop_id": "B", "to_stop_id": "A",
             "from_sequence": 1, "to_sequence": 2},
        ]
        patterns = BUILDER.collect_patterns(rows)
        routes, edges = BUILDER.build_busan_routes(
            patterns,
            [{"route_id": "R", "route_name": "100"}],
            [
                {"stop_id": "A", "stop_name": "가", "stop_lat": 35.1, "stop_lon": 129.1},
                {"stop_id": "B", "stop_name": "나", "stop_lat": 35.2, "stop_lon": 129.2},
            ],
        )
        self.assertEqual(2, len(routes))
        self.assertEqual(2, len({route["routeId"] for route in routes}))
        self.assertTrue(all(".P" in route["routeId"] for route in routes))
        self.assertEqual(2, len(edges))

    def test_missing_hours_are_materialized_with_formula_fallback(self):
        edge = {"rawRouteId": "R", "patternRouteId": "R", "fromStopId": "A",
                "toStopId": "B", "fromOrder": 1, "toOrder": 2, "patternTripCount": 3}
        serving = [{"route_id": "R", "from_stop_id": "A", "to_stop_id": "B",
                    "from_sequence": 1, "to_sequence": 2, "hour": 8, "sample_count": 4,
                    "local_mean_seconds": 80.0, "segment_mean_seconds": 100.0}]
        factors = {str(hour): 1.2 if hour == 9 else 1.0 for hour in range(24)}
        rows = BUILDER.build_formula_rows(
            [edge], serving, factors, "2017-01-01", "2030-12-31")
        self.assertEqual(24, len(rows))
        direct = next(row for row in rows if row["hourSlot"] == 8)
        missing = next(row for row in rows if row["hourSlot"] == 9)
        self.assertAlmostEqual((4 * 80 + .25 * 100) / 4.25, direct["expectedSeconds"])
        self.assertIsNone(missing["localMeanSeconds"])
        self.assertEqual(0, missing["localSampleCount"])
        self.assertEqual(120.0, missing["expectedSeconds"])
        self.assertEqual("BUSAN_GTFS.R", missing["routeId"])

    def test_non_contiguous_trip_is_rejected(self):
        rows = [
            {"trip_id": "T", "route_id": "R", "from_stop_id": "A", "to_stop_id": "B",
             "from_sequence": 1, "to_sequence": 3},
            {"trip_id": "T", "route_id": "R", "from_stop_id": "C", "to_stop_id": "D",
             "from_sequence": 3, "to_sequence": 4},
        ]
        with self.assertRaisesRegex(ValueError, "NON_CONTIGUOUS"):
            BUILDER.collect_patterns(rows)

    def test_gtfs_sequence_gap_is_reindexed_but_preserved_for_formula_lookup(self):
        rows = [{"trip_id": "T", "route_id": "R", "from_stop_id": "A", "to_stop_id": "B",
                 "from_sequence": 10, "to_sequence": 20}]
        patterns = BUILDER.collect_patterns(rows)
        routes, edges = BUILDER.build_busan_routes(
            patterns, [{"route_id": "R", "route_name": "100"}],
            [{"stop_id": "A", "stop_name": "가", "stop_lat": 35.1, "stop_lon": 129.1},
             {"stop_id": "B", "stop_name": "나", "stop_lat": 35.2, "stop_lon": 129.2}],
        )
        self.assertEqual([1, 2], [stop["sequence"] for stop in routes[0]["stops"]])
        self.assertEqual((1, 2), (edges[0]["fromOrder"], edges[0]["toOrder"]))
        self.assertEqual((10, 20), (edges[0]["rawFromOrder"], edges[0]["rawToOrder"]))

    def test_repeated_same_stop_occurrence_is_removed_without_breaking_path(self):
        rows = [
            {"trip_id": "T", "route_id": "R", "from_stop_id": "X", "to_stop_id": "A",
             "from_sequence": 0, "to_sequence": 1},
            {"trip_id": "T", "route_id": "R", "from_stop_id": "A", "to_stop_id": "A",
             "from_sequence": 1, "to_sequence": 2},
            {"trip_id": "T", "route_id": "R", "from_stop_id": "A", "to_stop_id": "B",
             "from_sequence": 2, "to_sequence": 3},
        ]
        pattern = next(iter(BUILDER.collect_patterns(rows)["R"]))
        self.assertEqual((("X", "A", 0, 1), ("A", "B", 2, 3)), pattern)


if __name__ == "__main__":
    unittest.main()
