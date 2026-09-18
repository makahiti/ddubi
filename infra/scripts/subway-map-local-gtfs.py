"""Build review-only nationwide GTFS master/crosswalk candidates; never activate them."""
import argparse
import collections
import csv
import gzip
import hashlib
import json
import math
from pathlib import Path
import re


def rows(path):
    with path.open(encoding="utf-8-sig", newline="") as stream:
        yield from csv.DictReader(stream)


def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1048576), b""):
            h.update(block)
    return h.hexdigest()


def line_name(name):
    name = re.sub(r"\s+", "", name)
    if re.fullmatch(r"서울[1-9]호선", name):
        return name[2:]
    return {"용인경전철": "에버라인"}.get(name, name)


def station_name(name):
    # Parenthetical secondary names may differ, but do not strip terminal '역'.
    return re.sub(r"\([^)]*\)|\s+", "", name)


def region(name):
    if name.startswith("부산") or name == "동해선":
        return "부산·울산·경남"
    if name.startswith("대구") or name == "대경선":
        return "대구·경북"
    if name.startswith("대전"):
        return "대전"
    if name.startswith("광주"):
        return "광주"
    metro = {"GTX-A", "경강선", "경의중앙선", "경춘선", "공항철도", "김포도시철도",
             "서해선", "수인분당선", "신림선", "신분당선", "용인경전철", "우이신설경전철", "의정부경전철"}
    if name.startswith(("서울", "인천")) or name in metro:
        return "수도권"
    return "미분류"


def distance(stop, coord):
    try:
        lat, lon = float(stop["stop_lat"]), float(stop["stop_lon"])
        lat2, lon2 = float(coord["latitude"]), float(coord["longitude"])
        if not all(map(math.isfinite, (lat, lon, lat2, lon2))):
            return None
        if not (-90 <= lat <= 90 and -90 <= lat2 <= 90 and -180 <= lon <= 180 and -180 <= lon2 <= 180):
            return None
        a, b = math.radians(lat), math.radians(lat2)
        v = math.sin((b-a)/2)**2 + math.cos(a)*math.cos(b)*math.sin(math.radians(lon2-lon)/2)**2
        return 6371000 * 2 * math.asin(min(1, math.sqrt(max(0, v))))
    except (KeyError, TypeError, ValueError):
        return None


def match_station(stop, stations, coordinates, radius):
    candidates = []
    for sid, station in stations.items():
        if station_name(stop["stop_name"]) != station_name(station["stationName"]):
            continue
        meters = distance(stop, coordinates.get(sid, {}))
        candidates.append({"stationId": sid, "stationName": station["stationName"],
                           "distanceMeters": round(meters, 2) if meters is not None else None})
    nearby = [x for x in candidates if x["distanceMeters"] is not None and x["distanceMeters"] <= radius]
    if len(nearby) == 1:
        return "UNIQUE_NAME_COORDINATE_CANDIDATE", nearby[0]["stationId"], candidates
    if len(nearby) > 1:
        return "AMBIGUOUS", None, candidates
    return ("NAME_ONLY_OR_COORDINATE_CONFLICT" if candidates else "MISSING_STATION"), None, candidates


def seconds(value):
    h, m, s = map(int, value.split(":"))
    if not (0 <= h < 48 and 0 <= m < 60 and 0 <= s < 60):
        raise ValueError(value)
    return h*3600 + m*60 + s


def write_json(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def write_jsonl(path, data):
    with path.open("x", encoding="utf-8") as stream:
        for item in data:
            stream.write(json.dumps(item, ensure_ascii=False) + "\n")


def build(gtfs, master_path, coordinate_path, output, radius=300):
    inputs = [gtfs / x for x in ("routes.txt", "trips.txt", "stops.txt", "stop_times.txt", "calendar.txt")]
    inputs += [master_path, coordinate_path, Path(__file__)]
    hashes = {str(p.resolve()): digest(p) for p in inputs}
    signature = {"sha256": hashes, "maximumCoordinateDistanceMeters": radius}
    if output.exists():
        complete = output / "complete.json"
        if complete.exists():
            old = json.loads(complete.read_text(encoding="utf-8"))
            if old["inputs"] == signature and all(digest(output / n) == h for n, h in old["outputs"].items()):
                return old["summary"]
        raise ValueError("Output exists but is incomplete, changed, or belongs to different inputs; choose another output")
    routes = {x["route_id"]: x for x in rows(gtfs / "routes.txt") if x["route_type"] == "1"}
    trips = {x["trip_id"]: x for x in rows(gtfs / "trips.txt") if x["route_id"] in routes}
    stops = {x["stop_id"]: x for x in rows(gtfs / "stops.txt")}
    calendars = {x["service_id"]: x for x in rows(gtfs / "calendar.txt")}
    calls = collections.defaultdict(list)
    for x in rows(gtfs / "stop_times.txt"):
        if x["trip_id"] in trips:
            calls[x["trip_id"]].append(x)
    master = json.loads(gzip.decompress(master_path.read_bytes()))
    coordinates = json.loads(coordinate_path.read_text(encoding="utf-8"))
    master_stations = collections.defaultdict(dict)
    master_edges = collections.defaultdict(set)
    for line in master["lines"]:
        name = line_name(line["lineName"])
        ordered = sorted(line["stations"], key=lambda x: x["sequence"])
        for station in ordered:
            master_stations[name][station["stationId"]] = station
        for a, b in zip(ordered, ordered[1:]):
            master_edges[(name, a["stationId"], b["stationId"])].add(
                (line["subwayId"], line["direction"], line["servicePatternId"]))
    mappings = {}
    mapping_counts = collections.Counter()
    patterns = collections.Counter()
    timing = collections.defaultdict(list)
    issues = collections.Counter()
    for tid, trip in sorted(trips.items()):
        rid = trip["route_id"]
        route = routes[rid]
        name = line_name(route["route_short_name"])
        try:
            ordered = sorted(calls[tid], key=lambda x: int(x["stop_sequence"]))
            sequences = [int(x["stop_sequence"]) for x in ordered]
            if len(sequences) != len(set(sequences)) or any(x < 0 for x in sequences):
                raise ValueError("invalid sequence")
        except (ValueError, KeyError):
            issues["invalidTripSequence"] += 1
            continue
        patterns[(rid, trip["service_id"], tuple(x["stop_id"] for x in ordered))] += 1
        for call in ordered:
            sid = call["stop_id"]
            if (rid, sid) not in mappings:
                stop = stops.get(sid)
                status, target, candidates = ("MISSING_SOURCE_STOP", None, [])
                if stop:
                    if name not in master_stations:
                        status = "MISSING_MASTER_LINE"
                    else:
                        status, target, candidates = match_station(stop, master_stations[name], coordinates, radius)
                mapping_counts[status] += 1
                mappings[(rid, sid)] = dict(routeId=rid, lineName=route["route_short_name"],
                    region=region(route["route_short_name"]), sourceStopId=sid, sourceStop=stop,
                    status=status, candidateStationId=target, evidence=candidates, productionReady=False)
        for a, b in zip(ordered, ordered[1:]):
            try:
                # GTFS sequences may have gaps; sorted consecutive calls are adjacent in this trip.
                elapsed = seconds(b["arrival_time"]) - seconds(a["departure_time"])
                if seconds(a["arrival_time"]) > seconds(a["departure_time"]) or seconds(b["arrival_time"]) > seconds(b["departure_time"]):
                    raise ValueError("negative dwell")
                if not 0 < elapsed <= 3600:
                    raise ValueError("duration outside review bounds")
            except (ValueError, KeyError):
                issues["invalidAdjacentTime"] += 1
                continue
            timing[(rid, trip["service_id"], a["stop_id"], b["stop_id"])].append(elapsed)
    edges = []
    for (rid, service, a, b), values in sorted(timing.items()):
        name = line_name(routes[rid]["route_short_name"])
        left, right = mappings[(rid, a)], mappings[(rid, b)]
        evidence = sorted(master_edges.get((name, left["candidateStationId"], right["candidateStationId"]), set()))
        edges.append(dict(routeId=rid, serviceId=service, fromStopId=a, toStopId=b,
            fromCandidateStationId=left["candidateStationId"], toCandidateStationId=right["candidateStationId"],
            status="DIRECTED_MASTER_EDGE_CANDIDATE" if evidence else "UNVERIFIED_MASTER_EDGE",
            masterPatternEvidence=[dict(subwayId=x, direction=y, servicePatternId=z) for x,y,z in evidence],
            scheduledMeanSeconds=sum(values)/len(values), scheduledSamples=len(values),
            canonicalSegmentId=None, productionReady=False))
    pattern_rows = []
    for (rid, service, sequence), count in sorted(patterns.items()):
        if not sequence:
            issues["emptyTripPattern"] += count
            continue
        route = routes[rid]
        pattern_rows.append(dict(sourcePatternId=hashlib.sha256(json.dumps([rid, service, sequence]).encode()).hexdigest(),
            route=route, region=region(route["route_short_name"]), serviceId=service,
            declaredCalendar=calendars.get(service), tripCount=count,
            masterLinePresent=line_name(route["route_short_name"]) in master_stations,
            direction=route.get("direction_id"), directionLabel=route["route_long_name"],
            stops=[dict(sourceStopId=s, sourceStop=stops.get(s), sequence=i+1,
                        candidateStationId=mappings[(rid,s)]["candidateStationId"]) for i,s in enumerate(sequence)],
            productionReady=False))
    summary = dict(productionReady=False, masterVersion=master["dataVersion"], routeCount=len(routes),
        lineCount=len({r["route_short_name"] for r in routes.values()}), tripCount=len(trips),
        patternCount=len(pattern_rows), stationMappingCounts=dict(mapping_counts),
        directedEdgeCandidates=sum(x["status"] == "DIRECTED_MASTER_EDGE_CANDIDATE" for x in edges),
        referenceEdgeCount=len(edges), validScheduledSamples=sum(x["scheduledSamples"] for x in edges),
        regionRouteCounts=dict(collections.Counter(region(x["route_short_name"]) for x in routes.values())),
        missingMasterLines=sorted({x["route_short_name"] for x in routes.values() if line_name(x["route_short_name"]) not in master_stations}),
        issues=dict(issues), blockedBy=["CURRENT_TIMETABLE_VALIDITY_UNVERIFIED", "PROVIDER_DIRECTION_AND_SERVICE_PATTERN_REVIEW_REQUIRED"],
        note="Candidate IDs are not approved canonical IDs. Calendar end dates do not certify present-day validity.")
    output.mkdir(parents=True, exist_ok=False)
    write_jsonl(output / "station-mapping-candidates.jsonl", mappings.values())
    write_jsonl(output / "reference-edge-candidates.jsonl", edges)
    write_jsonl(output / "master-pattern-candidates.jsonl", pattern_rows)
    write_json(output / "summary.json", summary)
    outputs = {p.name: digest(p) for p in output.iterdir() if p.is_file()}
    write_json(output / "complete.json", dict(inputs=signature, outputs=outputs, summary=summary))
    return summary


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("gtfs", "master", "coordinates", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--maximum-distance-meters", type=float, default=300)
    args = parser.parse_args()
    if not math.isfinite(args.maximum_distance_meters) or args.maximum_distance_meters <= 0:
        parser.error("maximum distance must be positive and finite")
    print(json.dumps(build(args.gtfs, args.master, args.coordinates, args.output, args.maximum_distance_meters), ensure_ascii=False))
