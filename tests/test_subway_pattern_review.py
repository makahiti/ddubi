import importlib.util
from pathlib import Path
import unittest

spec=importlib.util.spec_from_file_location("pattern_review",Path(__file__).parents[1]/"infra/scripts/subway-review-patterns.py")
review=importlib.util.module_from_spec(spec)
spec.loader.exec_module(review)


class ReviewTests(unittest.TestCase):
    def test_direction_and_branch_order(self):
        self.assertEqual(review.sequence_relation(["a","b"],["x","a","b","c"]),"CONTIGUOUS")
        self.assertIsNone(review.sequence_relation(["b","a"],["a","b"]))
        self.assertIsNone(review.sequence_relation(["a","c","b"],["a","b","c"]))

    def test_express_is_not_contiguous(self):
        self.assertEqual(review.sequence_relation(["a","c"],["a","b","c"]),"ORDERED_SUBSEQUENCE")
        self.assertIsNone(review.sequence_relation(["a",None],["a","b"]))

    def test_terminal_station_suffix_only(self):
        self.assertTrue(review.terminal_station_variant("평내호평", "평내호평역"))
        self.assertTrue(review.terminal_station_variant("서울역", "서울"))
        self.assertFalse(review.terminal_station_variant("역곡", "곡"))
        self.assertFalse(review.terminal_station_variant("서울", "서울대입구"))

    def test_loop_does_not_drop_repeated_stop(self):
        self.assertIsNone(review.sequence_relation(["a","b","a"],["a","b"]))
        self.assertEqual(review.sequence_relation(["a","b","a"],["a","b","a"]),"CONTIGUOUS")

    def test_coordinate_gap_causes(self):
        item=dict(status="NAME_ONLY_OR_COORDINATE_CONFLICT",sourceStop=dict(stop_lat="37",stop_lon="127"),evidence=[dict(distanceMeters=None)])
        self.assertEqual(review.diagnose(item,{}, {},300)[0],"MASTER_COORDINATES_UNAVAILABLE")
        item["evidence"]=[dict(distanceMeters=500)]
        self.assertEqual(review.diagnose(item,{}, {},300)[0],"NAME_MATCH_COORDINATES_OUTSIDE_RADIUS")

    def test_contract_never_promotes_source_ids(self):
        data=dict(productionReady=False,canonicalPatternId=None,stops=[dict(sequence=1,sourceStopId="a",sourceStop={"stop_name":"a"}),dict(sequence=2,sourceStopId="b",sourceStop={"stop_name":"b"})])
        review.validate_candidate(data)
        data["canonicalPatternId"]="invented"
        with self.assertRaises(ValueError): review.validate_candidate(data)


if __name__=="__main__":unittest.main()
