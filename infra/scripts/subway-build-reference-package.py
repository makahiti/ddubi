"""Package all reviewed GTFS subway patterns and undated scheduled reference data."""
import argparse
import collections
import datetime
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys

spec=importlib.util.spec_from_file_location("subway_base",Path(__file__).with_name("subway-map-local-gtfs.py"))
base=importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)


def verified_manifest(root):
    manifest=json.loads((root/"complete.json").read_text(encoding="utf-8"))
    for name, checksum in manifest["outputs"].items():
        if base.digest(root/name)!=checksum:
            raise ValueError("INPUT_CHECKSUM_MISMATCH:"+name)
    return manifest


def read_jsonl(path):
    return [json.loads(x) for x in path.read_text(encoding="utf-8").splitlines()]


def identity(route,service,stops):
    return hashlib.sha256(json.dumps([route,service,stops]).encode()).hexdigest()


def validate_calls(calls):
    ordered=sorted(calls,key=lambda x:int(x["stop_sequence"]))
    sequences=[int(x["stop_sequence"]) for x in ordered]
    if len(ordered)<2 or len(set(sequences))!=len(sequences) or any(x<0 for x in sequences):
        raise ValueError("INVALID_STOP_SEQUENCE")
    result=[]
    for call in ordered:
        a,d=base.seconds(call["arrival_time"]),base.seconds(call["departure_time"])
        if d<a:
            raise ValueError("NEGATIVE_DWELL")
        if result and not 0<a-result[-1][2]<=3600:
            raise ValueError("NON_MONOTONIC_OR_EXCESSIVE_SEGMENT")
        result.append((call["stop_id"],a,d))
    return result


def headways(departures):
    # Exact timestamp duplicates are counted, not interpreted as zero-second service.
    times=sorted(set(departures))
    by_hour=collections.defaultdict(list)
    for a,b in zip(times,times[1:]):
        by_hour[a//3600].append(b-a)
    return by_hour,len(departures)-len(times)


def validate_package(data):
    if data["productionReady"] or data["usage"]!="UNDATED_SCHEDULE_REFERENCE":
        raise ValueError("PRODUCTION_PROMOTION_FORBIDDEN")
    availability=data["availability"]
    for name in ("sourcePublishedAt","sourceAvailableAt","currentValidFrom","currentValidUntil"):
        if availability[name] is not None:
            raise ValueError("UNVERIFIED_SOURCE_DATES")
    if len({x["sourcePatternId"] for x in data["patterns"]})!=len(data["patterns"]):
        raise ValueError("DUPLICATE_PATTERN")
    if any(x["productionReady"] or x["canonicalPatternId"] is not None for x in data["patterns"]):
        raise ValueError("UNAPPROVED_MASTER_PATTERN")


def build(gtfs,source,review,output):
    sm=verified_manifest(source)
    rm=verified_manifest(review)
    if rm["inputs"].get(str((source/"complete.json").resolve()))!=base.digest(source/"complete.json"):
        raise ValueError("REVIEW_SOURCE_VERSION_MISMATCH")
    if output.exists():
        raise ValueError("OUTPUT_EXISTS")
    inputs={str(x.resolve()):base.digest(x) for x in (source/"complete.json",review/"complete.json",Path(__file__),Path(base.__file__))}
    for name in ("routes.txt","trips.txt","stops.txt","stop_times.txt","calendar.txt"):
        path=gtfs/name
        checksum=base.digest(path)
        if sm["inputs"]["sha256"].get(str(path.resolve()))!=checksum:
            raise ValueError("GTFS_VERSION_MISMATCH:"+name)
        inputs[str(path.resolve())]=checksum
    patterns=read_jsonl(source/"master-pattern-candidates.jsonl")
    reviewed={x["sourcePatternId"]:x for x in read_jsonl(review/"pattern-review.jsonl")}
    if set(reviewed)!={x["sourcePatternId"] for x in patterns}:
        raise ValueError("PATTERN_COVERAGE_MISMATCH")
    route_ids={x["route"]["route_id"] for x in patterns}
    trips={x["trip_id"]:x for x in base.rows(gtfs/"trips.txt") if x["route_id"] in route_ids}
    calls=collections.defaultdict(list)
    for call in base.rows(gtfs/"stop_times.txt"):
        if call["trip_id"] in trips:
            calls[call["trip_id"]].append(call)
    edge_values=collections.defaultdict(list)
    departures=collections.defaultdict(list)
    accepted=collections.Counter()
    rejected=[]
    for tid,trip in sorted(trips.items()):
        try:
            sequence=validate_calls(calls[tid])
            pid=identity(trip["route_id"],trip["service_id"],[x[0] for x in sequence])
            if pid not in reviewed:
                raise ValueError("PATTERN_NOT_REVIEWED")
            accepted[pid]+=1
            for index,(sid,arrival,departure) in enumerate(sequence):
                if index<len(sequence)-1:
                    # Terminal departure is not a boarding opportunity.
                    departures[(pid,index)].append(departure)
                    edge_values[(pid,index)].append(sequence[index+1][1]-departure)
        except (ValueError,KeyError) as exc:
            rejected.append(dict(sourceTripId=tid,routeId=trip["route_id"],reason=str(exc)))
    package_patterns=[]
    for pattern in patterns:
        pid=pattern["sourcePatternId"]
        result=reviewed[pid]
        package_patterns.append(dict(pattern,canonicalPatternId=None,reviewStatus=result["status"],
            masterPatternEvidence=result["masterPatternEvidence"],reviewCandidateStationIds=result["reviewCandidateStationIds"],
            acceptedScheduledTrips=accepted[pid],referenceTimingAvailable=accepted[pid]>0))
    by_id={x["sourcePatternId"]:x for x in package_patterns}
    edges=[]
    for (pid,index),values in sorted(edge_values.items()):
        pattern=by_id[pid]
        edges.append(dict(sourcePatternId=pid,serviceId=pattern["serviceId"],sequence=index+1,
            fromSourceStopId=pattern["stops"][index]["sourceStopId"],toSourceStopId=pattern["stops"][index+1]["sourceStopId"],
            reviewStatus=pattern["reviewStatus"],scheduledMeanSeconds=sum(values)/len(values),
            scheduledMinSeconds=min(values),scheduledMaxSeconds=max(values),scheduledTripSamples=len(values),
            observedTripSamples=0,canonicalSegmentId=None,productionReady=False))
    intervals=[]
    duplicate_departures=0
    for (pid,index),values in sorted(departures.items()):
        by_hour,duplicates=headways(values)
        duplicate_departures+=duplicates
        for hour,gaps in sorted(by_hour.items()):
            intervals.append(dict(sourcePatternId=pid,sourceStopId=by_id[pid]["stops"][index]["sourceStopId"],sequence=index+1,
                serviceId=by_id[pid]["serviceId"],serviceElapsedHour=hour,timezone="Asia/Seoul",
                scheduledMeanHeadwaySeconds=sum(gaps)/len(gaps),scheduledMinHeadwaySeconds=min(gaps),
                scheduledMaxHeadwaySeconds=max(gaps),scheduledGapSamples=len(gaps),
                windowMeaning="GAP_GROUPED_BY_EARLIER_DEPARTURE_HOUR_IN_SAME_SOURCE_PATTERN",
                productionReady=False))
    bundle=dict(schemaVersion="ddubi-subway-reference-v1",productionReady=False,usage="UNDATED_SCHEDULE_REFERENCE",
        masterDataVersion=sm["summary"]["masterVersion"],sourceVersion=base.digest(source/"complete.json"),
        availability=dict(packagedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
            sourcePublishedAt=None,sourceAvailableAt=None,currentValidFrom=None,currentValidUntil=None),
        limitations=["NOT_A_DATED_BACKEND_TIMETABLE","CURRENT_TIMETABLE_VALIDITY_UNVERIFIED",
            "PROVIDER_TRAIN_IDS_UNASSIGNED","PATTERN_SPECIFIC_HEADWAY_NOT_ALL_LINE_FREQUENCY","NOT_REALTIME_OR_OBSERVED_DATA"],
        patterns=package_patterns)
    validate_package(bundle)
    summary=dict(productionReady=False,inputTrips=len(trips),acceptedTrips=sum(accepted.values()),rejectedTrips=len(rejected),
        patternCount=len(patterns),patternsWithTiming=sum(accepted[x["sourcePatternId"]]>0 for x in patterns),
        referenceEdgeCount=len(edges),headwayBucketCount=len(intervals),duplicateScheduledDepartures=duplicate_departures,
        patternStatusCounts=dict(collections.Counter(x["reviewStatus"] for x in package_patterns)),
        regionPatternCounts=dict(collections.Counter(x["region"] for x in package_patterns)))
    output.mkdir(parents=True,exist_ok=False)
    base.write_json(output/"reference-master-bundle.json",bundle)
    base.write_jsonl(output/"scheduled-segment-reference.jsonl",edges)
    base.write_jsonl(output/"scheduled-headway-reference.jsonl",intervals)
    base.write_json(output/"quarantine.json",rejected)
    base.write_json(output/"summary.json",summary)
    base.write_json(output/"complete.json",dict(inputs=inputs,outputs={x.name:base.digest(x) for x in output.iterdir()},summary=summary))
    return summary


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ("gtfs","source","review","output"):
        parser.add_argument("--"+name,type=Path,required=True)
    for name in ("job-record","stdout-log","stderr-log"):
        parser.add_argument("--"+name,type=Path)
    args=parser.parse_args()
    job=dict(pid=os.getpid(),startedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
        script=str(Path(__file__).resolve()),stdout=str(args.stdout_log) if args.stdout_log else None,
        stderr=str(args.stderr_log) if args.stderr_log else None,output=str(args.output.resolve()),exitCode=None)
    if args.job_record:
        args.job_record.parent.mkdir(parents=True,exist_ok=True)
        base.write_json(args.job_record,job)
    try:
        print(json.dumps(build(args.gtfs,args.source,args.review,args.output),ensure_ascii=False))
        job["exitCode"]=0
    except BaseException:
        job["exitCode"]=1
        raise
    finally:
        job["finishedAt"]=datetime.datetime.now(datetime.timezone.utc).isoformat()
        if args.job_record:
            base.write_json(args.job_record,job)
