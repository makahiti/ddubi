"""정적 시간표 운행과 실시간 열차 ID의 검증 가능한 Crosswalk를 만든다.

입력 관측의 ``matchedAt``은 해당 열차가 provider 역에 도착했거나 있었다고 판단한
절대시각이다. 도착 API는 ``occurredAt + arrivalSeconds``, 위치 API는 상태 코드를
해석해 선택한 역 시각을 상류 정규화 단계에서 넣어야 한다. 이 도구는 수집시각을 대신
사용하거나 정적 열차코드와 실시간 trainId가 같다고 가정하지 않는다.

노선, 역 순서, 시각 이동량과 도착/위치 양쪽의 일관된 식별값을 검사한다. 조건을 통과한
정적 후보가 정확히 하나일 때만 EXACT다. 후보가 둘 이상이면 AMBIGUOUS, 증거 부족이나
후보 없음은 UNMAPPED로 보존한다.
"""

import argparse
import collections
import datetime as dt
import hashlib
import json
import re
import statistics
from pathlib import Path

SCHEMA_VERSION = "subway-train-crosswalk-v1"
OBSERVATION_SCHEMA_VERSION = "subway-train-observations-v1"
PROVIDER_ID = re.compile(r"^[0-9A-Za-z_-]{1,60}$")
EVENT_TYPES = {"subway.arrival", "subway.position"}


def _time(value, field):
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{field.upper()}_REQUIRED")
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as invalid:
        raise ValueError(f"{field.upper()}_INVALID") from invalid
    if parsed.tzinfo is None:
        raise ValueError(f"{field.upper()}_OFFSET_REQUIRED")
    return parsed


def _text(value):
    return value.strip() if isinstance(value, str) and value.strip() else None


def _integer(value):
    return isinstance(value, int) and not isinstance(value, bool)


def _identity(records, field):
    values = [_text(record.get(field)) for record in records]
    if not values or any(value is None for value in values):
        return None
    values = set(values)
    return next(iter(values)) if len(values) == 1 else None


def _scheduled_at(call):
    value = call.get("arrivalAt") or call.get("departureAt")
    return _time(value, "scheduledAt") if value else None


def _best_alignment(trip, observations, max_offset_seconds, max_deviation_seconds):
    """관측 역 순서를 trip call에 맞추고 가장 일관된 시간 이동량을 반환한다."""
    calls = trip.get("calls") or []
    matches = []

    def visit(observation_index, previous_call, indices, offsets):
        if len(matches) > 4096:
            return
        if observation_index == len(observations):
            median = int(round(statistics.median(offsets)))
            deviations = [abs(value - median) for value in offsets]
            if max(deviations, default=0) <= max_deviation_seconds:
                matches.append((max(deviations, default=0), abs(median), median, tuple(indices)))
            return
        observation = observations[observation_index]
        observed_at = observation["_matchedAt"]
        station = observation["providerStationId"]
        for index in range(max(previous_call, 0), len(calls)):
            call = calls[index]
            if call.get("providerStationId") != station:
                continue
            if index == previous_call and indices and calls[indices[-1]].get("providerStationId") != station:
                continue
            scheduled_at = _scheduled_at(call)
            if scheduled_at is None:
                continue
            offset = int((observed_at - scheduled_at).total_seconds())
            if abs(offset) > max_offset_seconds:
                continue
            visit(observation_index + 1, index, indices + [index], offsets + [offset])

    visit(0, 0, [], [])
    return min(matches) if matches else None


def _group_identity(records):
    arrivals = [record for record in records if record["eventType"] == "subway.arrival"]
    positions = [record for record in records if record["eventType"] == "subway.position"]
    identity = {
        "arrivalDirection": _identity(arrivals, "direction"),
        "positionDirection": _identity(positions, "direction"),
        "arrivalDestination": _identity(arrivals, "destination"),
        "positionDestination": _identity(positions, "destination"),
        "trainType": _identity(arrivals, "trainType"),
    }
    if not arrivals or not positions:
        return identity, "ARRIVAL_AND_POSITION_EVIDENCE_REQUIRED"
    if any(value is None for value in identity.values()):
        return identity, "REALTIME_IDENTITY_MISSING_OR_CONFLICTING"
    return identity, None


def _entry(line_id, train_id, records, timetable, criteria):
    identity, identity_error = _group_identity(records)
    ordered = sorted(records, key=lambda record: (record["_matchedAt"], record["eventId"]))
    distinct_stations = len({record["providerStationId"] for record in ordered})
    candidates = []
    if identity_error is None and distinct_stations >= criteria["minDistinctStations"]:
        for trip in timetable["trips"]:
            if trip.get("providerLineId") != line_id:
                continue
            alignment = _best_alignment(
                trip,
                ordered,
                criteria["maxScheduleOffsetSeconds"],
                criteria["maxDeviationSeconds"],
            )
            if alignment:
                candidates.append((alignment, trip["key"]))
    candidates.sort(key=lambda candidate: (candidate[0], candidate[1]))
    if identity_error:
        status, reason = "UNMAPPED", identity_error
    elif distinct_stations < criteria["minDistinctStations"]:
        status, reason = "UNMAPPED", "INSUFFICIENT_DISTINCT_STATIONS"
    elif not candidates:
        status, reason = "UNMAPPED", "NO_SCHEDULE_CANDIDATE"
    elif len(candidates) > 1:
        status, reason = "AMBIGUOUS", "MULTIPLE_SCHEDULE_CANDIDATES"
    else:
        status, reason = "EXACT", "UNIQUE_SEQUENCE_AND_TIME_MATCH"
    exact = candidates[0] if status == "EXACT" else None
    return {
        "serviceDate": timetable["validFrom"][:10],
        "providerLineId": line_id,
        "providerTrainId": train_id,
        "staticTripKey": exact[1] if exact else None,
        "matchStatus": status,
        "reason": reason,
        **identity,
        "matchedStationCount": distinct_stations,
        "scheduleOffsetSeconds": exact[0][2] if exact else None,
        "maxScheduleDeviationSeconds": exact[0][0] if exact else None,
        "candidateTripKeys": [candidate[1] for candidate in candidates],
        "evidenceEventIds": sorted({record["eventId"] for record in records}),
        "validFrom": timetable["validFrom"],
        "validUntil": timetable["validUntil"],
    }


def build_crosswalk(
    timetable,
    observation_document,
    generated_at,
    *,
    min_distinct_stations=3,
    max_schedule_offset_seconds=1800,
    max_deviation_seconds=120,
    evidence_sha256="synthetic",
):
    """Crosswalk artifact와 사람이 검토할 요약 보고서를 반환한다."""
    if observation_document.get("schemaVersion") != OBSERVATION_SCHEMA_VERSION:
        raise ValueError("OBSERVATION_SCHEMA_VERSION_UNSUPPORTED")
    if not isinstance(timetable.get("trips"), list) or not timetable.get("version"):
        raise ValueError("TIMETABLE_INVALID")
    generated = _time(generated_at, "generatedAt")
    valid_from = _time(timetable.get("validFrom"), "validFrom")
    valid_until = _time(timetable.get("validUntil"), "validUntil")
    if generated < valid_from:
        raise ValueError("GENERATED_AT_BEFORE_SERVICE_DATE")
    criteria = {
        "minDistinctStations": int(min_distinct_stations),
        "maxScheduleOffsetSeconds": int(max_schedule_offset_seconds),
        "maxDeviationSeconds": int(max_deviation_seconds),
    }
    if min(criteria.values()) <= 0:
        raise ValueError("CRITERIA_MUST_BE_POSITIVE")
    groups = collections.defaultdict(list)
    seen_events = set()
    rejected = collections.Counter()
    for raw in observation_document.get("observations", []):
        try:
            event_type = raw.get("eventType")
            line_id = _text(raw.get("providerLineId"))
            train_id = _text(raw.get("providerTrainId"))
            station_id = _text(raw.get("providerStationId"))
            event_id = _text(raw.get("eventId"))
            if event_type not in EVENT_TYPES:
                raise ValueError("EVENT_TYPE_UNSUPPORTED")
            if not all(value and PROVIDER_ID.fullmatch(value) for value in (line_id, train_id, station_id)):
                raise ValueError("PROVIDER_ID_INVALID")
            if not event_id or event_id in seen_events:
                raise ValueError("EVENT_ID_MISSING_OR_DUPLICATE")
            matched_at = _time(raw.get("matchedAt"), "matchedAt")
            if matched_at < valid_from or not matched_at < valid_until:
                raise ValueError("OBSERVATION_OUTSIDE_SERVICE_WINDOW")
            record = dict(raw)
            record["providerLineId"] = line_id
            record["providerTrainId"] = train_id
            record["providerStationId"] = station_id
            record["eventId"] = event_id
            record["_matchedAt"] = matched_at
            seen_events.add(event_id)
            groups[(line_id, train_id)].append(record)
        except ValueError as invalid:
            rejected[str(invalid)] += 1
    entries = [
        _entry(line_id, train_id, records, timetable, criteria)
        for (line_id, train_id), records in sorted(groups.items())
    ]
    status_counts = collections.Counter(entry["matchStatus"] for entry in entries)
    reason_counts = collections.Counter(entry["reason"] for entry in entries)
    artifact = {
        "schemaVersion": SCHEMA_VERSION,
        "timetableVersion": timetable["version"],
        "masterDataVersion": timetable["masterDataVersion"],
        "serviceDate": timetable["validFrom"][:10],
        "generatedAt": generated.isoformat(),
        "validFrom": timetable["validFrom"],
        "validUntil": timetable["validUntil"],
        "evidenceSha256": evidence_sha256,
        "criteria": criteria,
        "entries": entries,
    }
    digest = hashlib.sha256(json.dumps(artifact, ensure_ascii=False, sort_keys=True).encode()).hexdigest()
    artifact["version"] = "subway-train-crosswalk-v1-" + digest[:20]
    report = {
        "version": artifact["version"],
        "timetableVersion": timetable["version"],
        "inputObservations": len(observation_document.get("observations", [])),
        "acceptedObservations": sum(len(records) for records in groups.values()),
        "rejectedObservations": sum(rejected.values()),
        "observationRejectionCounts": dict(sorted(rejected.items())),
        "trainGroups": len(entries),
        "statusCounts": dict(sorted(status_counts.items())),
        "reasonCounts": dict(sorted(reason_counts.items())),
        "exactCoverage": status_counts["EXACT"] / len(entries) if entries else 0.0,
        "entries": entries,
    }
    return artifact, report


def apply_crosswalk(timetable, crosswalk):
    """검증된 EXACT만 복사한다. 중복이나 버전 불일치는 전체 적용을 실패시킨다."""
    if crosswalk.get("schemaVersion") != SCHEMA_VERSION:
        raise ValueError("CROSSWALK_SCHEMA_VERSION_UNSUPPORTED")
    if crosswalk.get("timetableVersion") != timetable.get("version"):
        raise ValueError("CROSSWALK_TIMETABLE_VERSION_MISMATCH")
    if crosswalk.get("masterDataVersion") != timetable.get("masterDataVersion"):
        raise ValueError("CROSSWALK_MASTER_VERSION_MISMATCH")
    if crosswalk.get("serviceDate") != timetable.get("validFrom", "")[:10]:
        raise ValueError("CROSSWALK_SERVICE_DATE_MISMATCH")
    trips = {trip["key"]: trip for trip in timetable["trips"]}
    used_provider_ids = set()
    used_trip_keys = set()
    pending = []
    criteria = crosswalk.get("criteria") or {}
    criterion_fields = ("minDistinctStations", "maxScheduleOffsetSeconds", "maxDeviationSeconds")
    if any(not _integer(criteria.get(field)) or criteria[field] <= 0 for field in criterion_fields):
        raise ValueError("CROSSWALK_CRITERIA_INVALID")
    for entry in crosswalk.get("entries", []):
        if entry.get("matchStatus") != "EXACT":
            continue
        key = entry.get("staticTripKey")
        trip = trips.get(key)
        provider = (entry.get("providerLineId"), entry.get("providerTrainId"))
        identity_fields = (
            "arrivalDirection",
            "positionDirection",
            "arrivalDestination",
            "positionDestination",
            "trainType",
        )
        offset = entry.get("scheduleOffsetSeconds")
        deviation = entry.get("maxScheduleDeviationSeconds")
        station_count = entry.get("matchedStationCount")
        if (
            trip is None
            or not all(_text(entry.get(field)) for field in identity_fields)
            or not all(value and PROVIDER_ID.fullmatch(value) for value in provider)
            or key not in entry.get("candidateTripKeys", [])
            or not _integer(station_count)
            or station_count < criteria["minDistinctStations"]
            or not _integer(offset)
            or abs(offset) > criteria["maxScheduleOffsetSeconds"]
            or not _integer(deviation)
            or deviation > criteria["maxDeviationSeconds"]
            or not entry.get("evidenceEventIds")
        ):
            raise ValueError("CROSSWALK_EXACT_ENTRY_INVALID")
        if trip.get("providerLineId") != provider[0]:
            raise ValueError("CROSSWALK_PROVIDER_LINE_MISMATCH")
        if key in used_trip_keys or provider in used_provider_ids:
            raise ValueError("CROSSWALK_EXACT_DUPLICATE")
        if entry.get("validFrom") != timetable.get("validFrom") or entry.get("validUntil") != timetable.get("validUntil"):
            raise ValueError("CROSSWALK_VALIDITY_MISMATCH")
        used_trip_keys.add(key)
        used_provider_ids.add(provider)
        pending.append((trip, provider[1], entry, identity_fields))
    for trip, train_id, entry, identity_fields in pending:
        trip["trainId"] = train_id
        for field in identity_fields:
            trip[field] = entry[field]
    return len(pending)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--timetable", type=Path, required=True)
    parser.add_argument("--observations", type=Path, required=True)
    parser.add_argument("--generated-at", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--min-distinct-stations", type=int, default=3)
    parser.add_argument("--max-schedule-offset-seconds", type=int, default=1800)
    parser.add_argument("--max-deviation-seconds", type=int, default=120)
    args = parser.parse_args()
    timetable = json.loads(args.timetable.read_text(encoding="utf-8"))
    raw_observations = args.observations.read_bytes()
    observations = json.loads(raw_observations)
    artifact, report = build_crosswalk(
        timetable,
        observations,
        args.generated_at,
        min_distinct_stations=args.min_distinct_stations,
        max_schedule_offset_seconds=args.max_schedule_offset_seconds,
        max_deviation_seconds=args.max_deviation_seconds,
        evidence_sha256=hashlib.sha256(raw_observations).hexdigest(),
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(artifact, ensure_ascii=False, indent=2), encoding="utf-8")
    args.output.with_suffix(".report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(json.dumps({key: value for key, value in report.items() if key != "entries"}, ensure_ascii=True))


if __name__ == "__main__":
    main()
