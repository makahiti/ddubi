"""서울 노선별 정류소 원본과 미매핑 관측 구간을 대조한다.

원본 XLSX는 읽기 전용이며 출력은 검토 후보 JSONL이다. 서로 다른 날짜의
스냅샷 차이가 실제 개편 때문인지 단정하지 않는다.
"""
import argparse
import collections
import datetime
import hashlib
import importlib.util
import json
import math
from pathlib import Path

spec = importlib.util.spec_from_file_location('bus_mapping', Path(__file__).with_name('bus-map-candidates.py'))
mapping = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mapping)


def identifier(value):
    if value is None or isinstance(value, bool):
        raise ValueError('INVALID_ID')
    if isinstance(value, (int, float)):
        if not math.isfinite(value) or int(value) != value:
            raise ValueError('INVALID_ID')
        return str(int(value))
    if not str(value).strip():
        raise ValueError('EMPTY_ID')
    return str(value).strip()


def source_index(records):
    routes = collections.defaultdict(lambda: collections.defaultdict(list))
    invalid = []
    for r in records:
        try:
            route = identifier(r['ROUTE_ID'])
            stop = identifier(r['NODE_ID'])
            seq = int(identifier(r['순번']))
            if seq < 1:
                raise ValueError('INVALID_SEQUENCE')
            lon, lat = float(r['X좌표']), float(r['Y좌표'])
            if not (124 <= lon <= 132 and 33 <= lat <= 39):
                raise ValueError('INVALID_COORDINATE')
            item = dict(stopId=stop, sequence=seq, stopName=r['정류소명'],
                        longitude=lon,latitude=lat, routeName=r['노선명'],
                        sourceRow=r['_row'], sourceSheet=r['_sheet'], direction=None)
            routes[route][seq].append(item)
        except (ValueError,TypeError,KeyError,OverflowError) as e:
            invalid.append(dict(row=r.get('_row'),reason=str(e)))
    return routes, invalid


def signature(stop):
    return (stop['stopId'],stop['longitude'],stop['latitude'])


def unique_stops(items):
    return {signature(s):s for s in items}


def classify(row, routes):
    route = routes.get(row['providerRouteId'])
    if not route:
        return 'SOURCE_ROUTE_MISSING', []
    a,b = int(row['fromOrder']),int(row['toOrder'])
    left,right = unique_stops(route.get(a,[])),unique_stops(route.get(b,[]))
    if len(left)>1 or len(right)>1:
        return 'SOURCE_SEQUENCE_AMBIGUOUS', []
    if left and right:
        x,y = next(iter(left.values())),next(iter(right.values()))
        if x['stopId']==row['fromStopId'] and y['stopId']==row['toStopId']:
            return 'SOURCE_EXACT_MASTER_GAP', [x,y]
    pairs=[]
    for order,stops in route.items():
        ls,rs=unique_stops(stops),unique_stops(route.get(order+1,[]))
        if len(ls)!=1 or len(rs)!=1:
            continue
        x,y=next(iter(ls.values())),next(iter(rs.values()))
        if (x['stopId'],y['stopId'])==(row['fromStopId'],row['toStopId']):
            pairs.append([x,y])
    if len(pairs)>1:
        return 'SOURCE_PAIR_AMBIGUOUS', []
    if pairs:
        return 'SOURCE_PAIR_ORDER_DIFFERENCE',pairs[0]
    return 'SOURCE_EDGE_MISSING', []


def workbook_records(path):
    import openpyxl
    book=openpyxl.load_workbook(path,read_only=True,data_only=True)
    try:
        sheet=book['Data']
        rows=sheet.iter_rows(values_only=True)
        header=list(next(rows))
        required={'ROUTE_ID','NODE_ID','순번','정류소명','노선명','X좌표','Y좌표'}
        if not required.issubset(header):
            raise ValueError('SOURCE_SCHEMA_MISMATCH')
        for number,row in enumerate(rows,2):
            yield dict(zip(header,row)) | dict(_row=number,_sheet=sheet.title)
    finally:
        book.close()


def run(candidates,source,snapshot_date,output_root):
    datetime.date.fromisoformat(snapshot_date)
    inputs={str(p.resolve()):mapping.sha(p) for p in (candidates,source)}
    code=mapping.sha(Path(__file__))
    helper=mapping.sha(Path(mapping.__file__))
    fingerprint=hashlib.sha256(json.dumps([inputs,code,helper,snapshot_date],sort_keys=True).encode()).hexdigest()
    target=output_root/fingerprint[:20]
    done=target/'complete.json'
    if done.exists():
        manifest=json.loads(done.read_text(encoding='utf-8'))
        if not all(mapping.sha(target/n)==d for n,d in manifest['outputs'].items()):
            raise ValueError('OUTPUT_CHECKSUM_MISMATCH')
        return {'reused':str(target),'result':manifest['result']}
    routes,invalid=source_index(workbook_records(source))
    target.mkdir(parents=True,exist_ok=False)
    edges,observations=collections.Counter(),collections.Counter()
    missing_routes=set()
    with candidates.open(encoding='utf-8') as src,(target/'reconciled-edges.jsonl').open('x',encoding='utf-8') as out,(target/'master-extension-proposals.jsonl').open('x',encoding='utf-8') as ext:
        for line in src:
            row=json.loads(line)
            if row['namespace']!='SEOUL_BUS':
                raise ValueError('SOURCE_NAMESPACE_MISMATCH')
            if row['reason']=='ROUTE_NOT_IN_MASTER' and row['providerRouteId'] in routes:
                missing_routes.add(row['providerRouteId'])
            status,stops=classify(row,routes)
            edges[status]+=1
            observations[status]+=row['observationRows']
            item=row | dict(reconciliationStatus=status,sourceSnapshotDate=snapshot_date,
                sourceStops=stops,productionReady=False,
                effectiveDateVerified=False,
                limitations=['SNAPSHOT_DATE_IS_NOT_ROUTE_EFFECTIVE_DATE','DIRECTION_UNVERIFIED'])
            mapping.emit(out,item)
            if status=='SOURCE_EXACT_MASTER_GAP':
                mapping.emit(ext,dict(namespace='SEOUL_BUS',providerRouteId=row['providerRouteId'],
                    stops=stops,observedRows=row['observationRows'],sourceSnapshotDate=snapshot_date,
                    canonicalSegmentId=None,productionReady=False,reviewStatus='SOURCE_CORROBORATED_CANDIDATE',
                    requiredEvidence=['MASTER_EFFECTIVE_DATE','DIRECTION','PROVIDER_ID_POLICY_APPROVAL']))
    with (target/'missing-route-proposals.jsonl').open('x',encoding='utf-8') as out:
        for route_id in sorted(missing_routes):
            sequence=routes[route_id]
            ambiguous=[n for n,stops in sequence.items() if len(unique_stops(stops))!=1]
            stops=[stop for n in sorted(sequence) for stop in unique_stops(sequence[n]).values()]
            mapping.emit(out,dict(namespace='SEOUL_BUS',providerRouteId=route_id,
                sourceSnapshotDate=snapshot_date,stops=stops,ambiguousSequences=ambiguous,
                productionReady=False,reviewStatus='SOURCE_ROUTE_CANDIDATE',
                limitations=['EFFECTIVE_DATE_UNVERIFIED','DIRECTION_UNVERIFIED','ID_POLICY_APPROVAL_REQUIRED']))
    with (target/'source-invalid-rows.json').open('x',encoding='utf-8') as out:
        mapping.emit(out,invalid)
    result=dict(sourceRoutes=len(routes),invalidSourceRows=len(invalid),candidateEdges=sum(edges.values()),
                observationRows=sum(observations.values()),edgeStatuses=dict(edges),observationStatuses=dict(observations),
                sourceCorroboratedMissingRoutes=sorted(missing_routes))
    manifest=dict(schemaVersion=1,inputs=inputs,codeSha256=code,helperCodeSha256=helper,
                  sourceSnapshotDate=snapshot_date,productionReady=False,result=result,
                  outputs={p.name:mapping.sha(p) for p in target.iterdir() if p.is_file()})
    with done.open('x',encoding='utf-8') as out:
        mapping.emit(out,manifest)
    return dict(output=str(target),result=result)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('candidates','source','output-root'):
        parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--snapshot-date',required=True)
    args=parser.parse_args()
    print(json.dumps(run(args.candidates,args.source,args.snapshot_date,args.output_root),ensure_ascii=False))
