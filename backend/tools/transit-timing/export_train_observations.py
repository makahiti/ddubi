"""아카이브 원본에서 열차 Crosswalk 검증용 도착·위치 관측을 내보낸다.

입력은 Archiver가 ``transport.raw/date=.../hour=.../*.parquet``에 저장한 Kafka
봉투다. 도착 시각은 제공자 생성시각에 남은 도착 초를 더해 계산한다. 위치 이벤트는
의미를 검증한 상태 코드(기본값 ``1`` = 역 도착)만 사용한다. 수집시각을 열차의 역
시각으로 대신 사용하지 않는다.
"""

from __future__ import annotations

import argparse
import collections
import datetime as dt
import json
import re
from pathlib import Path

SCHEMA_VERSION = "subway-train-observations-v1"
KST = dt.timezone(dt.timedelta(hours=9), name="Asia/Seoul")
PROVIDER_ID = re.compile(r"^[0-9A-Za-z_-]{1,60}$")
EVENT_TYPES = {"subway.arrival", "subway.position"}
DEFAULT_POSITION_AT_STATION_CODES = {"1"}


def _text(value):
    return str(value).strip() if value is not None and str(value).strip() else None


def _first(mapping, *names):
    if not isinstance(mapping, dict):
        return None
    for name in names:
        value = _text(mapping.get(name))
        if value is not None:
            return value
    return None


def _time(value):
    text = _text(value)
    if text is None:
        return None
    try:
        parsed = dt.datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed.replace(tzinfo=KST) if parsed.tzinfo is None else parsed


def _integer(value):
    if isinstance(value, bool):
        return None
    try:
        result = int(str(value).strip())
    except (TypeError, ValueError):
        return None
    return result


def _provider_time(event, raw):
    """원본 제공시각만 반환한다. Collector 수신시각 fallback은 증거로 쓰지 않는다."""
    payload = event.get("payload")
    if not isinstance(payload, dict) or payload.get("timestampBasis") != "provider":
        return None
    return _time(_first(raw, "recptnDt", "RECPTN_DT")) or _time(event.get("occurredAt"))


def normalize_event(event, position_at_station_codes=None):
    """Kafka 봉투 하나를 관측 하나로 바꾸고, 제외 시에는 이유를 반환한다."""
    codes = position_at_station_codes or DEFAULT_POSITION_AT_STATION_CODES
    if not isinstance(event, dict):
        return None, "EVENT_NOT_OBJECT"
    event_type = event.get("eventType")
    if event_type not in EVENT_TYPES:
        return None, "EVENT_TYPE_IGNORED"
    payload = event.get("payload")
    raw = payload.get("raw") if isinstance(payload, dict) else None
    if not isinstance(raw, dict):
        return None, "RAW_PAYLOAD_REQUIRED"

    event_id = _text(event.get("eventId"))
    line_id = _first(payload, "providerLineId")
    train_id = _first(payload, "providerTrainId")
    station_id = _first(payload, "providerStationId")
    if event_id is None:
        return None, "EVENT_ID_REQUIRED"
    if not all(value and PROVIDER_ID.fullmatch(value) for value in (line_id, train_id, station_id)):
        return None, "PROVIDER_ID_INVALID"

    source_time = _provider_time(event, raw)
    if source_time is None:
        return None, "PROVIDER_TIME_REQUIRED"
    if event_type == "subway.arrival":
        arrival_seconds = _integer(_first(raw, "barvlDt", "BARVL_DT"))
        if arrival_seconds is None or arrival_seconds < 0:
            return None, "ARRIVAL_SECONDS_INVALID"
        matched_at = source_time + dt.timedelta(seconds=arrival_seconds)
        direction = _first(raw, "updnLine", "UPDN_LINE")
        destination = _first(raw, "bstatnNm", "BSTATN_NM")
        train_type = _first(raw, "btrainStt", "BTRAIN_STT")
        if not all((direction, destination, train_type)):
            return None, "ARRIVAL_IDENTITY_REQUIRED"
    else:
        status = _first(raw, "trainSttus", "TRAIN_STTUS")
        if status not in codes:
            return None, "POSITION_STATUS_NOT_AT_STATION"
        matched_at = source_time
        direction = _first(raw, "updnLine", "UPDN_LINE")
        destination = _first(raw, "statnTnm", "STATN_TNM")
        train_type = None
        if not all((direction, destination)):
            return None, "POSITION_IDENTITY_REQUIRED"

    observation = {
        "eventType": event_type,
        "eventId": event_id,
        "providerLineId": line_id,
        "providerTrainId": train_id,
        "providerStationId": station_id,
        "matchedAt": matched_at.astimezone(KST).isoformat(),
        "direction": direction,
        "destination": destination,
    }
    if event_type == "subway.arrival":
        observation["trainType"] = train_type
    return observation, None


def build_observations(events, service_date, position_at_station_codes=None):
    """운행일 00:00~다음날 06:00의 유효 관측과 품질 보고서를 만든다."""
    if not isinstance(service_date, dt.date):
        raise TypeError("service_date must be datetime.date")
    valid_from = dt.datetime.combine(service_date, dt.time(), KST)
    valid_until = valid_from + dt.timedelta(days=1, hours=6)
    reasons = collections.Counter()
    accepted_by_id = {}
    fingerprints = {}
    conflicted = set()
    input_events = 0
    subway_events = 0
    ignored_events = 0

    for event in events:
        input_events += 1
        observation, reason = normalize_event(event, position_at_station_codes)
        if reason == "EVENT_TYPE_IGNORED":
            ignored_events += 1
            continue
        subway_events += 1
        if reason:
            reasons[reason] += 1
            continue
        matched_at = _time(observation["matchedAt"])
        if matched_at is None or not valid_from <= matched_at < valid_until:
            reasons["OUTSIDE_SERVICE_WINDOW"] += 1
            continue
        event_id = observation["eventId"]
        fingerprint = json.dumps(observation, ensure_ascii=False, sort_keys=True)
        if event_id in conflicted:
            reasons["EVENT_ID_CONFLICT"] += 1
            continue
        if event_id in fingerprints:
            if fingerprints[event_id] == fingerprint:
                reasons["DUPLICATE_EVENT_ID"] += 1
            else:
                reasons["EVENT_ID_CONFLICT"] += 2
                conflicted.add(event_id)
                accepted_by_id.pop(event_id, None)
            continue
        fingerprints[event_id] = fingerprint
        accepted_by_id[event_id] = observation

    observations = sorted(
        accepted_by_id.values(),
        key=lambda item: (item["matchedAt"], item["eventType"], item["eventId"]),
    )
    event_counts = collections.Counter(item["eventType"] for item in observations)
    train_groups = {(item["providerLineId"], item["providerTrainId"]) for item in observations}
    document = {"schemaVersion": SCHEMA_VERSION, "observations": observations}
    report = {
        "schemaVersion": SCHEMA_VERSION,
        "serviceDate": service_date.isoformat(),
        "validFrom": valid_from.isoformat(),
        "validUntil": valid_until.isoformat(),
        "inputEvents": input_events,
        "ignoredEvents": ignored_events,
        "subwayEvents": subway_events,
        "acceptedObservations": len(observations),
        "acceptedEventCounts": dict(sorted(event_counts.items())),
        "trainGroups": len(train_groups),
        "rejectedEvents": sum(reasons.values()),
        "rejectionCounts": dict(sorted(reasons.items())),
        "positionAtStationCodes": sorted(position_at_station_codes or DEFAULT_POSITION_AT_STATION_CODES),
    }
    return document, report


def archive_paths(archive_uri, service_date, hdfs_user=None):
    """운행일과 다음날의 transport.raw Parquet 경로를 결정한다."""
    import fsspec

    options = {"user": hdfs_user} if archive_uri.startswith("webhdfs://") and hdfs_user else {}
    fs, root = fsspec.core.url_to_fs(archive_uri, **options)
    root = root.rstrip("/")
    if root.endswith(".parquet"):
        paths = [root] if fs.exists(root) else []
    else:
        topic_root = root if root.endswith("/transport.raw") else root + "/transport.raw"
        dates = (service_date, service_date + dt.timedelta(days=1))
        paths = []
        for value in dates:
            paths.extend(fs.glob(f"{topic_root}/date={value.isoformat()}/hour=*/*.parquet"))
    return fs, sorted(set(paths))


def read_archive_events(archive_uri, service_date, hdfs_user=None):
    """Parquet의 원본 ``value`` JSON을 순차적으로 읽는다."""
    import pyarrow.parquet as pq

    fs, paths = archive_paths(archive_uri, service_date, hdfs_user)
    if not paths:
        raise FileNotFoundError("선택한 운행일의 transport.raw Parquet 파일이 없습니다")
    for path in paths:
        with fs.open(path, "rb") as stream:
            parquet = pq.ParquetFile(stream)
            for batch in parquet.iter_batches(columns=["value"]):
                for value in batch.column(0).to_pylist():
                    try:
                        yield json.loads(value)
                    except (TypeError, json.JSONDecodeError):
                        yield None


def _write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", required=True, help="아카이브 루트 URI, transport.raw 디렉터리 또는 Parquet")
    parser.add_argument("--service-date", type=dt.date.fromisoformat, required=True, help="운행일 YYYY-MM-DD")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--hdfs-user")
    parser.add_argument(
        "--position-at-station-code",
        action="append",
        dest="position_codes",
        help="역 도착으로 검증한 위치 상태 코드. 반복 지정 가능하며 기본값은 1",
    )
    args = parser.parse_args(argv)
    codes = set(args.position_codes or DEFAULT_POSITION_AT_STATION_CODES)
    events = read_archive_events(args.archive, args.service_date, args.hdfs_user)
    document, report = build_observations(events, args.service_date, codes)
    _write_json(args.output, document)
    report_path = args.output.with_suffix(".report.json")
    _write_json(report_path, report)
    print(json.dumps(report, ensure_ascii=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
