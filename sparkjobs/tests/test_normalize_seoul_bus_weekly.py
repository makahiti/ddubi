import pandas as pd
import pytest

from preparation.normalize_seoul_bus_weekly import normalise_chunk


def source_row():
    row = {
        "기준_날짜": "20260706",
        "노선_ID": "100100352",
        "출발_정류장_ID": "112000076",
        "도착_정류장_ID": "112000077",
        "출발_정류장_순서": "46",
        "도착_정류장_순서": "47",
    }
    row.update({f"운행시간_{hour:02d}시": "105" for hour in range(24)})
    return row


def test_normalise_chunk_matches_training_schema():
    frame, stats = normalise_chunk(pd.DataFrame([source_row()]))

    assert len(frame) == 24
    assert frame["date"].unique().tolist() == ["2026-07-06"]
    assert frame["year_month"].unique().tolist() == ["202607"]
    assert frame["hour"].tolist() == list(range(24))
    assert frame["segment_id"].unique().tolist() == [
        "BUS:SEOUL:100100352:112000076:112000077"
    ]
    assert stats["nonpositive_or_null_target_rows"] == 0


def test_normalise_chunk_rejects_missing_hour():
    row = source_row()
    del row["운행시간_23시"]

    with pytest.raises(ValueError, match="hour_columns=23"):
        normalise_chunk(pd.DataFrame([row]))
