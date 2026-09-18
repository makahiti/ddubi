"""관측 후보를 명시적 제공자 namespace와 마스터 순서로 대조한다.

정확 일치도 현재 마스터에 대한 구조 일치일 뿐 과거 유효일/운행 의미의
검증을 대신하지 않는다. 운영 활성화 또는 원본 수정은 하지 않는다.
"""
import argparse
import collections
import gzip
import hashlib
import json
from pathlib import Path


def sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def emit(stream, value):
    stream.write(json.dumps(value, ensure_ascii=False, sort_keys=True) + '\n')


def order(value):
    if isinstance(value, bool) or str(value).strip() != str(int(value)):
        raise ValueError('INVALID_ORDER')
    return int(value)


class Mapper:
    def __init__(self, crosswalk, namespace):
        self.exact = collections.defaultdict(list)
        self.pairs = set()
        self.routes = set()
        self.namespace = namespace
        for edge in crosswalk['edges']:
            if edge['namespace'] != namespace:
                continue
            pair = (str(edge['routeId']), str(edge['fromStopId']), str(edge['toStopId']))
            key = pair + (order(edge['fromOrder']), order(edge['toOrder']))
            self.exact[key].append(edge)
            self.pairs.add(pair)
            self.routes.add(pair[0])
        if not self.routes:
            raise ValueError('NAMESPACE_NOT_IN_MASTER')

    def match(self, row):
        try:
            pair = tuple(str(row[k]) for k in ('providerRouteId', 'fromStopId', 'toStopId'))
            if any(v in ('', 'None') for v in pair):
                raise ValueError('MISSING_ID')
            a, b = order(row['fromOrder']), order(row['toOrder'])
            if a < 1 or b != a + 1 or pair[1] == pair[2]:
                raise ValueError('NON_ADJACENT')
        except (ValueError, TypeError, KeyError, OverflowError):
            return 'INVALID_INPUT', []
        matches = self.exact.get(pair + (a, b), [])
        # 같은 ID라도 방향 정보가 다르면 임의 선택하지 않는다.
        unique = {(e['segmentId'], e.get('direction')) for e in matches}
        if len(unique) == 1:
            return 'EXACT_ID_ORDER_CANDIDATE', matches[:1]
        if len(unique) > 1:
            return 'AMBIGUOUS_MASTER', matches
        if pair[0] not in self.routes:
            return 'ROUTE_NOT_IN_MASTER', []
        if pair in self.pairs:
            return 'ORDER_MISMATCH', []
        return 'EDGE_NOT_IN_MASTER', []


def run(source, crosswalk_path, master_path, namespace, output_root):
    inputs = {str(p.resolve()): sha(p) for p in (source, crosswalk_path, master_path)}
    code = sha(Path(__file__))
    identity = hashlib.sha256(json.dumps([inputs, code, namespace], sort_keys=True).encode()).hexdigest()
    target = output_root / identity[:20]
    done = target / 'complete.json'
    if done.exists():
        manifest = json.loads(done.read_text(encoding='utf-8'))
        if not all(sha(target / n) == digest for n, digest in manifest['outputs'].items()):
            raise ValueError('OUTPUT_CHECKSUM_MISMATCH')
        return {'reused': str(target), 'result': manifest['result']}
    with gzip.open(crosswalk_path, 'rt', encoding='utf-8') as stream:
        crosswalk = json.load(stream)
    with gzip.open(master_path, 'rt', encoding='utf-8') as stream:
        master = json.load(stream)
    if crosswalk['masterSha256'] != sha(master_path) or crosswalk['masterVersion'] != master['version']:
        raise ValueError('CROSSWALK_MASTER_MISMATCH')
    mapper = Mapper(crosswalk, namespace)
    target.mkdir(parents=True, exist_ok=False)
    counts = collections.Counter()
    routes = collections.defaultdict(collections.Counter)
    missing = collections.Counter()
    with source.open(encoding='utf-8') as src, (target / 'mapped-candidates.jsonl').open('x', encoding='utf-8') as out:
        for line in src:
            row = json.loads(line)
            status, edges = mapper.match(row)
            counts[status] += 1
            routes[str(row.get('providerRouteId'))][status] += 1
            exact = status == 'EXACT_ID_ORDER_CANDIDATE'
            row.update(mappingStatus=status, mappingNamespace=namespace,
                       mappingMasterVersion=master['version'],
                       canonicalSegmentId=edges[0]['segmentId'] if exact else None,
                       mappedDirection=edges[0].get('direction') if exact else None,
                       productionReady=False,
                       mappingLimitations=['MASTER_EFFECTIVE_DATE_UNVERIFIED', 'SOURCE_ID_SEMANTICS_UNVERIFIED'])
            emit(out, row)
            if status in ('ROUTE_NOT_IN_MASTER', 'EDGE_NOT_IN_MASTER', 'ORDER_MISMATCH'):
                key = tuple(row[k] for k in ('providerRouteId','fromStopId','toStopId','fromOrder','toOrder')) + (status,)
                missing[key] += 1
    # 원본에 나타난 순서를 보존한 마스터 확장 검토 재료. 정식 마스터가 아니다.
    with (target / 'master-extension-candidates.jsonl').open('x', encoding='utf-8') as out:
        for key, count in sorted(missing.items(), key=lambda item: str(item[0])):
            candidate = dict(zip(('providerRouteId','fromStopId','toStopId','fromOrder','toOrder','reason'), key))
            emit(out, candidate | dict(namespace=namespace, observationRows=count,
                productionReady=False, canonicalSegmentId=None,
                requiredEvidence=['PROVIDER_ID_VERIFICATION','STOP_COORDINATES','ROUTE_DIRECTION','MASTER_EFFECTIVE_DATE']))
    with (target / 'route-coverage.json').open('x', encoding='utf-8') as out:
        emit(out, routes)
    result = dict(totalRows=sum(counts.values()), routeCount=len(routes), statuses=dict(counts),
                  distinctExtensionCandidates=len(missing))
    manifest = dict(schemaVersion=1, inputs=inputs, codeSha256=code, namespace=namespace,
        masterVersion=master['version'], productionReady=False, result=result,
        outputs={f.name:sha(f) for f in target.iterdir() if f.is_file()},
        blockedBy=['MASTER_EFFECTIVE_DATE_UNVERIFIED','SOURCE_ID_SEMANTICS_UNVERIFIED',
                   'SOURCE_AVAILABILITY_UNKNOWN','WEATHER_NOT_JOINED'])
    with done.open('x', encoding='utf-8') as out:
        emit(out, manifest)
    return {'output': str(target), 'result': result}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('input','crosswalk','master','output-root'):
        parser.add_argument('--'+name, type=Path, required=True)
    parser.add_argument('--namespace', required=True)
    args = parser.parse_args()
    print(json.dumps(run(args.input, args.crosswalk, args.master, args.namespace, args.output_root), ensure_ascii=False))
