"""공식 OA-1095 노선별 정류장 XLSX에서 누락 노선만 보충하는 재현 가능한 갱신기.

기존 수도권 노선·좌표·수동 검토 날짜는 보존한다. 순번 구멍을 재번호로 메우지 않으며
공통 crosswalk는 실제 연속 순번만 내보낸다. 기존 노선의 ID열이 달라지면 전체 작업을
중단해 변경 노선 검토를 요구한다. 이름/좌표로 출처 간 ID를 합치지 않는다.
"""
import argparse
from collections import defaultdict
import gzip
import hashlib
import json
from pathlib import Path
import openpyxl
from bus_crosswalk_artifact import write_crosswalk


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def supplement(master, rows, version):
    """노선 ID·정류장 ID·공식 순번을 검증하고 새 노선만 추가한다. 원본 객체는 수정하지 않는다."""
    grouped = defaultdict(list)
    for row in rows:
        if len(row) != 8 or row[0] is None:
            raise ValueError('OA-1095 row schema mismatch')
        rid, name, sequence, sid, ars, stop_name, lon, lat = row
        def identifier(value):
            if isinstance(value, bool) or value is None:
                raise ValueError('missing/boolean ID')
            result = str(value)
            if not result.isdigit():
                raise ValueError('non-integral provider ID')
            return result
        rid, sid = identifier(rid), identifier(sid)
        if isinstance(sequence, bool) or not isinstance(sequence, int) or sequence < 1:
            raise ValueError('invalid stop occurrence')
        if not str(name or '').strip() or not str(stop_name or '').strip():
            raise ValueError('missing route/stop name')
        if not isinstance(lon, (int, float)) or not isinstance(lat, (int, float)) or not 124 <= lon <= 132 or not 33 <= lat <= 39:
            raise ValueError('invalid Korean coordinate')
        grouped[rid].append((str(name), dict(namespace='SEOUL_BUS', stopId=sid, stopName=str(stop_name),
            sequence=sequence, direction=None, boardingAllowed=None, longitude=float(lon), latitude=float(lat),
            mobileCode=None if ars is None else str(ars).zfill(5), coordinateStatus='SEOUL_OA1095_20260902')))
    existing = {r['routeId']: r for r in master['routes'] if r['namespace'] == 'SEOUL_BUS'}
    added, unchanged, gaps = [], 0, []
    for rid, values in sorted(grouped.items()):
        names = {name for name, _ in values}
        stops = sorted((s for _, s in values), key=lambda s: s['sequence'])
        orders = [s['sequence'] for s in stops]
        if len(names) != 1 or len(set(orders)) != len(orders) or len(stops) < 2:
            raise ValueError('duplicate sequence/name or short route: ' + rid)
        if rid in existing:
            old = [(s['sequence'], s['stopId']) for s in existing[rid]['stops']]
            if old != [(s['sequence'], s['stopId']) for s in stops]:
                raise ValueError('existing route topology changed; review required: ' + rid)
            unchanged += 1
            continue
        added.append(dict(namespace='SEOUL_BUS', routeId=rid, routeName=next(iter(names)),
                          region='서울 자료', stops=stops, shapes={}))
        gaps.append(dict(routeId=rid, routeName=next(iter(names)), stopRows=len(stops),
                         missingSequences=sorted(set(range(1, orders[-1]+1))-set(orders))))
    if set(existing)-set(grouped):
        raise ValueError('source missing existing routes; incomplete snapshot')
    result = dict(master, version=version, routes=sorted(master['routes']+added, key=lambda r:(r['namespace'],r['routeId'])))
    return result, dict(existingRoutesPreserved=unchanged, addedRoutes=gaps,
                        policy='preserve official gaps; no edge across missing sequence; no source equivalence inference')


def main():
    """새 경로에만 묶음과 SHA/출처 보고서를 만든다. 운영 DB 활성화는 Spring 관리 적재의 책임이다."""
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('base','source','output'):
        parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--version',required=True)
    args=parser.parse_args()
    if args.output.exists():
        raise ValueError('output already exists')
    raw=args.base.read_bytes(); source=args.source.read_bytes()
    book=openpyxl.load_workbook(args.source,read_only=True,data_only=True)
    try:
        rows=iter(book.active.values); header=next(rows)
        if len(header)!=8 or header[0]!='ROUTE_ID' or header[3]!='NODE_ID' or header[4]!='ARS_ID':
            raise ValueError('official source header mismatch')
        artifact, report=supplement(json.loads(gzip.decompress(raw)),rows,args.version)
    finally:
        book.close()
    report.update(baseVersion=json.loads(gzip.decompress(raw))['version'],baseSha256=sha(raw),
        sourceFile=args.source.name,sourceSha256=sha(source),sourceReferenceDate='2026-09-02',
        sourceUrl='https://data.seoul.go.kr/dataList/OA-1095/S/1/datasetView.do')
    metadata=json.dumps(report,ensure_ascii=False,sort_keys=True,indent=2)+'\n'
    artifact['sourceMetadataSha256']=sha(metadata.encode())
    encoded=gzip.compress(json.dumps(artifact,ensure_ascii=False,separators=(',',':'),allow_nan=False).encode(),mtime=0)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_bytes(encoded)
    args.output.with_suffix('.sources.json').write_text(metadata,encoding='utf-8',newline='\n')
    manifest=dict(version=artifact['version'],routes=len(artifact['routes']),stops=sum(len(r['stops']) for r in artifact['routes']),
        reviewedScopes=len(artifact['reviews']),sha256=sha(encoded),sourceCsvSha256=artifact['sourceCsvSha256'],
        sourceMetadataSha256=artifact['sourceMetadataSha256'])
    args.output.with_suffix('.manifest.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8',newline='\n')
    write_crosswalk(artifact,manifest['sha256'],args.output.with_suffix('').with_suffix('.crosswalk.json.gz'))
    print(json.dumps(manifest))


if __name__=='__main__':
    main()
