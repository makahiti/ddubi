"""검증된 수도권 마스터의 인접 순번을 전 서비스용 대응표로 내보낸다.

표시 이름·좌표 근접으로 ID를 추측하지 않는다. 입력 artifact의 SHA와 버전을 묶고,
노선 순번이 연속하지 않은 곳은 edge를 생성하지 않아 누락을 숨기지 않는다.
서로 다른 출처는 독립 canonical ID다. 실시간 sectOrd 의미와 학습 인코더는 이 파일에 없다.
"""
import gzip
import hashlib
import json
import re
from pathlib import Path

POLICY = "canonical-adjacent-v2"
NAMESPACES = {"SEOUL_BUS", "GBIS", "INCHEON_PUBLIC_CSV"}


def component(namespace, value):
    """Java CanonicalSegmentIdFactory와 같은 50자 구성요소를 만들고 잘라내지 않는다."""
    if namespace not in NAMESPACES or not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9._-]{1,50}", value):
        raise ValueError("invalid source ID")
    result = value if namespace == "SEOUL_BUS" else namespace + "." + value
    if len(result) > 50:
        raise ValueError("canonical component too long")
    return result


def build_crosswalk(master, master_sha):
    """각 edge에 원본 노선·정류장 ID, 순번과 공통 ID를 보존한다. 파싱 오류는 적재를 중단한다."""
    edges, gaps, keys = [], 0, set()
    for route in master['routes']:
        ns, rid = route['namespace'], route['routeId']
        for a, b in zip(route['stops'], route['stops'][1:]):
            if a['namespace'] != ns or b['namespace'] != ns:
                raise ValueError('mixed route/stop namespaces')
            if b['sequence'] != a['sequence'] + 1:
                gaps += 1
                continue
            key = (ns, rid, a['sequence'])
            if key in keys:
                raise ValueError('duplicate route sequence')
            keys.add(key)
            edges.append(dict(namespace=ns, routeId=rid, fromStopId=a['stopId'], toStopId=b['stopId'],
                fromOrder=a['sequence'], toOrder=b['sequence'], direction=a['direction'],
                segmentId='BUS:' + ':'.join(component(ns, v) for v in [rid, a['stopId'], b['stopId']])))
    return dict(schemaVersion=1, idPolicy=POLICY, masterVersion=master['version'], masterSha256=master_sha,
                edgeCount=len(edges), sequenceGaps=gaps, edges=edges)


def write_crosswalk(master, master_sha, path):
    """gzip timestamp=0으로 같은 입력에서 같은 SHA를 생성한다. 운영 활성화는 하지 않는다."""
    payload=build_crosswalk(master,master_sha)
    raw=gzip.compress(json.dumps(payload,ensure_ascii=False,separators=(',',':'),allow_nan=False).encode(),mtime=0)
    path=Path(path);path.write_bytes(raw)
    manifest={k:v for k,v in payload.items() if k!='edges'}
    manifest['sha256']=hashlib.sha256(raw).hexdigest()
    path.with_suffix('.manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
    return manifest
