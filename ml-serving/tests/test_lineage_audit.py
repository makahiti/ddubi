"""계보 검사기가 결측을 0과 같다고 판정하거나 숫자 불일치를 숨기지 않는지 검증한다."""

import sys
from pathlib import Path

import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from tools.audit_v13_lineage import compare, normalize_raw


def test_audit_detects_zero_instead_of_missing_and_missing_keys():
    expected = pd.DataFrame({"id": ["a", "b"], "seconds": [None, 12.0]})
    actual = pd.DataFrame({"id": ["a"], "seconds": [0.0]})
    report = compare(expected, actual, ["id"], ["seconds"])
    assert not report["equal"] and report["mismatch_rows"] == 2


def test_independent_raw_recalculation_preserves_positive_hourly_mean():
    row = {
        "기준_날짜": "20220103",
        "노선_ID": "001",
        "출발_정류장_ID": "002",
        "도착_정류장_ID": "003",
        "출발_정류장_순서": "7",
    }
    row.update({f"운행시간_{h:02d}시": "0" for h in range(24)})
    row["운행시간_09시"] = "32.5"
    result, zero_count = normalize_raw(pd.DataFrame([row], dtype="string"))
    assert zero_count == 23
    assert result.h00.isna().all() and result.h09.iloc[0] == 32.5
