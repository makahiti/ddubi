"""공식 BIS CSV를 v6 버스 occurrence와 연결하는 재현 가능한 오프라인 감사.

CSV의 각 셀에 중복 BOM이 있어 제거하되 원본 파일은 수정하지 않는다. 정류장 ID·순번,
노드 순번 연속성, 구간 링크 순서, 도로 링크의 방향 연결을 모두 확인한다. 결과는 검토용
후보이며 역사적 유효기간/AccInfo 동일 ID 검증 없이 운영 대응표로 자동 승격하지 않는다.
"""
import argparse
import csv
import gzip
import hashlib
import json
from collections import defaultdict, Counter
from pathlib import Path


def read_csv(path):
    """ID를 문자열로 보존한다. Excel float 변환이나 이름·좌표 유사 매칭은 하지 않는다."""
    with Path(path).open(encoding='utf-8-sig', newline='') as stream:
        rows = csv.reader(stream)
        headers = [v.replace('\ufeff', '').strip() for v in next(rows)]
        for values in rows:
            if len(values) != len(headers):
                raise ValueError('CSV column count mismatch')
            yield dict(zip(headers, [v.replace('\ufeff', '').strip() for v in values]))


def audit(directory, bus_path):
    """공식 노선→노드→구간→방향 도로 링크의 각 단계 연결률과 제외 사유를 산출한다."""
    root = Path(directory)
    sources = json.loads((root/'sources.json').read_text(encoding='utf-8'))
    by_id = {}
    for source in sources:
        path = root/Path(source['path'].replace('\\', '/')).name
        if hashlib.sha256(path.read_bytes()).hexdigest() != source['sha256']:
            raise ValueError('source SHA mismatch')
        by_id[str(source['datasetId'])] = path
    raw = Path(bus_path).read_bytes()
    bus = json.loads(gzip.decompress(raw))
    edges = [e for e in bus['edges'] if e['namespace'] == 'SEOUL_BUS']
    route_ids = {e['routeId'] for e in edges}
    routes = defaultdict(list)
    for row in read_csv(by_id['58']):
        if row['노선ID'] in route_ids and row['사용여부'] == '1':
            routes[row['노선ID']].append(row)
    sections = defaultdict(dict)
    conflicting_sections = set()
    for row in read_csv(by_id['62']):
        sid, order = row['구간ID'], int(row['구간순번'])
        old = sections[sid].get(order)
        if old is not None and old != row:
            conflicting_sections.add(sid)
        sections[sid][order] = row
    links = {}
    conflicting_links = set()
    for row in read_csv(by_id['63']):
        lid = row['링크ID']
        if lid in links and links[lid] != row:
            conflicting_links.add(lid)
        links[lid] = row
    # 같은 표준 ID라도 BIS 시작·종료 노드가 TOPIS 표준 링크와 일치하는 경우만 후보로 인정한다.
    standard = defaultdict(list)
    for row in read_csv(by_id['7']):
        standard[row['표준링크ID']].append(row)
    nodes = {}
    for rid, rows in routes.items():
        rows.sort(key=lambda r: int(r['노드일련번호']))
        lookup = defaultdict(list)
        for i, row in enumerate(rows):
            lookup[(row['노드ID'], int(row['정류장순번']))].append(i)
        nodes[rid] = (rows, lookup)
    counts = Counter(); candidates = []; rejected = []
    for edge in edges:
        reason = None
        rows, lookup = nodes.get(edge['routeId'], ([], {}))
        starts = lookup.get((edge['fromStopId'], edge['fromOrder']), [])
        ends = lookup.get((edge['toStopId'], edge['toOrder']), [])
        if len(starts) != 1 or len(ends) != 1 or starts[0] >= ends[0]:
            reason = 'STOP_OCCURRENCE_UNRESOLVED'
        path_links = []; section_ids = []
        if reason is None:
            span = rows[starts[0]:ends[0]+1]
            orders = [int(r['노드일련번호']) for r in span]
            if orders != list(range(orders[0], orders[-1]+1)):
                reason = 'NODE_SEQUENCE_GAP'
            else:
                # 각 노드에 도착하는 구간이다. 출발 노드 이전 링크를 포함하지 않는다.
                for node in span[1:]:
                    sid = node['구간ID']; part = sections.get(sid, {})
                    if not part or sid in conflicting_sections or sorted(part) != list(range(1,len(part)+1)):
                        reason = 'SECTION_LINK_MISSING_OR_AMBIGUOUS'; break
                    section_ids.append(sid)
                    for order in sorted(part):
                        lid = part[order]['링크ID']
                        if lid not in links or lid in conflicting_links or links[lid]['사용여부'] != '1':
                            reason = 'ROAD_LINK_MISSING_OR_AMBIGUOUS'; break
                        if not path_links or path_links[-1] != lid:
                            path_links.append(lid)
                    if reason: break
                if not reason and any(links[a]['종료노드ID'] != links[b]['시작노드ID'] for a,b in zip(path_links,path_links[1:])):
                    reason = 'DIRECTIONAL_LINK_CHAIN_DISCONNECTED'
        if reason:
            counts[reason] += 1
            rejected.append(dict(edge, reason=reason))
            continue
        counts['BIS_DIRECTIONAL_PATH'] += 1
        mapped=[]; missing=[]
        for lid in path_links:
            road = links[lid]
            options = [s for s in standard.get(lid, []) if s['시작노드ID'] == road['시작노드ID'] and s['종료노드ID'] == road['종료노드ID']]
            ids = sorted({s['링크ID'] for s in options})
            if len(ids) != 1:
                missing.append(lid)
            elif not mapped or mapped[-1] != ids[0]:
                mapped.append(ids[0])
        counts['TOPIS_PATH_PARTIAL' if missing else 'TOPIS_PATH_COMPLETE'] += 1
        candidates.append(dict(edge, bisLinkIds=path_links, topisServiceLinkIds=mapped,
                               unmappedBisLinkIds=missing, sectionIds=section_ids,
                               state='REVIEW_REQUIRED', evidenceMethod='OFFICIAL_ROUTE_LINKS',
                               reason='VERIFY_SOURCE_VALIDITY_AND_ACCINFO_NAMESPACE'))
    result = dict(masterVersion=bus['masterVersion'],busCrosswalkSha256=hashlib.sha256(raw).hexdigest(),
                  seoulEdgeCount=len(edges), counts=dict(counts),
                  bisPathRate=len(candidates)/len(edges), operationalMappings=0,
                  note='공식 연결 후보. 자료 유효기간 및 AccInfo 링크 동일성 확인 전 운영 활성화 금지.',
                  sourceFiles=[dict(datasetId=s['datasetId'],sha256=s['sha256']) for s in sources])
    (root/'mapping-audit.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    for name, values in [('mapping-candidates.jsonl',candidates),('mapping-rejected.jsonl',rejected)]:
        with (root/name).open('w',encoding='utf-8') as stream:
            for row in values:stream.write(json.dumps(row,ensure_ascii=False)+'\n')
    return result


if __name__ == '__main__':
    p=argparse.ArgumentParser();p.add_argument('--directory',required=True);p.add_argument('--bus-crosswalk',required=True)
    args=p.parse_args();print(json.dumps(audit(args.directory,args.bus_crosswalk),ensure_ascii=True,indent=2))
