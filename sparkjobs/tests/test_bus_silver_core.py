"""Run with unittest without Spark; policy checks use collector-shaped fixtures."""

import json
import sys
import tempfile
import unittest
from copy import deepcopy
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from common.bus_normalizer import normalize, normalize_json
from tools.export_bus_silver import export


def event(kind="bus.position", namespace="SEOUL_BUS", raw=None):
    return dict(
        eventId="p1" if kind == "bus.position" else "a1",
        eventType=kind,
        source={
            "SEOUL_BUS": "seoul.bus_location" if kind == "bus.position" else "seoul.bus_arrival",
            "GBIS": "gbis.arrival",
            "INCHEON_API": "incheon.arrival",
        }[namespace],
        occurredAt="2026-09-16T08:10:00+09:00",
        collectedAt="2026-09-16T08:10:05+09:00",
        payload=dict(
            providerNamespace=namespace,
            providerRouteId="00124",
            providerStopId="S12",
            providerVehicleId="V123",
            timestampBasis="received",
            raw=raw or {},
        ),
    )


class BusSilverTests(unittest.TestCase):
    def test_received_time_is_not_observation(self):
        row = normalize(event(raw={"gpsX": "126.978", "gpsY": "37.566", "stationSeq": "12"}))
        self.assertEqual(row["status"], "ACCEPTED")
        self.assertIsNone(row["observedAt"])
        self.assertEqual(row["providerRouteId"], "00124")
        self.assertEqual(row["providerStopOrder"], 12)
        self.assertEqual(row["longitude"], 126.978)

    def test_provider_time_with_short_fraction_is_read(self):
        """서울 mkTm 은 초 소수점이 한 자리로 온다. 파이썬 3.11 미만의 fromisoformat 은
        3자리나 6자리만 읽어서, Spark 컨테이너(3.8)에서 이 값이 통째로 버려졌다.
        그 결과 Redis 버스 도착값의 observedAt 이 비어 백엔드가 버스 실시간을 100% 버렸다
        (2026-09-18). 자릿수만 맞춰 읽도록 고쳤고, 다시 깨지지 않게 여기서 고정한다."""
        for raw_time, expected in (
            ("2026-09-16 08:10:00.0", "2026-09-16T08:10:00+09:00"),
            ("2026-09-16 08:10:00.12", "2026-09-16T08:10:00.120000+09:00"),
            ("2026-09-16 08:10:00.123456", "2026-09-16T08:10:00.123456+09:00"),
            ("2026-09-16 08:10:00", "2026-09-16T08:10:00+09:00"),
        ):
            with self.subTest(raw_time):
                row = normalize(event("bus.arrival", raw={"mkTm": raw_time}))
                self.assertEqual(row["observedAt"], expected)
                self.assertNotIn("SOURCE_TIME_INVALID", row["qualityFlags"])
                self.assertNotIn("SOURCE_TIME_UNAVAILABLE", row["qualityFlags"])

    def test_broken_provider_time_is_still_flagged(self):
        """자릿수만 맞춰 줄 뿐이다. 진짜로 못 읽는 값은 예전처럼 걸러야 한다."""
        row = normalize(event("bus.arrival", raw={"mkTm": "어제 오전"}))
        self.assertIsNone(row["observedAt"])
        self.assertIn("SOURCE_TIME_INVALID", row["qualityFlags"])

    def test_seconds_zero_and_minutes_remain_distinct(self):
        row = normalize(
            event("bus.arrival", raw={"exps1": "0", "traTime1": "3.5", "mkTm": "2026-09-16 08:10:00", "full1": "0"})
        )
        self.assertEqual(row["firstArrivalSeconds"], 0)
        self.assertEqual(row["firstTravelTimeMinutes"], 3.5)
        self.assertEqual(row["firstFullCode"], "0")
        self.assertEqual(row["observedAt"], "2026-09-16T08:10:00+09:00")
        self.assertIsNone(row["secondArrivalSeconds"])

    def test_invalid_optional_values_keep_row(self):
        row = normalize(event("bus.arrival", raw={"exps1": "-1", "exps2": "1.5", "staOrd": "abc"}))
        self.assertEqual(row["status"], "ACCEPTED")
        self.assertIsNone(row["firstArrivalSeconds"])
        self.assertIsNone(row["secondArrivalSeconds"])
        self.assertIn("firstArrivalSeconds:INVALID_NUMBER_OR_RANGE", row["qualityFlags"])

    def test_nonfinite_and_projected_coordinates_are_not_used(self):
        row = normalize(event(raw={"gpsX": "NaN", "gpsY": "37", "tmX": "200000", "tmY": "400000"}))
        self.assertIsNone(row["longitude"])
        self.assertIsNone(row["latitude"])
        self.assertNotIn("NaN", normalize_json(event(raw={"gpsX": "NaN"})).split('"longitude":')[1].split(",")[0])

    def test_boolean_object_and_long_overflow_are_invalid(self):
        row = normalize(event("bus.arrival", raw={"exps1": True, "exps2": {"bad": 1}, "staOrd": "1e30"}))
        for field in ("firstArrivalSeconds", "secondArrivalSeconds", "stopOrder"):
            self.assertIsNone(row[field])
            self.assertIn(field + ":INVALID_NUMBER_OR_RANGE", row["qualityFlags"])

    def test_required_id_rejected(self):
        original = event()
        original["payload"]["providerVehicleId"] = "unknown"
        row = normalize(original)
        self.assertEqual(row["status"], "REJECTED")
        self.assertIn("MISSING_OR_INVALID:providerVehicleId", row["rejectionReasons"])

    def test_region_units_and_incheon_vehicle(self):
        row = normalize(
            event("bus.arrival", "GBIS", {"predictTime1": "3", "predictTimeSec2": "75", "predictTime2": "9"})
        )
        self.assertEqual((row["firstArrivalSeconds"], row["secondArrivalSeconds"]), (180, 75))
        row = normalize(event("bus.arrival", "INCHEON_API", {"ARRIVALESTIMATETIME": "90"}))
        self.assertEqual(row["firstVehicleId"], "V123")
        self.assertIsNone(row["secondArrivalSeconds"])

    def test_source_conflict_and_bad_json(self):
        original = event()
        original["payload"]["providerNamespace"] = "GBIS"
        self.assertIn("SOURCE_NAMESPACE_MISMATCH", normalize(original)["rejectionReasons"])
        self.assertEqual(normalize("{broken")["status"], "REJECTED")
        self.assertEqual(normalize({"eventType": "weather.observation"})["status"], "IGNORED")

    def test_future_source_time_and_invalid_collection(self):
        row = normalize(event("bus.arrival", raw={"mkTm": "2026-09-16 09:00:00"}))
        self.assertIsNone(row["observedAt"])
        self.assertIsNone(row["sourceGeneratedAt"])
        original = event()
        original["collectedAt"] = "2026-09-16 08:10:00"
        self.assertEqual(normalize(original)["status"], "REJECTED")

    def test_export_accounting_conflicts_and_no_latest_collapse(self):
        a = event()
        b = deepcopy(a)
        b["eventId"] = "p2"  # same vehicle, separate observation must survive
        conflict = deepcopy(b)
        conflict["payload"]["raw"] = {"stationSeq": "14"}
        lines = [json.dumps(x) for x in (a, a, b, conflict, {"eventType": "weather.observation"})] + ["{broken"]
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "result"
            report = export(lines, output)
            self.assertEqual(
                (report["acceptedRows"], report["rejectedRows"], report["duplicateRows"], report["ignoredRows"]),
                (1, 3, 1, 1),
            )
            self.assertEqual(
                report["inputRows"],
                sum(report[k] for k in ("acceptedRows", "rejectedRows", "duplicateRows", "ignoredRows")),
            )
            with self.assertRaises(FileExistsError):
                export(lines, output)
        with tempfile.TemporaryDirectory() as directory:
            later = event(raw={"stationSeq": "13"})
            later["eventId"] = "p3"
            report = export([json.dumps(a), json.dumps(later)], Path(directory) / "out")
            self.assertEqual(report["acceptedRows"], 2)


if __name__ == "__main__":
    unittest.main()
