"""보존된 v13과 과거 버스 정규화의 계보를 읽기 전용으로 대조한다.

모델/원천은 수정하지 않는다. 별도 출력 폴더에 SHA·mtime(생성시각 아님), 날짜별
정규화→학습 입력 비교와 원본 ZIP 표본 재계산을 기록한다. 공급처가 제공한 시간평균의
개별 운행 표본 수는 원본에 없으므로 추측하지 않는다. CPU 4개 이하, GPU 미사용이다.
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import zipfile
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime
from pathlib import Path

# BLAS 초기화 전에 스레드 수를 제한한다. 표준 라이브러리 import에는 영향이 없다.
os.environ["OMP_NUM_THREADS"] = "1"
os.environ["OPENBLAS_NUM_THREADS"] = "1"
os.environ["MKL_NUM_THREADS"] = "1"

import numpy as np
import pandas as pd

KEY = ["provider_route_id", "provider_from_stop_id", "provider_to_stop_id", "from_stop_sequence"]
HOURS = [f"h{i:02d}" for i in range(24)]


def sha(path):
    """대용량 원본도 메모리에 전부 올리지 않고 해시한다."""
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(8 * 1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def info(path):
    """mtime은 관측한 파일 수정시각으로만 기록하며 원래 생성시각을 대신하지 않는다."""
    path = Path(path)
    return dict(
        path=str(path),
        sha256=sha(path),
        bytes=path.stat().st_size,
        modified_at=datetime.fromtimestamp(path.stat().st_mtime, UTC).isoformat(),
        original_created_at=None,
    )


def write(path, data):
    Path(path).write_text(json.dumps(data, ensure_ascii=False, indent=2, default=str), encoding="utf-8")


def compare(expected, actual, keys, values):
    """중복 키를 숨기지 않고 outer join하여 결측과 숫자를 각각 비교한다."""
    if expected.duplicated(keys).any() or actual.duplicated(keys).any():
        return dict(equal=False, duplicate_keys=True)
    joined = expected.merge(actual, on=keys, how="outer", suffixes=("_expected", "_actual"), indicator=True)
    bad = joined["_merge"].ne("both").to_numpy(copy=True)
    max_error = 0.0
    for name in values:
        a = pd.to_numeric(joined[name + "_expected"], errors="coerce").to_numpy(dtype=float)
        b = pd.to_numeric(joined[name + "_actual"], errors="coerce").to_numpy(dtype=float)
        same = (np.isnan(a) & np.isnan(b)) | np.isclose(a, b, rtol=0, atol=1e-5, equal_nan=True)
        bad |= ~same
        finite = np.isfinite(a) & np.isfinite(b)
        if finite.any():
            max_error = max(max_error, float(np.max(np.abs(a[finite] - b[finite]))))
    return dict(
        equal=not bool(bad.any()),
        expected_rows=len(expected),
        actual_rows=len(actual),
        mismatch_rows=int(bad.sum()),
        max_error=max_error,
        examples=joined.loc[bad].head(10).to_dict("records"),
    )


def normalize_raw(frame):
    """OA-21217만 독립 재계산: 원천 0/음수/86400초 초과는 결측, 양수는 float32 보존."""
    frame = frame.rename(columns={"출발_정류장_순서": "출발_정류장_순번"})
    result = pd.DataFrame(index=frame.index)
    result["service_date"] = pd.to_datetime(
        frame["기준_날짜"].str.replace(r"\.0$", "", regex=True), format="%Y%m%d", errors="coerce"
    )
    for source, target in [("노선_ID", KEY[0]), ("출발_정류장_ID", KEY[1]), ("도착_정류장_ID", KEY[2])]:
        value = frame[source].str.strip().str.replace(r"\.0$", "", regex=True)
        result[target] = value.where(value.str.fullmatch(r"\d+", na=False))
    seq = pd.to_numeric(frame["출발_정류장_순번"], errors="coerce")
    result[KEY[3]] = seq.where(seq.ge(0) & seq.mod(1).eq(0)).astype("Int32")
    zero = 0
    for hour in range(24):
        cols = [c for c in frame if re.fullmatch("운행시간_0?" + str(hour) + "시", c)]
        if len(cols) != 1:
            raise ValueError("HOURLY_COLUMN_AMBIGUOUS")
        numbers = pd.to_numeric(frame[cols[0]], errors="coerce")
        zero += int(numbers.eq(0).sum())
        result[HOURS[hour]] = numbers.where(numbers.gt(0) & numbers.le(86400)).astype("float32")
    valid = result[["service_date", *KEY]].notna().all(axis=1)
    return result.loc[valid], zero


def main():
    """전 기간 하류 비교와 원본 표본 재계산을 분리해 PASS 범위를 과장하지 않는다."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--home", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--reuse-days", help="같은 검사 소스로 완료한 하류 비교 결과 폴더; 원본 표본은 다시 검사")
    args = parser.parse_args()
    home, out = Path(args.home), Path(args.output)
    out.mkdir(parents=True, exist_ok=False)
    run = home / "j-moo/experiments/runs/20260918-v13-static-cpu"
    old = home / "j-moo/experiments/runs/20260908-v12"
    norm = home / "data/normalized/20260908-v12"
    usage = json.loads((run / "source_usage.json").read_text())
    manifest = json.loads((old / "raw_manifest.json").read_text())
    quality = json.loads((old / "bus_daily_quality.json").read_text())
    fraction = min(1, 100000000 / sum(r["valid_target_cells"] for r in quality))
    write(
        out / "preserved_before.json",
        {p.name: info(p) for p in run.iterdir() if p.is_file() and p.suffix != ".parquet"},
    )
    write(
        out / "metadata.json",
        dict(
            audited_at=datetime.now(UTC).isoformat(),
            fraction=fraction,
            split=usage["split"],
            input_version="20260908-v12",
            model_version=run.name,
            scripts=[
                info(home / "j-moo/experiments/runtime" / name)
                for name in ["ddubi_rebuild_v12.py", "ddubi_train_v12.py"]
            ],
            manifests=[
                info(old / name)
                for name in ["raw_manifest.json", "normalization_report.json", "bus_daily_quality.json"]
            ],
            aggregation="Provider hourly mean; exact duplicate collapse, conflicting wide keys excluded; no local averaging",
            provider_vehicle_sample_count="NOT_AVAILABLE_IN_SOURCE",
        ),
    )

    def day_check(record):
        path = Path(record["path"])
        day = path.stem
        daily_path = norm / "bus_daily" / path.name
        daily = pd.read_parquet(daily_path)
        hashed = pd.util.hash_pandas_object(daily[["service_date", *KEY]], index=False).to_numpy(dtype="uint64")
        sampled = daily.loc[(hashed % np.uint64(10000000)) < int(fraction * 10000000)]
        expected = sampled.melt(
            id_vars=["service_date", *KEY], value_vars=HOURS, var_name="hour_slot", value_name="target_seconds"
        ).dropna(subset=["target_seconds"])
        expected["hour_slot"] = expected.hour_slot.str[1:].astype("int8")
        actual = pd.read_parquet(path, columns=["service_date", *KEY, "hour_slot", "target_seconds"])
        result = compare(expected, actual, ["service_date", *KEY, "hour_slot"], ["target_seconds"])
        return dict(
            date=day,
            feature=info(path),
            daily=info(daily_path),
            sha_matches_training=sha(path) == record["sha256"],
            zero_cells=int(daily[HOURS].eq(0).sum().sum()),
            valid_cells=int(daily[HOURS].notna().sum().sum()),
            nonpositive_targets=int(actual.target_seconds.le(0).sum()),
            comparison=result,
        )

    records = []
    if args.reuse_days:
        previous = Path(args.reuse_days) / "days.jsonl"
        records = [json.loads(line) for line in previous.read_text().splitlines()]
        if len(records) != len(usage["files"]):
            raise ValueError("INCOMPLETE_PREVIOUS_DAYS")
        shutil.copyfile(previous, out / "days.jsonl")
        write(out / "reused_days.json", info(previous))
    else:
        with ThreadPoolExecutor(max_workers=4) as pool:
            for result in pool.map(day_check, usage["files"]):
                records.append(result)
                with (out / "days.jsonl").open("a", encoding="utf-8") as stream:
                    stream.write(json.dumps(result, default=str) + "\n")
                if len(records) % 50 == 0:
                    print("DAYS", len(records), flush=True)
    days = sorted(r["date"] for r in records)
    dates = pd.to_datetime(days)
    gaps = sorted(set(pd.date_range(dates.min(), dates.max())) - set(dates))
    selected = {days[0], days[-1]}
    selected.update(next(d for d in days if pd.Timestamp(d).dayofweek == weekday) for weekday in (0, 5, 6))
    # 부족 관측 후보는 유효 셀이 가장 적은 날짜다. 원본 0 비율은 재계산 후 별도 보고한다.
    selected.update(r["date"] for r in sorted(records, key=lambda r: r["valid_cells"])[:2])
    for gap in gaps[:2]:
        selected.update(
            d.strftime("%Y-%m-%d")
            for d in (gap - pd.Timedelta(days=1), gap + pd.Timedelta(days=1))
            if d.strftime("%Y-%m-%d") in days
        )
    write(
        out / "sample_selection.json",
        dict(
            dates=sorted(selected),
            missing_calendar_dates=[d.strftime("%Y-%m-%d") for d in gaps],
            gap_cause="Absent normalized date; supplier absence not independently certified",
        ),
    )
    # 대상 월 ZIP 전부를 확인해 겹치는 주간 원본을 누락하지 않는다.
    months = {d[:7].replace("-", "") for d in selected}
    tasks = [
        t
        for t in manifest["tasks"]
        if t[1].startswith("OA-21217/") and any(m in re.sub(r"\D", "", Path(t[0]).name) for m in months)
    ]
    raw_parts = []
    raw_stats = []
    raw_files = []
    for task in tasks:
        # 과거 manifest의 마지막 값은 파일 내용 SHA가 아니라 크기/mtime 기반 캐시 서명이다.
        path = Path(task[0])
        raw_files.append(dict(**info(path), recorded_metadata_signature=task[-1], historical_content_sha256=None))
        if path.suffix.lower() != ".zip":
            raise ValueError("SAMPLE_REQUIRES_NONZIP_READER:" + str(path))
        with zipfile.ZipFile(path) as archive:
            for entry in archive.infolist():
                if not entry.filename.lower().endswith(".csv"):
                    continue
                with archive.open(entry) as stream:
                    prefix = stream.read(65536)
                encoding = "utf-8-sig"
                try:
                    prefix.decode(encoding)
                except UnicodeDecodeError:
                    encoding = "cp949"
                with archive.open(entry) as stream:
                    for frame in pd.read_csv(stream, encoding=encoding, dtype="string", chunksize=20000):
                        frame.columns = [str(c).strip().strip('"').lstrip("\ufeff") for c in frame.columns]
                        # 실제 배포본에서 확인된 두 헤더 손상만 제한적으로 복원한다.
                        if frame.columns[0] == '?"기준_날짜':
                            frame = frame.rename(columns={'?"기준_날짜': "기준_날짜"})
                        if (
                            path.name == "tpss_route_section_speedh_202210.zip"
                            and entry.filename == "tpss_route_section_speedh_2022.10.24-10.30.csv"
                            and len(frame.columns) == 31
                            and "기준_날짜" not in frame
                        ):
                            frame.columns = [
                                "기준_날짜",
                                "노선_ID",
                                "출발_정류장_ID",
                                "도착_정류장_ID",
                                "운행시간",
                                *[f"운행시간_{h:02d}시" for h in range(24)],
                                "출발_정류장_순서",
                                "도착_정류장_순서",
                            ]
                        date = pd.to_datetime(
                            frame["기준_날짜"].str.replace(r"\.0$", "", regex=True), format="%Y%m%d", errors="coerce"
                        ).dt.strftime("%Y-%m-%d")
                        frame = frame.loc[date.isin(selected)]
                        if frame.empty:
                            continue
                        for day, group in frame.groupby(date.loc[frame.index]):
                            converted, zeros = normalize_raw(group)
                            raw_parts.append(converted)
                            raw_stats.append(
                                dict(
                                    date=day, source=str(path), entry=entry.filename, rows=len(group), zero_cells=zeros
                                )
                            )
        print("RAW", path.name, flush=True)
    raw = pd.concat(raw_parts, ignore_index=True) if raw_parts else pd.DataFrame()
    samples = []
    for day in sorted(selected):
        wide = raw.loc[raw.service_date.eq(pd.Timestamp(day))]
        dedup = wide.drop_duplicates(["service_date", *KEY, *HOURS])
        conflicts = dedup.duplicated(["service_date", *KEY], keep=False)
        expected = dedup.loc[~conflicts]
        actual = pd.read_parquet(norm / "bus_daily" / (day + ".parquet"))
        stats = [r for r in raw_stats if r["date"] == day]
        samples.append(
            dict(
                date=day,
                raw_rows=len(wide),
                raw_zero_cells=sum(r["zero_cells"] for r in stats),
                valid_cells=int(expected[HOURS].notna().sum().sum()),
                conflicting_rows=int(conflicts.sum()),
                expected_mean=float(expected[HOURS].stack().astype("float64").mean()),
                actual_mean=float(actual[HOURS].stack().astype("float64").mean()),
                comparison=compare(expected, actual, ["service_date", *KEY], HOURS),
            )
        )
    write(out / "raw_files.json", raw_files)
    write(out / "raw_cells.json", raw_stats)
    write(out / "samples.json", samples)
    failures = [
        r["date"]
        for r in records
        if not r["comparison"]["equal"] or not r["sha_matches_training"] or r["zero_cells"] or r["nonpositive_targets"]
    ]
    failures += [r["date"] for r in samples if not r["comparison"]["equal"]]
    before = json.loads((out / "preserved_before.json").read_text())
    unchanged = all(sha(Path(v["path"])) == v["sha256"] for v in before.values())
    summary = dict(
        status="FAIL" if failures else "PARTIAL",
        full_downstream_days=len(records),
        raw_sample_days=len(samples),
        affected_dates=sorted(set(failures)),
        model_preserved=unchanged,
        full_raw_audit=False,
        limitation="Raw ZIP coverage is sampled; provider underlying traversal sample counts unavailable",
    )
    write(out / "summary.json", summary)
    print(json.dumps(summary), flush=True)


if __name__ == "__main__":
    main()
