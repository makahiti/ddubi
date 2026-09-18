"""Bind GTFS shape review samples to exact bus stop occurrences, never approve runtime mappings."""
import argparse
import collections
import csv
import gzip
import hashlib
import json
import math
from pathlib import Path


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as f:
        for block in iter(lambda:f.read(1048576), b''):
            h.update(block)
    return h.hexdigest()


def read_csv(path):
    with Path(path).open(encoding='utf-8-sig', newline='') as f:
        yield from csv.DictReader(f)


def direction_evidence(candidate):
    """GIS DB manual: graphics start/end is UP; ONEWAY=1 permits only UP."""
    attrs, orientation = candidate['attributes'], candidate['orientation']
    code = str(attrs.get('ONEWAY', ''))
    if code not in ('0','1') or orientation not in ('FORWARD','REVERSE'):
        return dict(state='DIRECTION_CODE_UNKNOWN', permitted=False)
    if code == '1' and orientation == 'REVERSE':
        return dict(state='ONEWAY_OPPOSITE_DIRECTION', permitted=False)
    up = orientation == 'FORWARD'
    link = str(attrs.get('TRAF_ID_P' if up else 'TRAF_ID_N',''))
    return dict(state='SOURCE_DIRECTION_PERMITTED', permitted=True,
        sourceNodeFrom=attrs.get('UP_FROM_NO' if up else 'DOWN_FROM_'),
        sourceNodeTo=attrs.get('UP_TO_NODE' if up else 'DOWN_TO_NO'),
        nationalStandardLinkId=link if link not in ('','0') else None,
        runtimeLinkNamespace=None, runtimeNamespaceVerified=False)


def geographic_distance(a,b):
    if not all(math.isfinite(v) for v in (*a,*b)) or not (-90<=a[0]<=90 and -90<=b[0]<=90 and -180<=a[1]<=180 and -180<=b[1]<=180):
        raise ValueError('INVALID_STOP_OR_SHAPE_COORDINATE')
    lat1, lon1, lat2, lon2 = map(math.radians, (a[0],a[1],b[0],b[1]))
    h = math.sin((lat2-lat1)/2)**2+math.cos(lat1)*math.cos(lat2)*math.sin((lon2-lon1)/2)**2
    return 6371008.8*2*math.asin(min(1,math.sqrt(h)))


def vertex_at_stop(stop, shape, coordinates, tolerance=30):
    distance = float(stop['shape_dist_traveled'])
    if not math.isfinite(distance):
        raise ValueError('NONFINITE_SHAPE_DISTANCE')
    matches = [i for i,r in enumerate(shape) if abs(float(r['shape_dist_traveled'])-distance)<.001]
    if len(matches) != 1:
        raise ValueError('STOP_SHAPE_VERTEX_UNRESOLVED')
    i = matches[0]
    actual = coordinates[stop['stop_id']]
    if geographic_distance((float(actual['stop_lat']),float(actual['stop_lon'])),
                           (float(shape[i]['shape_pt_lat']),float(shape[i]['shape_pt_lon']))) > tolerance:
        raise ValueError('STOP_SHAPE_COORDINATE_MISMATCH')
    return i


def exact_edge(index, namespace, route_id, prefix, start, end):
    if not route_id.startswith(prefix) or not start['stop_id'].startswith(prefix) or not end['stop_id'].startswith(prefix):
        raise ValueError('GTFS_IDENTIFIER_PREFIX_MISMATCH')
    key = (namespace, route_id[len(prefix):], int(start['stop_sequence']), int(end['stop_sequence']),
           start['stop_id'][len(prefix):], end['stop_id'][len(prefix):])
    found = index.get(key, [])
    if len(found) != 1:
        raise ValueError('BUS_STOP_OCCURRENCE_UNRESOLVED')
    return found[0]


def connected_path(samples, from_vertex, to_vertex):
    """Conservative dynamic programming over sampled, directed candidates; never runtime approval."""
    if not samples or set(range(from_vertex,to_vertex))-set(r['shapeEdge'] for r in samples):
        return dict(state='SHAPE_SAMPLE_COVERAGE_GAP',path=[])
    states=[]
    for sample in samples:
        candidates=sample['permittedCandidates']
        if not candidates:return dict(state='ROAD_CANDIDATE_GAP',path=[])
        next_states=[]
        for current in candidates:
            if not all(k in current for k in ('directedStart','directedEnd','alongLinkMetres')):
                return dict(state='DIRECTIONAL_GEOMETRY_NOT_AVAILABLE',path=[])
            token=(current['linkId'],current['orientation'])
            paths=[]
            if not states:
                paths=[(token,)]
            for previous,previous_paths in states:
                old=(previous['linkId'],previous['orientation'])
                if token==old:
                    allowed=current['alongLinkMetres']+5>=previous['alongLinkMetres']
                else:
                    a,b=previous['directionEvidence'],current['directionEvidence']
                    node=a.get('sourceNodeTo')
                    allowed=bool(node and node!='0' and node==b.get('sourceNodeFrom') and math.dist(previous['directedEnd'],current['directedStart'])<=5)
                if allowed:
                    for path in previous_paths:
                        extended=path if token==old else path+(token,)
                        if extended not in paths:paths.append(extended)
                        if len(paths)>=2:break
                if len(paths)>=2:break
            if paths:next_states.append((current,paths))
        if not next_states:return dict(state='DIRECTIONAL_PATH_DISCONNECTED',path=[])
        states=next_states
    paths=[]
    for _,possible in states:
        for path in possible:
            if path not in paths:paths.append(path)
            if len(paths)>=2:return dict(state='MULTIPLE_CONNECTED_CANDIDATE_PATHS',path=[])
    return dict(state='SINGLE_CONNECTED_CANDIDATE_PATH',path=[dict(linkId=p[0],orientation=p[1]) for p in paths[0]])


def run(args):
    # This is an explicit identifier adapter, not a route-name or coordinate fuzzy join.
    if not args.gtfs_id_prefix or not args.bus_namespace:
        raise ValueError('Explicit provider namespace and identifier prefix required')
    first_manifest=json.loads((args.candidates.parent/'complete.json').read_text(encoding='utf-8'))
    if first_manifest['outputs'].get(args.candidates.name) != digest(args.candidates):
        raise ValueError('First-stage candidate checksum mismatch')
    first_report_path=args.candidates.parent/'report.json'
    if first_manifest['outputs'].get('report.json') != digest(first_report_path):
        raise ValueError('First-stage report checksum mismatch')
    expected_shapes=[sha for name,sha in first_manifest['inputs'].items() if Path(name).name=='shapes.txt']
    if expected_shapes != [digest(args.gtfs/'shapes.txt')]:
        raise ValueError('GTFS shapes differ from first-stage input')
    first_report=json.loads(first_report_path.read_text(encoding='utf-8'))
    margin=first_report['configuration']['ambiguityMarginMetres']
    if not math.isfinite(margin) or margin <= 0:raise ValueError('Invalid first-stage ambiguity margin')
    args.output.mkdir(parents=True, exist_ok=False)
    files = [args.candidates,args.bus_crosswalk]+[args.gtfs/name for name in ('trips.txt','stop_times.txt','stops.txt','shapes.txt')]
    bus = json.loads(gzip.decompress(args.bus_crosswalk.read_bytes()))
    index = collections.defaultdict(list)
    for edge in bus['edges']:
        index[(edge['namespace'],edge['routeId'],edge['fromOrder'],edge['toOrder'],edge['fromStopId'],edge['toStopId'])].append(edge)
    samples, counts = collections.defaultdict(list), collections.Counter()
    with args.candidates.open(encoding='utf-8') as f:
        for line in f:
            row = json.loads(line)
            for candidate in row['candidates']:
                candidate['directionEvidence'] = direction_evidence(candidate)
                counts[candidate['directionEvidence']['state']] += 1
            row['permittedCandidates'] = [c for c in row['candidates'] if c['directionEvidence']['permitted']]
            samples[row['shapeId']].append(row)
    shapes = collections.defaultdict(list)
    for row in read_csv(args.gtfs/'shapes.txt'):
        if row['shape_id'] in samples:
            shapes[row['shape_id']].append(row)
    invalid_shapes = set()
    for key, rows in shapes.items():
        rows.sort(key=lambda r:int(r['shape_pt_sequence']))
        distances = [float(r['shape_dist_traveled']) for r in rows]
        if len({int(r['shape_pt_sequence']) for r in rows}) != len(rows) or any(not math.isfinite(x) for x in distances) or any(a>=b for a,b in zip(distances,distances[1:])):
            invalid_shapes.add(key)
    trips = {r['trip_id']:r for r in read_csv(args.gtfs/'trips.txt') if r['shape_id'] in samples}
    stops = {r['stop_id']:r for r in read_csv(args.gtfs/'stops.txt')}
    times = collections.defaultdict(list)
    for row in read_csv(args.gtfs/'stop_times.txt'):
        if row['trip_id'] in trips:
            times[row['trip_id']].append(row)
    seen = set()
    with (args.output/'occurrence-candidates.jsonl').open('x',encoding='utf-8') as out, (args.output/'rejected.jsonl').open('x',encoding='utf-8') as rejected:
        for trip_id, rows in times.items():
            trip = trips[trip_id]
            rows.sort(key=lambda r:int(r['stop_sequence']))
            if len({r['stop_sequence'] for r in rows}) != len(rows) or trip['shape_id'] in invalid_shapes:
                counts['INVALID_TRIP_OR_SHAPE_SEQUENCE'] += 1
                continue
            for start, end in zip(rows,rows[1:]):
                try:
                    edge = exact_edge(index,args.bus_namespace,trip['route_id'],args.gtfs_id_prefix,start,end)
                    a = vertex_at_stop(start,shapes[trip['shape_id']],stops)
                    b = vertex_at_stop(end,shapes[trip['shape_id']],stops)
                    if a >= b:
                        raise ValueError('SHAPE_ORDER_REVERSED')
                except (ValueError,KeyError) as exc:
                    reason = str(exc)
                    counts[reason] += 1
                    rejected.write(json.dumps(dict(tripId=trip_id,fromOrder=start['stop_sequence'],reason=reason))+'\n')
                    continue
                # Collapse identical service-day patterns, preserving different shape occurrences.
                key = (edge['namespace'],edge['routeId'],edge['fromOrder'],trip['shape_id'],a,b)
                if key in seen:
                    counts['DUPLICATE_SERVICE_PATTERN'] += 1
                    continue
                seen.add(key)
                portion = [r for r in samples[trip['shape_id']] if a <= r['shapeEdge'] < b]
                covered = sum(bool(r['permittedCandidates']) for r in portion)
                ambiguous = sum(len(r['permittedCandidates']) > 1 and r['permittedCandidates'][1]['score']-r['permittedCandidates'][0]['score'] <= margin for r in portion)
                counts['EXACT_STOP_OCCURRENCE_REVIEW'] += 1
                connection=connected_path(portion,a,b)
                counts[connection['state']] += 1
                out.write(json.dumps(dict(edge=edge,shapeId=trip['shape_id'],fromShapeVertex=a,toShapeVertex=b,
                    sampleCount=len(portion),directionPermittedSamples=covered,ambiguousSamples=ambiguous,
                    sourceNationalLinkCandidates=sorted({c['directionEvidence']['nationalStandardLinkId'] for r in portion for c in r['permittedCandidates'] if c['directionEvidence']['nationalStandardLinkId']}),
                    runtimeMappingApproved=False,productionReady=False,
                    candidatePath=connection,
                    blockers=['ACCINFO_NAMESPACE_EQUIVALENCE_UNVERIFIED','COMPLETE_DIRECTIONAL_PATH_NOT_REVIEWED','SOURCE_VALIDITY_UNVERIFIED'],
                    reviewSamples=portion),ensure_ascii=False)+'\n')
    report = dict(productionReady=False,approvedMappings=0,masterVersion=bus['masterVersion'],
        busNamespace=args.bus_namespace,gtfsIdPrefix=args.gtfs_id_prefix,counts=dict(counts),shapeCount=len(samples),
        limitation='Retained top-3 candidates only. A connected candidate path is not approval of grade separation, current validity, or AccInfo ID equivalence.',
        sourceSemanticsEvidence='2024년기준 GIS DB 설명자료.hwp: LINK table and sections ②, ⑧, ㉒; see road-mapping-preparation.md',
        inputs={str(p):digest(p) for p in files})
    (args.output/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    manifest=dict(scriptSha256=digest(__file__),outputs={p.name:digest(p) for p in args.output.iterdir() if p.is_file()},productionReady=False)
    (args.output/'complete.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k not in ('inputs','limitation')},ensure_ascii=False))


if __name__ == '__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--candidates',type=Path,required=True)
    p.add_argument('--gtfs',type=Path,required=True)
    p.add_argument('--bus-crosswalk',type=Path,required=True)
    p.add_argument('--bus-namespace',required=True)
    p.add_argument('--gtfs-id-prefix',required=True)
    p.add_argument('--output',type=Path,required=True)
    run(p.parse_args())
