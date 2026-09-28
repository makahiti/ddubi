"""수도권 GTFS 매핑이 유일한 공식 ID 증거만 채택하는지 검증한다."""

import importlib.util
from pathlib import Path
import unittest


SCRIPT = Path(__file__).with_name("build_metropolitan_gtfs_mapping.py")
SPEC = importlib.util.spec_from_file_location("build_metropolitan_gtfs_mapping", SCRIPT)
BUILDER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILDER)


class MetropolitanGtfsMappingTest(unittest.TestCase):
    def master(self):
        return {"routes": [{"namespace": "GBIS", "routeId": "1", "routeName": "100번",
                            "stops": [{"stopId": "10", "sequence": 1},
                                      {"stopId": "11", "sequence": 2}]}]}

    def test_unique_route_name_and_stop_ids_map(self):
        routes = [{"route_id": "GR", "route_name": "100"}]
        segments = [{"region": "GYEONGGI", "route_id": "GR",
                     "from_stop_id": "BS_GGB10", "to_stop_id": "BS_GGB11",
                     "from_sequence": 5, "to_sequence": 8}]
        mapped, reasons = BUILDER.map_master_edges(
            self.master(), BUILDER.build_candidate_index(routes, segments))
        self.assertEqual(1, len(mapped))
        self.assertFalse(reasons)
        self.assertEqual(("GR", "BS_GGB10", "BS_GGB11", 5, 8), mapped[0]["gtfsEdge"])

    def test_multiple_gtfs_candidates_are_not_guessed(self):
        routes = [{"route_id": "A", "route_name": "100"}, {"route_id": "B", "route_name": "100"}]
        segments = [{"region": "GYEONGGI", "route_id": route,
                     "from_stop_id": "BS10", "to_stop_id": "BS11",
                     "from_sequence": 1, "to_sequence": 2} for route in ("A", "B")]
        mapped, reasons = BUILDER.map_master_edges(
            self.master(), BUILDER.build_candidate_index(routes, segments))
        self.assertFalse(mapped)
        self.assertEqual(1, reasons["AMBIGUOUS_GTFS_EDGE"])

    def test_formula_uses_local_or_missing_hour_factor(self):
        mapped = [{"namespace": "GBIS", "routeId": "1", "fromStopId": "10",
                   "toStopId": "11", "fromOrder": 1, "toOrder": 2,
                   "gtfsEdge": ("G", "A", "B", 1, 2)}]
        cells = {("G", "A", "B", 1, 2, 8): {"sample_count": 3, "local_mean_seconds": 70.0}}
        factors = {str(hour): 1.2 if hour == 9 else 1.0 for hour in range(24)}
        rows = list(BUILDER.formula_rows(mapped, cells, {mapped[0]["gtfsEdge"]: 100.0},
                    {mapped[0]["gtfsEdge"]: 5}, factors, "2017-01-01", "2030-12-31"))
        self.assertEqual(70.0, rows[8]["expectedSeconds"])
        self.assertEqual(120.0, rows[9]["expectedSeconds"])
        self.assertEqual("GBIS.1", rows[9]["routeId"])

    def test_out_of_range_edge_is_rejected_before_export(self):
        mapped = {"gtfsEdge": ("G", "A", "B", 1, 2)}
        factors = {str(hour): 1.0 for hour in range(24)}
        self.assertFalse(BUILDER.valid_formula_edge(mapped, {}, {mapped["gtfsEdge"]: 3601.0}, factors))


if __name__ == "__main__":
    unittest.main()
