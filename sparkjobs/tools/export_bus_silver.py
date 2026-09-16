"""Small JSONL review export, sharing the production normalizer. Not a bulk HDFS job."""
import argparse
import csv
import json
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from common.bus_normalizer import COMMON, POSITION, ARRIVAL, normalize


def export(lines, output):
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    entries = [(line.rstrip("\r\n"), normalize(line)) for line in lines if line.strip()]
    variants = {}
    for raw, row in entries:
        if row["status"] != "IGNORED" and row["eventId"]:
            variants.setdefault(row["eventId"], set()).add(raw)
    rows, seen, duplicates, ignored = [], set(), 0, 0
    duplicate_events = []
    for raw, row in entries:
        if row["status"] == "IGNORED":
            ignored += 1
            continue
        key = (row["eventId"], raw)
        if key in seen:
            duplicates += 1
            duplicate_events.append(raw)
            continue
        seen.add(key)
        if len(variants.get(row["eventId"], ())) > 1:
            row["status"] = "REJECTED"
            row["rejectionReasons"].append("EVENT_ID_CONTENT_CONFLICT")
        row["rawEventJson"] = raw
        rows.append(row)
    for name, kind, fields in (("position", "bus.position", POSITION), ("arrival", "bus.arrival", ARRIVAL), ("rejected", None, dict(POSITION, **ARRIVAL))):
        selected = [r for r in rows if (r["status"] == "REJECTED" if kind is None else r["status"] == "ACCEPTED" and r["eventType"] == kind)]
        columns = list(COMMON) + list(fields) + ["rawEventJson"]
        with (output / (name + ".jsonl")).open("w", encoding="utf-8") as file:
            for row in selected:
                file.write(json.dumps({k: row[k] for k in columns}, ensure_ascii=False) + "\n")
        with (output / (name + ".csv")).open("w", encoding="utf-8-sig", newline="") as file:
            writer = csv.DictWriter(file, fieldnames=columns)
            writer.writeheader()
            for row in selected:
                writer.writerow({k: "null" if row[k] is None else json.dumps(row[k], ensure_ascii=False) if isinstance(row[k], list) else row[k] for k in columns})
    (output / "duplicates.jsonl").write_text("".join(json.dumps({"rawEventJson": raw}, ensure_ascii=False) + "\n" for raw in duplicate_events), encoding="utf-8")
    report = dict(inputRows=len(entries), acceptedRows=sum(r["status"] == "ACCEPTED" for r in rows), rejectedRows=sum(r["status"] == "REJECTED" for r in rows), duplicateRows=duplicates, ignoredRows=ignored, missingFields=dict(Counter(f for r in rows for f in r["missingFields"])), qualityFlags=dict(Counter(f for r in rows for f in r["qualityFlags"])), inputVerification="UNVERIFIED_BY_EXPORTER")
    (output / "quality.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    with open(args.input, encoding="utf-8-sig") as file:
        print(json.dumps(export(file, args.output), ensure_ascii=False))
