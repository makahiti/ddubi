"""Review source master patterns and diagnose gaps without promoting canonical IDs."""
import argparse
import collections
import gzip
import importlib.util
import json
from pathlib import Path

spec = importlib.util.spec_from_file_location("subway_base", Path(__file__).with_name("subway-map-local-gtfs.py"))
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)


def load_rows(path):
    return [json.loads(x) for x in path.read_text(encoding="utf-8").splitlines()]


def sequence_relation(source, target):
    """Return exact directed contiguous versus ordered skipped-stop pattern support."""
    if not source or any(x is None for x in source):
        return None
    if any(target[i:i+len(source)] == source for i in range(len(target)-len(source)+1)):
        return "CONTIGUOUS"
    cursor = 0
    for item in target:
        if item == source[cursor]:
            cursor += 1
            if cursor == len(source):
                return "ORDERED_SUBSEQUENCE"
    return None


def diagnose(mapping, stations, coords, radius):
    status = mapping["status"]
    if status == "MISSING_MASTER_LINE":
        return "MASTER_LINE_ABSENT", []
    stop = mapping.get("sourceStop")
    if stop is None:
        return "SOURCE_STOP_ABSENT", []
    if base.distance(stop, {"latitude": stop.get("stop_lat"), "longitude": stop.get("stop_lon")}) is None:
        return "SOURCE_COORDINATES_INVALID", []
    if status == "UNIQUE_NAME_COORDINATE_CANDIDATE":
        return "NAME_COORDINATE_SUPPORTED", mapping["evidence"]
    if status == "AMBIGUOUS":
        return "MULTIPLE_NAME_COORDINATE_CANDIDATES", mapping["evidence"]
    if status == "NAME_ONLY_OR_COORDINATE_CONFLICT":
        measured = [x for x in mapping["evidence"] if x["distanceMeters"] is not None]
        return ("NAME_MATCH_COORDINATES_OUTSIDE_RADIUS" if measured else "MASTER_COORDINATES_UNAVAILABLE"), mapping["evidence"]
    nearby = []
    for sid, station in stations.items():
        meters = base.distance(stop, coords.get(sid, {}))
        if meters is not None and meters <= radius:
            nearby.append(dict(stationId=sid, stationName=station["stationName"], distanceMeters=round(meters,2)))
    return ("NAME_DIFFERENCE_NEARBY_CANDIDATE" if nearby else "NO_NAME_OR_NEARBY_MASTER_STATION"), sorted(nearby, key=lambda x:x["stationId"])


def validate_candidate(candidate):
    if candidate["productionReady"] is not False or candidate["canonicalPatternId"] is not None:
        raise ValueError("Review candidate cannot be production approved")
    stops = candidate["stops"]
    if len(stops) < 2 or [x["sequence"] for x in stops] != list(range(1,len(stops)+1)):
        raise ValueError("Invalid source pattern sequence")
    if any(not x["sourceStopId"] or not x["sourceStop"] for x in stops):
        raise ValueError("Missing source stop")


def review(source, master_path, coordinate_path, output):
    complete = json.loads((source/"complete.json").read_text(encoding="utf-8"))
    for name, expected in complete["outputs"].items():
        if base.digest(source/name) != expected:
            raise ValueError("Source artifact checksum mismatch: " + name)
    paths = [source/"complete.json", master_path, coordinate_path, Path(__file__), Path(base.__file__)]
    hashes = {str(x.resolve()):base.digest(x) for x in paths}
    radius = complete["inputs"]["maximumCoordinateDistanceMeters"]
    # Do not combine prior candidates with a different master or coordinate snapshot.
    for path in (master_path, coordinate_path):
        if complete["inputs"]["sha256"].get(str(path.resolve())) != base.digest(path):
            raise ValueError("Input differs from original mapping snapshot")
    if output.exists():
        raise ValueError("Output already exists; preserve it and select a new directory")
    master = json.loads(gzip.decompress(master_path.read_bytes()))
    coords = json.loads(coordinate_path.read_text(encoding="utf-8"))
    lines = collections.defaultdict(list)
    stations = collections.defaultdict(dict)
    triples = collections.defaultdict(set)
    for line in master["lines"]:
        name = base.line_name(line["lineName"])
        ordered = sorted(line["stations"], key=lambda x:x["sequence"])
        seq = [x["stationId"] for x in ordered]
        lines[name].append((line, seq))
        for x in ordered:
            stations[name][x["stationId"]] = x
        for a,b,c in zip(seq,seq[1:],seq[2:]):
            triples[(name,a,c)].add(b)
    mappings = {(x["routeId"],x["sourceStopId"]):x for x in load_rows(source/"station-mapping-candidates.jsonl")}
    patterns = load_rows(source/"master-pattern-candidates.jsonl")
    diagnostics = {}
    alias_support = collections.defaultdict(set)
    for key, mapping in mappings.items():
        name = base.line_name(mapping["lineName"])
        cause, nearby = diagnose(mapping, stations[name], coords, radius)
        diagnostics[key] = dict(routeId=key[0], sourceStopId=key[1], lineName=mapping["lineName"],
            region=mapping["region"], originalStatus=mapping["status"], cause=cause,
            nearbyEvidence=nearby, sourceStop=mapping["sourceStop"],
            reviewCandidateStationId=mapping["candidateStationId"], productionReady=False)
        if cause == "NAME_DIFFERENCE_NEARBY_CANDIDATE":
            raw_name = base.station_name(mapping["sourceStop"]["stop_name"])
            variants = [x for x in nearby if terminal_station_variant(raw_name, base.station_name(x["stationName"]))]
            if len(variants) == 1:
                diagnostics[key]["cause"] = "NAME_TERMINAL_STATION_SUFFIX_COORDINATE_SUPPORTED"
                diagnostics[key]["reviewCandidateStationId"] = variants[0]["stationId"]
    # Only a nearby station bracketed by two independently mapped adjacent stops
    # in the same directed master pattern supports a potential rename.
    for pattern in patterns:
        rid = pattern["route"]["route_id"]
        name = base.line_name(pattern["route"]["route_short_name"])
        for a,b,c in zip(pattern["stops"],pattern["stops"][1:],pattern["stops"][2:]):
            key = (rid,b["sourceStopId"])
            if diagnostics[key]["cause"] != "NAME_DIFFERENCE_NEARBY_CANDIDATE":
                continue
            supported = triples.get((name,diagnostics[(rid,a["sourceStopId"])]["reviewCandidateStationId"],
                                    diagnostics[(rid,c["sourceStopId"])]["reviewCandidateStationId"]), set())
            for item in diagnostics[key]["nearbyEvidence"]:
                if item["stationId"] in supported:
                    alias_support[key].add(item["stationId"])
    for key, supported in alias_support.items():
        diagnostics[key]["adjacentPatternCandidateStationIds"] = sorted(supported)
        diagnostics[key]["cause"] = "NAME_DIFFERENCE_COORDINATE_AND_BOTH_NEIGHBORS_SUPPORTED" if len(supported)==1 else "AMBIGUOUS_NEIGHBOR_SUPPORTED_RENAME"
        if len(supported) == 1:
            diagnostics[key]["reviewCandidateStationId"] = next(iter(supported))
    reviewed = []
    candidates = []
    for pattern in patterns:
        name = base.line_name(pattern["route"]["route_short_name"])
        rid = pattern["route"]["route_id"]
        seq = [diagnostics[(rid,x["sourceStopId"])]["reviewCandidateStationId"] for x in pattern["stops"]]
        evidence = []
        for line, target in lines[name]:
            relation = sequence_relation(seq,target)
            if relation:
                evidence.append(dict(servicePatternId=line["servicePatternId"], direction=line["direction"], relation=relation))
        # Native GTFS direction labels are retained, never translated into provider UP/DOWN.
        directions = {x["direction"] for x in evidence}
        if len(directions)>1:
            status = "AMBIGUOUS_MASTER_DIRECTION"
        elif any(x["relation"] == "CONTIGUOUS" for x in evidence):
            status = "FULL_CONTIGUOUS_PATTERN_CANDIDATE"
        elif evidence:
            status = "SKIPPED_STOP_PATTERN_REQUIRES_EXPRESS_REVIEW"
        elif name not in lines or not lines[name]:
            status = "MISSING_MASTER_LINE_SOURCE_PATTERN"
        elif None in seq:
            status = "INCOMPLETE_STATION_MAPPING"
        else:
            status = "NO_SINGLE_MASTER_PATTERN_SUPPORT"
        reviewed.append(dict(sourcePatternId=pattern["sourcePatternId"], region=pattern["region"],
            lineName=pattern["route"]["route_short_name"], routeId=pattern["route"]["route_id"],
            status=status, masterPatternEvidence=evidence, mappedStopCount=sum(x is not None for x in seq),
            sourceStopCount=len(seq), reviewCandidateStationIds=seq,
            directionLabel=pattern["directionLabel"], productionReady=False))
        if status != "FULL_CONTIGUOUS_PATTERN_CANDIDATE":
            candidate = dict(pattern, canonicalPatternId=None, candidateReason=status,
                namespace="GTFS_SOURCE_ONLY", limitations=["CURRENT_TIMETABLE_VALIDITY_UNVERIFIED","PROVIDER_IDS_UNASSIGNED"])
            try:
                validate_candidate(candidate)
                candidates.append(candidate)
            except ValueError:
                reviewed[-1]["status"] = "INVALID_SOURCE_PATTERN"
    summary = dict(productionReady=False, sourcePatternCount=len(patterns),
        mappingCauseCounts=dict(collections.Counter(x["cause"] for x in diagnostics.values())),
        patternStatusCounts=dict(collections.Counter(x["status"] for x in reviewed)),
        sourceMasterCandidateCount=len(candidates),
        regionPatternStatusCounts={region:dict(collections.Counter(x["status"] for x in reviewed if x["region"]==region)) for region in sorted({x["region"] for x in reviewed})})
    output.mkdir(parents=True,exist_ok=False)
    base.write_jsonl(output/"station-gap-diagnostics.jsonl",diagnostics.values())
    base.write_jsonl(output/"pattern-review.jsonl",reviewed)
    base.write_jsonl(output/"source-master-expansion-candidates.jsonl",candidates)
    base.write_json(output/"summary.json",summary)
    base.write_json(output/"complete.json",dict(inputs=hashes,outputs={x.name:base.digest(x) for x in output.iterdir()},summary=summary))
    return summary


def terminal_station_variant(a, b):
    """Only an exact additional terminal 역, never fuzzy or arbitrary suffix stripping."""
    return bool(a and b and (a + "역" == b or b + "역" == a))


if __name__ == "__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ("source","master","coordinates","output"):
        parser.add_argument("--"+name,type=Path,required=True)
    args=parser.parse_args()
    print(json.dumps(review(args.source,args.master,args.coordinates,args.output),ensure_ascii=False))
