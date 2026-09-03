"""외부 학습 원천 파일을 감사하고 버스 통과 이벤트를 생성한다.

대용량 원본은 저장소에 넣지 않는다. 이 모듈은 로컬/GPU 서버에 별도로
보관한 CSV를 스트리밍 방식으로 읽어 스키마와 기간을 검사하고, 조인 가능한
버스 이력 쌍에서 동일 차량의 연속 정류장 통과시간을 만든다.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Iterable, Iterator


BUS_JOIN_COLUMNS = ("기준일", "노선ID", "운행순번", "운행계획ID")
KST = timezone(timedelta(hours=9), name="Asia/Seoul")


@dataclass(frozen=True)
class SourceDefinition:
    name: str
    required_columns: tuple[str, ...]
    date_column: str
    distinct_columns: tuple[str, ...] = ()


SOURCE_DEFINITIONS = {
    "traffic_hourly": SourceDefinition(
        name="traffic_hourly",
        required_columns=("기준일", "시간코드", "링크ID", "평균속도"),
        date_column="기준일",
        distinct_columns=("링크ID",),
    ),
    "bus_stop_history": SourceDefinition(
        name="bus_stop_history",
        required_columns=(
            "기준일",
            "노선ID",
            "운행순번",
            "정류장순번",
            "운행계획ID",
            "도착일시",
            "출발일시",
            "정류장ID",
        ),
        date_column="기준일",
        distinct_columns=("노선ID", "정류장ID"),
    ),
    "bus_run_history": SourceDefinition(
        name="bus_run_history",
        required_columns=(
            "기준일",
            "노선ID",
            "운행순번",
            "운행계획ID",
            "버스ID",
            "출발일시",
            "도착일시",
        ),
        date_column="기준일",
        distinct_columns=("노선ID", "버스ID"),
    ),
    "incident_112": SourceDefinition(
        name="incident_112",
        required_columns=("사고일", "사고시간", "사고종류", "위도", "경도"),
        date_column="사고일",
        distinct_columns=("사고종류",),
    ),
}


def clean(value: str | None) -> str:
    """T-DATA가 각 셀 앞에 붙이는 BOM과 주변 공백을 제거한다."""
    return (value or "").lstrip("\ufeff").strip()


def normalise_date(value: str) -> str | None:
    digits = "".join(ch for ch in clean(value) if ch.isdigit())
    if len(digits) < 8:
        return None
    candidate = digits[:8]
    try:
        return datetime.strptime(candidate, "%Y%m%d").date().isoformat()
    except ValueError:
        return None


def detect_encoding(path: Path) -> str:
    for encoding in ("utf-8-sig", "cp949"):
        try:
            # 바이트를 임의 위치에서 자르면 UTF-8 다중 바이트 문자의 중간에서
            # 끊길 수 있다. TextIOWrapper가 문자 경계를 보존하도록 맡긴다.
            with path.open("r", encoding=encoding, newline="") as stream:
                stream.read(65536)
            return encoding
        except UnicodeDecodeError:
            continue
    raise ValueError(f"지원하지 않는 CSV 인코딩: {path}")


def csv_rows(path: Path, encoding: str | None = None) -> Iterator[dict[str, str]]:
    chosen = encoding or detect_encoding(path)
    with path.open("r", encoding=chosen, newline="") as stream:
        reader = csv.DictReader(stream)
        if reader.fieldnames is None:
            raise ValueError(f"CSV 헤더가 없습니다: {path}")
        reader.fieldnames = [clean(name) for name in reader.fieldnames]
        for row in reader:
            yield {clean(key): clean(value) for key, value in row.items() if key is not None}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def audit_source(path: Path, definition: SourceDefinition) -> dict[str, object]:
    encoding = detect_encoding(path)
    rows = csv_rows(path, encoding)
    row_count = 0
    invalid_date_rows = 0
    dates: set[str] = set()
    distinct = {column: set() for column in definition.distinct_columns}
    header: list[str] = []

    for row in rows:
        if not header:
            header = list(row)
        row_count += 1
        date_value = normalise_date(row.get(definition.date_column, ""))
        if date_value is None:
            invalid_date_rows += 1
        else:
            dates.add(date_value)
        for column in definition.distinct_columns:
            if value := clean(row.get(column)):
                distinct[column].add(value)

    missing = sorted(set(definition.required_columns) - set(header))
    return {
        "source": definition.name,
        "file_name": path.name,
        "size_bytes": path.stat().st_size,
        "sha256": sha256(path),
        "encoding": encoding,
        "row_count": row_count,
        "columns": header,
        "missing_required_columns": missing,
        "invalid_date_rows": invalid_date_rows,
        "date_min": min(dates) if dates else None,
        "date_max": max(dates) if dates else None,
        "unique_date_count": len(dates),
        "distinct_counts": {column: len(values) for column, values in distinct.items()},
        "schema_ok": not missing,
    }


def bus_join_key(row: dict[str, str]) -> tuple[str, str, str, str]:
    return tuple(clean(row.get(column)) for column in BUS_JOIN_COLUMNS)  # type: ignore[return-value]


def audit_bus_join(stop_path: Path, run_path: Path) -> dict[str, object]:
    run_index: dict[tuple[str, str, str, str], set[str]] = {}
    run_rows = 0
    missing_vehicle_rows = 0
    for row in csv_rows(run_path):
        run_rows += 1
        vehicle_id = clean(row.get("버스ID"))
        if not vehicle_id:
            missing_vehicle_rows += 1
        run_index.setdefault(bus_join_key(row), set()).add(vehicle_id)

    duplicate_run_keys = sum(1 for vehicles in run_index.values() if len(vehicles) > 1)
    stop_rows = 0
    joinable_stop_rows = 0
    stop_dates: set[str] = set()
    run_dates = {key[0] for key in run_index if key[0]}
    timestamp_rows = 0
    arrival_minute_aligned_rows = 0
    dwell_rows = 0
    dwell_zero_or_twenty_rows = 0
    for row in csv_rows(stop_path):
        stop_rows += 1
        key = bus_join_key(row)
        if key[0]:
            stop_dates.add(key[0])
        vehicles = run_index.get(key, set())
        if len(vehicles) == 1 and next(iter(vehicles), ""):
            joinable_stop_rows += 1
        arrival = parse_timestamp(row.get("도착일시", ""))
        departure = parse_timestamp(row.get("출발일시", ""))
        if arrival is not None:
            timestamp_rows += 1
            if arrival.second == 0:
                arrival_minute_aligned_rows += 1
        if arrival is not None and departure is not None:
            dwell_seconds = int((departure - arrival).total_seconds())
            if dwell_seconds >= 0:
                dwell_rows += 1
                if dwell_seconds in (0, 20):
                    dwell_zero_or_twenty_rows += 1

    overlapping_dates = sorted(stop_dates & run_dates)
    arrival_minute_aligned_ratio = (
        arrival_minute_aligned_rows / timestamp_rows if timestamp_rows else None
    )
    dwell_zero_or_twenty_ratio = (
        dwell_zero_or_twenty_rows / dwell_rows if dwell_rows else None
    )
    join_ready = (
        bool(overlapping_dates)
        and duplicate_run_keys == 0
        and missing_vehicle_rows == 0
        and joinable_stop_rows > 0
    )
    schedule_like_timing_signal = bool(
        arrival_minute_aligned_ratio is not None
        and dwell_zero_or_twenty_ratio is not None
        and arrival_minute_aligned_ratio >= 0.95
        and dwell_zero_or_twenty_ratio >= 0.95
    )
    return {
        "join_columns": list(BUS_JOIN_COLUMNS),
        "stop_rows": stop_rows,
        "run_rows": run_rows,
        "stop_dates": sorted(stop_dates),
        "run_dates": sorted(run_dates),
        "overlapping_dates": overlapping_dates,
        "run_key_count": len(run_index),
        "duplicate_run_keys": duplicate_run_keys,
        "missing_vehicle_rows": missing_vehicle_rows,
        "joinable_stop_rows": joinable_stop_rows,
        "unmatched_stop_rows": stop_rows - joinable_stop_rows,
        "arrival_minute_aligned_ratio": arrival_minute_aligned_ratio,
        "dwell_zero_or_twenty_ratio": dwell_zero_or_twenty_ratio,
        "schedule_like_timing_signal": schedule_like_timing_signal,
        "join_ready": join_ready,
        "candidate_pass_event_ready": join_ready,
        "training_label_ready": join_ready and not schedule_like_timing_signal,
    }


def parse_timestamp(value: str) -> datetime | None:
    cleaned = clean(value)
    if not cleaned:
        return None
    try:
        return datetime.strptime(cleaned, "%Y%m%d%H%M%S")
    except ValueError:
        return None


def build_pass_events(stop_path: Path, run_path: Path) -> list[dict[str, object]]:
    """같은 차량·운행의 연속 정류장 사이 provider 시간 후보를 만든다."""
    run_index: dict[tuple[str, str, str, str], str] = {}
    ambiguous: set[tuple[str, str, str, str]] = set()
    for row in csv_rows(run_path):
        key = bus_join_key(row)
        vehicle_id = clean(row.get("버스ID"))
        if not vehicle_id:
            continue
        previous = run_index.get(key)
        if previous and previous != vehicle_id:
            ambiguous.add(key)
        else:
            run_index[key] = vehicle_id
    for key in ambiguous:
        run_index.pop(key, None)

    grouped: dict[tuple[str, str, str, str], list[dict[str, str]]] = {}
    for row in csv_rows(stop_path):
        key = bus_join_key(row)
        if key in run_index:
            grouped.setdefault(key, []).append(row)

    events: list[dict[str, object]] = []
    for key, stops in grouped.items():
        stops.sort(key=lambda row: int(clean(row.get("정류장순번")) or -1))
        for start, end in zip(stops, stops[1:]):
            from_seq = int(clean(start.get("정류장순번")) or -1)
            to_seq = int(clean(end.get("정류장순번")) or -1)
            if to_seq != from_seq + 1:
                continue
            from_time = parse_timestamp(start.get("출발일시", "")) or parse_timestamp(
                start.get("도착일시", "")
            )
            to_time = parse_timestamp(end.get("도착일시", "")) or parse_timestamp(
                end.get("출발일시", "")
            )
            if from_time is None or to_time is None:
                continue
            travel_seconds = int((to_time - from_time).total_seconds())
            if not 0 < travel_seconds <= 7200:
                continue
            events.append(
                {
                    "basis_date": normalise_date(key[0]),
                    "route_id": key[1],
                    "run_sequence": key[2],
                    "operation_plan_id": key[3],
                    "vehicle_id": run_index[key],
                    "from_stop_sequence": from_seq,
                    "to_stop_sequence": to_seq,
                    "from_stop_id": clean(start.get("정류장ID")),
                    "to_stop_id": clean(end.get("정류장ID")),
                    "provider_from_timestamp": from_time.isoformat(),
                    "provider_to_timestamp": to_time.isoformat(),
                    "candidate_travel_time_seconds": travel_seconds,
                    "timing_semantics": "unverified",
                    "source": "tdata.bus-dispatch-history",
                }
            )
    return events


def write_pass_events(events: Iterable[dict[str, object]], output: Path) -> int:
    rows = list(events)
    if not rows:
        raise ValueError("생성 가능한 pass event가 없습니다. 두 버스 파일의 기준일과 조인 키를 확인하세요.")
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    return len(rows)


def parse_hourly_interval(basis_date: str, hour_code: str) -> tuple[datetime, datetime] | None:
    """T-DATA `01=00:00~01:00`, `24=23:00~익일00:00` 구간을 해석한다."""
    normalised = normalise_date(basis_date)
    digits = "".join(character for character in clean(hour_code) if character.isdigit())
    if normalised is None or not digits:
        return None
    hour = int(digits)
    if not 1 <= hour <= 24:
        return None
    day_start = datetime.strptime(normalised, "%Y-%m-%d").replace(tzinfo=KST)
    interval_end = day_start + timedelta(hours=hour)
    return interval_end - timedelta(hours=1), interval_end


def normalise_traffic_rows(path: Path) -> Iterator[dict[str, object]]:
    """도로 1시간 속도를 provider 링크 기준의 누수 방지용 레코드로 변환한다."""
    for row in csv_rows(path):
        interval = parse_hourly_interval(row.get("기준일", ""), row.get("시간코드", ""))
        link_id = clean(row.get("링크ID"))
        try:
            average_speed = float(clean(row.get("평균속도")))
        except ValueError:
            continue
        if interval is None or not link_id or average_speed < 0:
            continue
        interval_start, interval_end = interval
        yield {
            "provider_link_id": link_id,
            "segment_id": "",
            "interval_start": interval_start.isoformat(),
            "interval_end": interval_end.isoformat(),
            "available_at": "",
            "average_speed_kmh": average_speed,
            "road_name": clean(row.get("도로명")),
            "direction_code": clean(row.get("도로방향구분코드")),
            "direction_name": clean(row.get("도로방향구분명")),
            "from_node_name": clean(row.get("시작노드명")),
            "to_node_name": clean(row.get("종료노드명")),
            "source": "tdata.hourly-link-speed",
        }


def parse_incident_timestamp(date_value: str, time_value: str) -> datetime | None:
    date = normalise_date(date_value)
    if date is None:
        return None
    digits = "".join(character for character in clean(time_value) if character.isdigit())
    if len(digits) not in (4, 6):
        return None
    if len(digits) == 4:
        digits += "00"
    try:
        return datetime.strptime(f"{date}{digits}", "%Y-%m-%d%H%M%S").replace(tzinfo=KST)
    except ValueError:
        return None


def normalise_incident_rows(path: Path) -> Iterator[dict[str, object]]:
    """112 사고 후보를 좌표 기반 이벤트로 변환하되 알려지지 않은 공개시각은 비운다."""
    for row in csv_rows(path):
        occurred_at = parse_incident_timestamp(row.get("사고일", ""), row.get("사고시간", ""))
        try:
            latitude = float(clean(row.get("위도")))
            longitude = float(clean(row.get("경도")))
        except ValueError:
            continue
        if occurred_at is None or not (-90 <= latitude <= 90 and -180 <= longitude <= 180):
            continue
        yield {
            "provider_event_id": clean(row.get("연번")),
            "event_type": clean(row.get("사고종류")),
            "occurred_at": occurred_at.isoformat(),
            "available_at": "",
            "latitude": latitude,
            "longitude": longitude,
            "segment_id": "",
            "source": "tdata.acc-info-112",
        }


def write_rows(rows: Iterable[dict[str, object]], output: Path) -> int:
    """대용량 정규화 결과를 메모리에 모으지 않고 CSV로 쓴다."""
    iterator = iter(rows)
    try:
        first = next(iterator)
    except StopIteration as exc:
        raise ValueError("정규화 가능한 레코드가 없습니다") from exc
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(first))
        writer.writeheader()
        writer.writerow(first)
        count = 1
        for row in iterator:
            writer.writerow(row)
            count += 1
    return count


def summarise_pass_events(events: Iterable[dict[str, object]]) -> dict[str, object]:
    rows = list(events)
    durations = sorted(int(row["candidate_travel_time_seconds"]) for row in rows)
    if not durations:
        return {"row_count": 0}

    def percentile(fraction: float) -> int:
        index = round((len(durations) - 1) * fraction)
        return durations[index]

    return {
        "row_count": len(rows),
        "date_min": min(str(row["basis_date"]) for row in rows),
        "date_max": max(str(row["basis_date"]) for row in rows),
        "route_count": len({row["route_id"] for row in rows}),
        "vehicle_count": len({row["vehicle_id"] for row in rows}),
        "duration_seconds": {
            "min": durations[0],
            "p50": percentile(0.50),
            "p90": percentile(0.90),
            "p99": percentile(0.99),
            "max": durations[-1],
        },
        "whole_minute_ratio": sum(value % 60 == 0 for value in durations) / len(durations),
    }


def audit_all(paths: dict[str, Path]) -> dict[str, object]:
    sources = {
        name: audit_source(path, SOURCE_DEFINITIONS[name])
        for name, path in paths.items()
    }
    report: dict[str, object] = {"sources": sources}
    if "bus_stop_history" in paths and "bus_run_history" in paths:
        report["bus_join"] = audit_bus_join(
            paths["bus_stop_history"], paths["bus_run_history"]
        )
    return report


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--traffic-hourly", type=Path)
    parser.add_argument("--bus-stop-history", type=Path)
    parser.add_argument("--bus-run-history", type=Path)
    parser.add_argument("--incident-112", type=Path)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--pass-events-output", type=Path)
    parser.add_argument("--traffic-output", type=Path)
    parser.add_argument("--incident-output", type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    paths = {
        name: value
        for name, value in {
            "traffic_hourly": args.traffic_hourly,
            "bus_stop_history": args.bus_stop_history,
            "bus_run_history": args.bus_run_history,
            "incident_112": args.incident_112,
        }.items()
        if value is not None
    }
    if not paths:
        raise SystemExit("감사할 원천 파일을 하나 이상 지정하세요.")

    report = audit_all(paths)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    if args.pass_events_output:
        if "bus_stop_history" not in paths or "bus_run_history" not in paths:
            raise SystemExit("pass event 생성에는 버스 이력 파일 2개가 모두 필요합니다.")
        events = build_pass_events(paths["bus_stop_history"], paths["bus_run_history"])
        report["pass_events"] = summarise_pass_events(events)
        write_pass_events(events, args.pass_events_output)
        args.report.write_text(
            json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
        )
    if args.traffic_output:
        if "traffic_hourly" not in paths:
            raise SystemExit("traffic 정규화에는 --traffic-hourly가 필요합니다.")
        report["traffic_normalised_rows"] = write_rows(
            normalise_traffic_rows(paths["traffic_hourly"]), args.traffic_output
        )
    if args.incident_output:
        if "incident_112" not in paths:
            raise SystemExit("incident 정규화에는 --incident-112가 필요합니다.")
        report["incident_normalised_rows"] = write_rows(
            normalise_incident_rows(paths["incident_112"]), args.incident_output
        )
    if args.traffic_output or args.incident_output:
        args.report.write_text(
            json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
