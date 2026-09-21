import csv
import gzip
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "subway_mapping", Path(__file__).parents[1] / "infra/scripts/subway-map-local-gtfs.py"
)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class MappingTests(unittest.TestCase):
    def test_region_and_line_alias_do_not_merge_city_lines(self):
        self.assertEqual(module.line_name("서울1호선"), "1호선")
        self.assertEqual(module.line_name("부산1호선"), "부산1호선")
        self.assertEqual(module.region("대경선"), "대구·경북")
        self.assertEqual(module.region("unknown"), "미분류")

    def test_same_name_requires_coordinate_and_uniqueness(self):
        stop = dict(stop_name="서울역", stop_lat="37.5", stop_lon="127")
        stations = {"1": dict(stationName="서울역")}
        self.assertEqual(module.match_station(stop, stations, {}, 300)[0], "NAME_ONLY_OR_COORDINATE_CONFLICT")
        coords = {"1": dict(latitude=37.5, longitude=127)}
        self.assertEqual(module.match_station(stop, stations, coords, 300)[1], "1")
        stations["2"] = dict(stationName="서울역")
        coords["2"] = coords["1"]
        self.assertEqual(module.match_station(stop, stations, coords, 300)[0], "AMBIGUOUS")

    def test_bad_coordinates_rejected(self):
        for value in ("nan", "999", ""):
            self.assertIsNone(module.distance(dict(stop_lat=value, stop_lon="127"), dict(latitude=37, longitude=127)))

    def test_time_after_midnight_and_invalid_minutes(self):
        self.assertEqual(module.seconds("25:00:00"), 90000)
        with self.assertRaises(ValueError):
            module.seconds("25:61:00")

    def test_end_to_end_directed_evidence_national_candidates_and_reuse(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)

            def table(name, fields, data):
                with (root / name).open("w", encoding="utf-8", newline="") as f:
                    writer = csv.writer(f)
                    writer.writerow(fields.split(","))
                    writer.writerows(data)

            table(
                "routes.txt",
                "route_id,route_short_name,route_long_name,route_type",
                [["r", "서울1호선", "상행", "1"], ["b", "부산1호선", "하행", "1"]],
            )
            table("trips.txt", "route_id,trip_id,service_id", [["r", "t", "s"], ["b", "u", "s"]])
            table(
                "stops.txt",
                "stop_id,stop_name,stop_lat,stop_lon",
                [["a", "가", "37", "127"], ["b", "나", "37.01", "127"]],
            )
            table(
                "stop_times.txt",
                "trip_id,stop_id,stop_sequence,arrival_time,departure_time",
                [
                    ["t", "b", 20, "25:02:00", "25:02:10"],
                    ["t", "a", 10, "25:00:00", "25:00:10"],
                    ["u", "a", 1, "25:00:00", "25:00:10"],
                    ["u", "b", 2, "25:02:00", "25:02:10"],
                ],
            )
            table("calendar.txt", "service_id,start_date,end_date", [["s", "20170101", "20301231"]])
            master = dict(
                dataVersion="v",
                lines=[
                    dict(
                        lineName="1호선",
                        subwayId="1",
                        direction="UP",
                        servicePatternId="p",
                        stations=[
                            dict(stationId="1", stationName="가", sequence=1),
                            dict(stationId="2", stationName="나", sequence=2),
                        ],
                    )
                ],
            )
            (root / "master.gz").write_bytes(gzip.compress(json.dumps(master).encode()))
            (root / "coords.json").write_text(
                json.dumps({"1": dict(latitude=37, longitude=127), "2": dict(latitude=37.01, longitude=127)})
            )
            args = (root, root / "master.gz", root / "coords.json", root / "out")
            result = module.build(*args)
            self.assertEqual(result["directedEdgeCandidates"], 1)
            self.assertEqual(result["missingMasterLines"], ["부산1호선"])
            self.assertEqual(result["validScheduledSamples"], 2)
            self.assertFalse(result["productionReady"])
            self.assertEqual(module.build(*args), result)
            # Opposite-direction edges must not count as matching directed evidence.
            master["lines"][0]["stations"][0]["sequence"] = 2
            master["lines"][0]["stations"][1]["sequence"] = 1
            (root / "reverse.gz").write_bytes(gzip.compress(json.dumps(master).encode()))
            reverse = module.build(root, root / "reverse.gz", root / "coords.json", root / "reverse-out")
            self.assertEqual(reverse["directedEdgeCandidates"], 0)
            with (root / "out" / "summary.json").open("a") as f:
                f.write(" ")
            with self.assertRaises(ValueError):
                module.build(*args)


if __name__ == "__main__":
    unittest.main()
