from datetime import date
import unittest

from preparation.download_tdata_history import find_rows, iter_dates


class DownloadTDataHistoryTest(unittest.TestCase):
    def test_iter_dates_is_inclusive(self):
        self.assertEqual(
            list(iter_dates(date(2026, 8, 24), date(2026, 8, 26))),
            [date(2026, 8, 24), date(2026, 8, 25), date(2026, 8, 26)],
        )

    def test_find_rows_accepts_nested_tdata_wrapper(self):
        rows = [{"stdrDe": "20220801"}, {"stdrDe": "20220801"}]
        self.assertEqual(find_rows({"response": {"body": {"items": rows}}}), rows)

    def test_find_rows_rejects_ambiguous_arrays(self):
        with self.assertRaisesRegex(ValueError, "ambiguous"):
            find_rows({"left": [{"a": 1}], "right": [{"b": 2}]})


if __name__ == "__main__":
    unittest.main()
