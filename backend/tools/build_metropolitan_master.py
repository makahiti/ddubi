"""통합 CSV·메타데이터를 Java 서빙 artifact로 변환한다.

표시용 엑셀을 만드는 도구가 아니라 데이터 파이프라인의 재현 가능한 변환기다.
공식 ID, 선행 0, 순번, 좌표 결측, 승하차 unknown을 보존한다. 원본 파일은 수정하지
않고 gzip의 시간을 0으로 고정한다. 검토 별칭은 1~20 입력과 원본 마스터 해시가
모두 일치할 때만 옮기며 날짜를 연장하지 않는다. Java 적재기가 다시 품질 검사한다.
"""
import argparse
import collections
import csv
import gzip
import hashlib
import json
from pathlib import Path
from bus_crosswalk_artifact import write_crosswalk


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def digest(value):
    return sha(json.dumps(value, ensure_ascii=False, sort_keys=True, allow_nan=False).encode())


def request_digest(step):
    """Java Double.toString과 같은 일반 좌표 표현으로 전체 정류장·경로 지문을 만든다."""
    def part(value):
        # Java String.length는 UTF-16 단위다. 정류장에 보조 평면 문자가 있어도 같은 지문을 만든다.
        return str(len(value.encode('utf-16-le')) // 2) + ':' + value
    names = ''.join(part(s['name']) for s in step['properties']['stops'])
    points = ''.join(part(str(float(v))) for point in step['path']['points'] for v in point)
    return sha((names + '|' + points).encode())


def build(source, responses):
    """CSV의 각 행을 공식 노선별로 묶는다. 중복/누락 메타데이터를 조용히 버리지 않는다."""
    csv_path = source / '통합마스터_v6.csv'
    meta_path = source / 'master-metadata.json'
    meta = json.loads(meta_path.read_text(encoding='utf-8-sig'))
    grouped, original = collections.defaultdict(list), collections.defaultdict(list)
    inactive = set(meta['inactiveEquivalentSources'])
    with csv_path.open(encoding='utf-8-sig', newline='') as stream:
        for row in csv.DictReader(stream):
            key = row['route_namespace'] + '.' + row['route_id']
            extra = json.loads(row['stop_extra_json'])
            lon = float(row['longitude']) if row['longitude'] else None
            lat = float(row['latitude']) if row['latitude'] else None
            if row['boarding_allowed'] not in ('', '0', '1'):
                raise ValueError('승하차 플래그 오류')
            raw = dict(extra, stopId=row['stop_id'], stopName=row['stop_name'], sequence=int(row['stop_sequence']), longitude=lon, latitude=lat)
            if row['direction']: raw['officialDirection'] = row['direction']
            if row['boarding_allowed']: raw['boardingAllowed'] = row['boarding_allowed'] == '1'
            original[key].append(raw)
            if row['active_candidate'] != '1' or key in inactive:
                continue
            grouped[key].append(dict(namespace=row['stop_namespace'], stopId=row['stop_id'],
                stopName=row['stop_name'], sequence=int(row['stop_sequence']), direction=row['direction'] or None,
                boardingAllowed=None if row['boarding_allowed'] == '' else row['boarding_allowed'] == '1',
                longitude=lon, latitude=lat, mobileCode=extra.get('mobileCode'), coordinateStatus=row['coordinate_status']))
    routes=[]
    for key, stops in sorted(grouped.items()):
        r=meta['routes'][key]
        ns, rid=meta['keymap'][key]
        if len({s['sequence'] for s in stops})!=len(stops): raise ValueError('중복 순번: '+key)
        routes.append(dict(namespace=ns, routeId=rid, routeName=r['routeName'],
            region=meta['regions'].get(key,{}).get('region'), stops=sorted(stops,key=lambda s:s['sequence']),
            shapes={d:[dict(longitude=p[0],latitude=p[1]) for p in ps] for d,ps in r.get('shapes',{}).items() if ps}))
    # 검토한 입력 범위만 이식한다. v6 결과를 정답 테이블로 사용하는 방식은 금지한다.
    records=json.loads((source/'reviewed-aliases.json').read_text(encoding='utf-8-sig'))
    if isinstance(records,dict): raise ValueError('검토 대응표 배열 필요')
    reviews=[]
    if responses:
        for i in range(1,21):
            response=json.loads((responses/(str(i)+'.json')).read_text(encoding='utf-8-sig'))
            for route in response['routes']:
                for step in route['steps']:
                    if step['properties']['type']!='BUS': continue
                    scope=digest(dict(stops=step['properties'].get('stops'),points=step.get('path',{}).get('points')))
                    for record in records:
                        if record['scopeDigest']!=scope: continue
                        key=record['officialRouteId']
                        reconstructed=dict(meta['routes'][key],stops=original[key])
                        if digest(reconstructed)!=record['officialRouteSha256']:
                            raise ValueError('검토 마스터 변경: '+key)
                        reviews.append(dict(observedDate=record['observedDate'], providerName=record['providerName'],
                            requestSha256=request_digest(step),routeKey=key,
                            stopIds=record.get('reviewedOfficialStopIds',[]),mobileCodes=record.get('reviewedMobileCodes',[])))
                    # v4에서 확인한 예약 노선의 공식 부분경로는 이름열까지 동일한 입력에만 더 좁게 바인딩한다.
                    for record in meta.get('aliases',[]):
                        key=record['officialRouteId']
                        if digest(dict(meta['routes'][key],stops=original[key]))!=record['officialRouteSha256']:
                            raise ValueError('이전 검토 마스터 변경: '+key)
                        approved={(sid,seq) for sid,seq in record['reviewedOfficialPath']}
                        path=[s for s in original[key] if (s['stopId'],s['sequence']) in approved]
                        names=[s['stopName'] for s in path if s.get('boardingAllowed',True)]
                        if names!=[s['name'] for s in step['properties']['stops']]:continue
                        reviews.append(dict(observedDate=record['observedDate'],providerName=record['providerName'],
                            requestSha256=request_digest(step),routeKey=key,stopIds=[s['stopId'] for s in path],mobileCodes=[]))
    reviews=list({digest(r):r for r in reviews}.values())
    return dict(schemaVersion=1,version=meta['version'],sourceCsvSha256=sha(csv_path.read_bytes()),
        sourceMetadataSha256=sha(meta_path.read_bytes()),routes=routes,reviews=reviews)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source',type=Path,required=True)
    parser.add_argument('--responses',type=Path)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    artifact=build(args.source,args.responses)
    raw=json.dumps(artifact,ensure_ascii=False,separators=(',',':'),allow_nan=False).encode()
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_bytes(gzip.compress(raw,mtime=0))
    manifest=dict(version=artifact['version'],routes=len(artifact['routes']),stops=sum(len(r['stops']) for r in artifact['routes']),
        reviewedScopes=len(artifact['reviews']),sha256=sha(args.output.read_bytes()),
        sourceCsvSha256=artifact['sourceCsvSha256'],sourceMetadataSha256=artifact['sourceMetadataSha256'])
    args.output.with_suffix('.manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    write_crosswalk(artifact,manifest['sha256'],args.output.with_suffix('').with_suffix('.crosswalk.json.gz'))
    print(json.dumps(manifest,ensure_ascii=False))


if __name__=='__main__': main()
