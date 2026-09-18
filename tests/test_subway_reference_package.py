import importlib.util
from pathlib import Path
import unittest

spec=importlib.util.spec_from_file_location("subway_reference",Path(__file__).parents[1]/"infra/scripts/subway-build-reference-package.py")
ref=importlib.util.module_from_spec(spec)
spec.loader.exec_module(ref)


class ReferenceTests(unittest.TestCase):
    def test_ordered_nonconsecutive_sequence_and_overnight(self):
        calls=[dict(stop_id="b",stop_sequence="20",arrival_time="25:02:00",departure_time="25:02:10"),
               dict(stop_id="a",stop_sequence="10",arrival_time="25:00:00",departure_time="25:00:10")]
        result=ref.validate_calls(calls)
        self.assertEqual(result[0][0],"a")
        self.assertEqual(result[1][1]-result[0][2],110)

    def test_bad_middle_call_quarantines_whole_trip(self):
        calls=[dict(stop_id="a",stop_sequence="1",arrival_time="01:00:00",departure_time="01:00:10"),
               dict(stop_id="b",stop_sequence="2",arrival_time="00:59:00",departure_time="01:00:10")]
        with self.assertRaises(ValueError):ref.validate_calls(calls)

    def test_duplicate_departures_not_zero_headway(self):
        buckets,duplicates=ref.headways([3600,3600,3900,7500])
        self.assertEqual(duplicates,1)
        self.assertEqual(buckets[1],[300,3600])

    def test_service_and_pattern_not_combined(self):
        self.assertNotEqual(ref.identity("r","weekday",["a","b"]),ref.identity("r","sunday",["a","b"]))
        self.assertNotEqual(ref.identity("r","weekday",["a","b"]),ref.identity("r","weekday",["b","a"]))

    def test_no_invented_current_dates(self):
        data=dict(productionReady=False,usage="UNDATED_SCHEDULE_REFERENCE",patterns=[],
                  availability=dict(sourcePublishedAt=None,sourceAvailableAt=None,currentValidFrom=None,currentValidUntil=None))
        ref.validate_package(data)
        data["availability"]["currentValidUntil"]="2030-01-01"
        with self.assertRaises(ValueError):ref.validate_package(data)


if __name__=="__main__":unittest.main()
