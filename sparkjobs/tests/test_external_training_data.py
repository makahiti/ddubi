import csv
import tempfile
import unittest
from pathlib import Path

from preparation.external_training_data import (
    SOURCE_DEFINITIONS,
    audit_bus_join,
    audit_source,
    build_pass_events,
    clean,
    detect_encoding,
    normalise_incident_rows,
    normalise_traffic_rows,
    parse_hourly_interval,
    summarise_pass_events,
    write_pass_events,
)


def write_csv(path: Path, columns: list[str], rows: list[list[str]], encoding="utf-8"):
    with path.open("w", encoding=encoding, newline="") as stream:
        writer = csv.writer(stream)
        writer.writerow(columns)
        writer.writerows(rows)


class ExternalTrainingDataTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.tmp_path = Path(self.temp_dir.name)

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_clean_removes_tdata_bom(self):
        self.assertEqual(clean("\ufeff100100014"), "100100014")

    def test_audit_detects_cp949_incident_file(self):
        path = self.tmp_path / "incident.csv"
        write_csv(
            path,
            ["연번", "사고일", "사고시간", "사고종류", "위도", "경도"],
            [["1", "2024-07-18", "00:07:15", "기상/재난", "37.5", "127.0"]],
            encoding="cp949",
        )

        self.assertEqual(detect_encoding(path), "cp949")
        report = audit_source(path, SOURCE_DEFINITIONS["incident_112"])

        self.assertTrue(report["schema_ok"])
        self.assertEqual(report["row_count"], 1)
        self.assertEqual(report["date_min"], "2024-07-18")

    def test_encoding_detection_does_not_split_utf8_character(self):
        path = self.tmp_path / "utf8-boundary.csv"
        path.write_bytes(b"\xef\xbb\xbf" + (b"a" * 65531) + "선".encode("utf-8"))

        self.assertEqual(detect_encoding(path), "utf-8-sig")

    def test_bus_join_blocks_different_basis_dates(self):
        stops = self.tmp_path / "stops.csv"
        runs = self.tmp_path / "runs.csv"
        write_csv(
            stops,
            list(SOURCE_DEFINITIONS["bus_stop_history"].required_columns),
            [["20260831", "R1", "1", "1", "P1", "20260831050000", "20260831050010", "S1"]],
        )
        write_csv(
            runs,
            list(SOURCE_DEFINITIONS["bus_run_history"].required_columns),
            [["20260901", "R1", "1", "P1", "B1", "20260901050000", "20260901060000"]],
        )

        report = audit_bus_join(stops, runs)

        self.assertEqual(report["overlapping_dates"], [])
        self.assertEqual(report["joinable_stop_rows"], 0)
        self.assertFalse(report["candidate_pass_event_ready"])
        self.assertFalse(report["training_label_ready"])

    def test_builds_same_vehicle_consecutive_stop_events(self):
        stops = self.tmp_path / "stops.csv"
        runs = self.tmp_path / "runs.csv"
        write_csv(
            stops,
            list(SOURCE_DEFINITIONS["bus_stop_history"].required_columns),
            [
                ["20260901", "R1", "1", "1", "P1", "20260901050000", "20260901050010", "S1"],
                ["20260901", "R1", "1", "2", "P1", "20260901050310", "20260901050320", "S2"],
                ["20260901", "R1", "1", "3", "P1", "20260901050820", "20260901050830", "S3"],
            ],
        )
        write_csv(
            runs,
            list(SOURCE_DEFINITIONS["bus_run_history"].required_columns),
            [["20260901", "R1", "1", "P1", "B1", "20260901050000", "20260901060000"]],
        )

        events = build_pass_events(stops, runs)

        self.assertEqual([event["candidate_travel_time_seconds"] for event in events], [180, 300])
        self.assertEqual({event["vehicle_id"] for event in events}, {"B1"})
        self.assertEqual(events[0]["from_stop_id"], "S1")
        self.assertEqual(events[0]["to_stop_id"], "S2")
        self.assertEqual(events[0]["timing_semantics"], "unverified")
        summary = summarise_pass_events(events)
        self.assertEqual(summary["row_count"], 2)
        self.assertEqual(summary["duration_seconds"]["p50"], 180)

    def test_write_pass_events_rejects_empty_output(self):
        with self.assertRaisesRegex(ValueError, "pass event"):
            write_pass_events([], self.tmp_path / "pass-events.csv")

    def test_normalises_tdata_hour_as_closed_interval(self):
        interval = parse_hourly_interval("20260901", "01")
        self.assertEqual(interval[0].isoformat(), "2026-09-01T00:00:00+09:00")
        self.assertEqual(interval[1].isoformat(), "2026-09-01T01:00:00+09:00")
        midnight = parse_hourly_interval("20260901", "24")
        self.assertEqual(midnight[1].isoformat(), "2026-09-02T00:00:00+09:00")

    def test_normalised_external_inputs_leave_unknown_availability_and_mapping_blank(self):
        traffic = self.tmp_path / "traffic.csv"
        incident = self.tmp_path / "incident.csv"
        write_csv(
            traffic,
            ["기준일", "시간코드", "링크ID", "평균속도", "도로명"],
            [["20260901", "01", "L1", "34.5", "테스트로"]],
        )
        write_csv(
            incident,
            ["연번", "사고일", "사고시간", "사고종류", "위도", "경도"],
            [["7", "2024-07-18", "00:07:15", "교통사고", "37.5", "127.0"]],
            encoding="cp949",
        )

        traffic_row = next(normalise_traffic_rows(traffic))
        incident_row = next(normalise_incident_rows(incident))

        self.assertEqual(traffic_row["interval_start"], "2026-09-01T00:00:00+09:00")
        self.assertEqual(traffic_row["available_at"], "")
        self.assertEqual(traffic_row["segment_id"], "")
        self.assertEqual(incident_row["occurred_at"], "2024-07-18T00:07:15+09:00")
        self.assertEqual(incident_row["available_at"], "")
        self.assertEqual(incident_row["segment_id"], "")


if __name__ == "__main__":
    unittest.main()
