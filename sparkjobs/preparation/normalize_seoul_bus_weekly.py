"""서울 버스 구간시간 주차 ZIP을 월별 학습 Parquet로 정규화한다.

월간 ZIP이 게시되기 전의 주차 파일을 기존 학습 스키마와 동일한 long 형식으로
변환한다. 원본은 수정하지 않고 출력 디렉터리 아래 ``month=YYYYMM/data.parquet``와
검증 보고서를 새로 만든다.
"""

from __future__ import annotations

import argparse
import json
import re
import zipfile
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

import pandas as pd
import pyarrow as pa
import pyarrow.parquet as pq


ID_COLUMNS = {
    "date": "기준_날짜",
    "route_id": "노선_ID",
    "departure_stop_id": "출발_정류장_ID",
    "arrival_stop_id": "도착_정류장_ID",
    "departure_stop_order": "출발_정류장_순서",
    "arrival_stop_order": "도착_정류장_순서",
}
OUTPUT_COLUMNS = [
    "date",
    "year_month",
    "hour",
    "day_of_week",
    "is_weekend",
    "route_id",
    "departure_stop_id",
    "arrival_stop_id",
    "departure_stop_order",
    "arrival_stop_order",
    "segment_id",
    "segment_time_sec",
]


def clean_id(series: pd.Series) -> pd.Series:
    return series.astype("string").str.strip().str.replace(r"\.0$", "", regex=True)


def normalise_chunk(frame: pd.DataFrame) -> tuple[pd.DataFrame, dict[str, int]]:
    frame.columns = (
        frame.columns.astype(str)
        .str.strip()
        .str.replace(chr(34), "", regex=False)
        .str.lstrip(chr(0xFEFF))
    )
    missing = [source for source in ID_COLUMNS.values() if source not in frame.columns]
    hour_columns = [column for column in frame.columns if re.fullmatch(r"운행시간_\d{2}시", column)]
    if missing or len(hour_columns) != 24:
        raise ValueError(f"schema mismatch: missing={missing}, hour_columns={len(hour_columns)}")

    long = frame[list(ID_COLUMNS.values()) + hour_columns].melt(
        id_vars=list(ID_COLUMNS.values()),
        value_vars=hour_columns,
        var_name="hour_column",
        value_name="segment_time_sec",
    )
    for target, source in ID_COLUMNS.items():
        if target.endswith("_order"):
            long[target] = pd.to_numeric(long[source], errors="coerce").astype("Int32")
        elif target == "date":
            long[target] = pd.to_datetime(clean_id(long[source]), format="%Y%m%d", errors="coerce")
        else:
            long[target] = clean_id(long[source])
    long["hour"] = long["hour_column"].str.extract(r"(\d{2})시")[0].astype("int8")
    long["segment_time_sec"] = pd.to_numeric(long["segment_time_sec"], errors="coerce").astype("float32")

    valid_key = long[["date", "route_id", "departure_stop_id", "arrival_stop_id"]].notna().all(axis=1)
    positive_target = long["segment_time_sec"].gt(0)
    stats = {
        "expanded_rows": int(len(long)),
        "invalid_key_rows": int((~valid_key).sum()),
        "nonpositive_or_null_target_rows": int((~positive_target).sum()),
    }
    long = long.loc[valid_key & positive_target].copy()
    long["date"] = long["date"].dt.strftime("%Y-%m-%d").astype("string")
    long["year_month"] = long["date"].str.replace("-", "", regex=False).str[:6]
    day_of_week = pd.to_datetime(long["date"]).dt.dayofweek.astype("int8")
    long["day_of_week"] = day_of_week
    long["is_weekend"] = day_of_week.ge(5).astype("int8")
    long["segment_id"] = (
        "BUS:SEOUL:"
        + long["route_id"]
        + ":"
        + long["departure_stop_id"]
        + ":"
        + long["arrival_stop_id"]
    ).astype("string")
    return long[OUTPUT_COLUMNS], stats


class MonthWriter:
    def __init__(self, output_root: Path, month: str) -> None:
        self.month = month
        self.final_path = output_root / f"month={month}" / "data.parquet"
        self.temp_path = self.final_path.with_suffix(".parquet.part")
        self.final_path.parent.mkdir(parents=True, exist_ok=True)
        if self.final_path.exists() or self.temp_path.exists():
            raise FileExistsError(f"output already exists: {self.final_path}")
        self.writer: pq.ParquetWriter | None = None
        self.rows = 0
        self.dates: Counter[str] = Counter()

    def write(self, frame: pd.DataFrame) -> None:
        if frame.empty:
            return
        table = pa.Table.from_pandas(frame, preserve_index=False)
        if self.writer is None:
            self.writer = pq.ParquetWriter(self.temp_path, table.schema, compression="snappy")
        self.writer.write_table(table)
        self.rows += len(frame)
        self.dates.update(frame["date"].astype(str))

    def close(self) -> None:
        if self.writer is None:
            raise ValueError(f"month {self.month} has no rows")
        self.writer.close()
        self.temp_path.replace(self.final_path)


def csv_members(archive: zipfile.ZipFile) -> list[str]:
    return [name for name in archive.namelist() if name.lower().endswith(".csv")]


def run(
    inputs: list[Path],
    output_root: Path,
    months: set[str],
    chunk_size: int,
    excluded_dates: set[str] | None = None,
) -> dict[str, object]:
    excluded_dates = excluded_dates or set()
    writers = {month: MonthWriter(output_root, month) for month in sorted(months)}
    totals = Counter[str]()
    files: list[dict[str, object]] = []
    try:
        for path in inputs:
            file_rows = 0
            with zipfile.ZipFile(path) as archive:
                members = csv_members(archive)
                if not members:
                    raise ValueError(f"CSV member not found: {path}")
                for member in members:
                    with archive.open(member) as stream:
                        for chunk in pd.read_csv(stream, encoding="cp949", dtype="string", chunksize=chunk_size):
                            normalised, stats = normalise_chunk(chunk)
                            if excluded_dates:
                                excluded_rows = int(normalised["date"].isin(excluded_dates).sum())
                                totals["excluded_existing_date_rows"] += excluded_rows
                                normalised = normalised.loc[
                                    ~normalised["date"].isin(excluded_dates)
                                ]
                            totals.update(stats)
                            for month, writer in writers.items():
                                selected = normalised.loc[normalised["year_month"] == month]
                                writer.write(selected)
                                file_rows += len(selected)
            files.append({"file": path.name, "selected_rows": file_rows})
        for writer in writers.values():
            writer.close()
    except Exception:
        for writer in writers.values():
            if writer.writer is not None:
                writer.writer.close()
            writer.temp_path.unlink(missing_ok=True)
        raise

    month_reports = {}
    for month, writer in writers.items():
        month_reports[month] = {
            "rows": writer.rows,
            "date_min": min(writer.dates) if writer.dates else None,
            "date_max": max(writer.dates) if writer.dates else None,
            "date_count": len(writer.dates),
            "dates": sorted(writer.dates),
            "duplicate_rows": None,
            "output": str(writer.final_path),
        }
    return {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "inputs": files,
        "months": month_reports,
        "filter_totals": dict(totals),
        "excluded_dates": sorted(excluded_dates),
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inputs", nargs="+", type=Path)
    parser.add_argument("--output-root", required=True, type=Path)
    parser.add_argument("--month", action="append", required=True, help="YYYYMM; repeatable")
    parser.add_argument("--chunk-size", type=int, default=20_000)
    parser.add_argument(
        "--exclude-date",
        action="append",
        default=[],
        help="이미 다른 파티션에 존재하는 YYYY-MM-DD; repeatable",
    )
    parser.add_argument("--report", type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    months = set(args.month)
    if any(not re.fullmatch(r"20\d{4}", month) for month in months):
        raise SystemExit("--month must be YYYYMM")
    excluded_dates = set(args.exclude_date)
    if any(not re.fullmatch(r"20\d{2}-\d{2}-\d{2}", value) for value in excluded_dates):
        raise SystemExit("--exclude-date must be YYYY-MM-DD")
    report = run(args.inputs, args.output_root, months, args.chunk_size, excluded_dates)
    report_path = args.report or args.output_root / "weekly-normalization-report.json"
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
