"""기존 v12 묶음과 공식 주간 ZIP으로 추론 참조 범위만 확장한다. 모델 재학습은 하지 않는다.

공식 4개 source의 ID/순번/시간대 의미를 보존하고, 기존 GPU 정규화와 겹치는 날짜를
전 행·전 열 비교한 경우에만 새 날짜를 채택한다. 기존 모델/특성/인코더는 그대로 복사한다.
원본의 0초 소요는 결측, 승객/운행 0은 유효하며 상충 관측은 전부 격리한다.
원본 경로와 SHA, 품질 건수, 이식 비교 근거를 provenance에 남긴다. DuckDB/GPU는 사용하지 않는다.
"""

import argparse
import hashlib
import json
import re
import shutil
import zipfile
from collections import defaultdict
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pandas as pd

HOURS = [f"h{i:02d}" for i in range(24)]
CAT = ["provider_route_id", "provider_from_stop_id", "provider_to_stop_id"]
KEY = CAT + ["from_stop_sequence"]
SOURCES = ["OA-21217", "OA-21218", "OA-21219", "OA-21220"]


def normalize(frame, source):
    """공식 열명만 허용한다. 잘못된 키는 별도 반환하고 원본 행의 ID·선행 0을 보존한다."""
    frame = frame.rename(columns={"출발_정류장_순서": "출발_정류장_순번"})
    result = pd.DataFrame(index=frame.index)
    result["service_date"] = pd.to_datetime(
        frame["기준_날짜"].str.replace(r"\.0$", "", regex=True), format="%Y%m%d", errors="coerce"
    )
    mapping = {"노선_ID": CAT[0]}
    if source in SOURCES[:2]:
        mapping.update({"출발_정류장_ID": CAT[1], "도착_정류장_ID": CAT[2]})
    else:
        mapping["정류장_ID"] = CAT[1]
    for column, target in mapping.items():
        value = frame[column].astype("string").str.strip().str.replace(r"\.0$", "", regex=True)
        result[target] = value.where(value.str.fullmatch(r"\d+", na=False))
    keys = KEY if source in SOURCES[:2] else CAT[:2]
    if source in SOURCES[:2]:
        seq = pd.to_numeric(frame["출발_정류장_순번" if source == SOURCES[0] else "정류장_순서"], errors="coerce")
        result["from_stop_sequence"] = seq.where(seq.ge(0) & seq.mod(1).eq(0)).astype("Int32")
    prefix = {"OA-21217": "운행시간", "OA-21218": "재차인원합", "OA-21219": "승차_승객_수", "OA-21220": "버스운행횟수"}[
        source
    ]
    for hour, target in enumerate(HOURS):
        columns = [c for c in frame if re.fullmatch(re.escape(prefix) + r"_0?" + str(hour) + r"시", c)]
        if len(columns) != 1:
            raise ValueError("source hourly schema mismatch: " + source)
        number = pd.to_numeric(frame[columns[0]], errors="coerce")
        valid = number.gt(0) & number.le(86400) if source == SOURCES[0] else number.ge(0) & np.isfinite(number)
        result[target] = number.where(valid).astype("float32")
    valid = result[["service_date", *keys]].notna().all(axis=1)
    return result.loc[valid], frame.loc[~valid], keys


def deduplicate(frame, keys):
    """같은 값의 중복은 접고 같은 관측 key의 서로 다른 값은 모두 격리한다. 평균으로 숨기지 않는다."""
    unique = frame.drop_duplicates(["service_date", *keys, *HOURS])
    conflict = unique.duplicated(["service_date", *keys], keep=False)
    return unique.loc[~conflict].reset_index(drop=True), unique.loc[conflict]


def required_dates(start, end):
    """v12의 고정 77~126일 시차를 그대로 적용한다. 최신 관측을 임의로 과거 특성에 끼우지 않는다."""
    required = {s: set() for s in SOURCES}
    cursor = start
    while cursor <= end:
        for source, lags in [
            ("OA-21217", range(77, 127, 7)),
            ("OA-21218", range(77, 99, 7)),
            ("OA-21219", [77]),
            ("OA-21220", [77]),
        ]:
            required[source].update(cursor - timedelta(days=n) for n in lags)
        cursor += timedelta(days=1)
    return required


def extend(base, raw_root, output, end, weeks):
    """기존 SHA를 검증하고 새 폴더에서 작업한다. 실패 시 기존 묶음/서빙 포인터는 바꾸지 않는다.

    weeks는 공식 주간 ZIP 파일명에 나타나는 기간 목록이다. 자동으로 모든 다운로드를
    합치지 않는다. 각 source에서 GPU 참조와 겹치는 1일 이상의 전 행 동등성이 필수다.
    """
    if output.exists():
        raise ValueError("destination already exists")
    metadata = json.loads((base / "bundle.json").read_text(encoding="utf-8"))
    start = date.fromisoformat(metadata["supported_from"])
    end = date.fromisoformat(end)
    if not 1 <= (end - start).days + 1 <= 31 or end <= date.fromisoformat(metadata["supported_until"]):
        raise ValueError("range must extend base within 31 days")
    for relative, expected in metadata["files"].items():
        path = (base / relative).resolve()
        if not path.is_relative_to(base.resolve()) or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ValueError("base manifest mismatch")
    shutil.copytree(base, output)
    needed = required_dates(start, end)
    report = {
        "baseSupportedUntil": metadata["supported_until"],
        "supportedUntil": str(end),
        "sources": [],
        "days": [],
        "productionReady": False,
    }
    for source in SOURCES:
        parts = defaultdict(list)
        parity = 0
        for week in weeks:
            paths = list((raw_root / source).glob("*" + week + ".zip"))
            if len(paths) != 1:
                raise ValueError("weekly source not unique: " + source + "/" + week)
            path = paths[0]
            report["sources"].append(
                {
                    "source": source,
                    "file": path.name,
                    "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                    "url": "https://data.seoul.go.kr/dataList/" + source + "/S/1/datasetView.do",
                }
            )
            with zipfile.ZipFile(path) as archive:
                for entry in archive.namelist():
                    if not entry.lower().endswith(".csv"):
                        continue
                    with archive.open(entry) as stream:
                        for raw in pd.read_csv(stream, encoding="cp949", dtype="string", chunksize=20000):
                            clean, bad, keys = normalize(raw, source)
                            if not bad.empty:
                                raise ValueError("invalid provider keys require quarantine review")
                            for day, rows in clean.groupby("service_date"):
                                if day.date() in needed[source]:
                                    parts[str(day.date())].append(rows)
        for day, frames in sorted(parts.items()):
            frame, conflict = deduplicate(pd.concat(frames, ignore_index=True), keys)
            relative = (
                ("references/bus_daily/" if source == SOURCES[0] else "references/aux_daily/" + source + "/")
                + day
                + ".parquet"
            )
            path = output / relative
            if not conflict.empty:
                dest = output / "quarantine" / source / (day + ".parquet")
                dest.parent.mkdir(parents=True, exist_ok=True)
                conflict.to_parquet(dest, index=False)
            if path.exists():
                original = pd.read_parquet(path)
                columns = original.columns.tolist()
                pd.testing.assert_frame_equal(
                    original.sort_values(keys).reset_index(drop=True),
                    frame[columns].sort_values(keys).reset_index(drop=True),
                    check_dtype=False,
                    check_exact=True,
                )
                parity += 1
                state = "GPU_REFERENCE_PARITY"
            else:
                path.parent.mkdir(parents=True, exist_ok=True)
                frame.to_parquet(path, index=False, compression="zstd")
                metadata["files"][relative] = hashlib.sha256(path.read_bytes()).hexdigest()
                state = "ADDED"
            report["days"].append(
                {"source": source, "date": day, "rows": len(frame), "conflictRows": len(conflict), "status": state}
            )
        if parity < 1:
            raise ValueError("no overlapping GPU reference validation: " + source)
    # 범위 문구만 늘리지 않는다. 새 결측은 해당 날짜를 포함하는 공식 주간 ZIP을 전부
    # 정상 파싱한 경우만 기록한다. 파일 부재를 0행 정상 관측이나 평균값으로 만들지 않는다.
    missing = set(metadata.get("missing_files", []))
    covered = set()
    for week in weeks:
        match = re.fullmatch(r"(\d{4})\.(\d{2})\.(\d{2})-(\d{2})\.(\d{2})", week)
        if not match:
            raise ValueError("invalid official weekly date interval")
        year, month, day, end_month, end_day = map(int, match.groups())
        cursor = date(year, month, day)
        last = date(year, end_month, end_day)
        if (last - cursor).days != 6:
            raise ValueError("expected 7-day official file")
        while cursor <= last:
            covered.add(cursor)
            cursor += timedelta(days=1)
    report["newMissingFiles"] = []
    for source, days in needed.items():
        for day in days:
            relative = ("bus_daily/" if source == SOURCES[0] else "aux_daily/" + source + "/") + str(day) + ".parquet"
            if not (output / "references" / relative).exists() and relative not in missing:
                if day not in covered:
                    raise ValueError("new required reference not inspected: " + relative)
                missing.add(relative)
                report["newMissingFiles"].append(relative)
    metadata["supported_until"] = str(end)
    metadata["missing_files"] = sorted(missing)
    (output / "bundle.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / "reference-update-provenance.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(
        json.dumps(
            {
                "bundle": str(output),
                "verifiedOverlapDays": sum(d["status"] == "GPU_REFERENCE_PARITY" for d in report["days"]),
                "newDailyFiles": sum(d["status"] == "ADDED" for d in report["days"]),
                "newMissingFiles": report["newMissingFiles"],
                "supportedUntil": str(end),
            }
        )
    )


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    for name in ("base", "raw-root", "output"):
        p.add_argument("--" + name, type=Path, required=True)
    p.add_argument("--end", required=True)
    p.add_argument("--weeks", nargs="+", required=True)
    a = p.parse_args()
    extend(a.base, a.raw_root, a.output, a.end, a.weeks)
