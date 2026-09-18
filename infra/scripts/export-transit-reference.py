"""Build bounded, hash-verified SUBWAY/ROAD reference lookups without operational promotion."""
import argparse
import collections
import gzip
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import tempfile


MAX_SHARD_BYTES=8*1024*1024
ID_PATTERN=re.compile(r'^(SUBWAY_PATTERN|SUBWAY_SEGMENT|SUBWAY_HEADWAY|ROAD_OCCURRENCE)_([a-f0-9]{64})$')
COMMON_LIMITATIONS=['REFERENCE_ONLY_NOT_ROUTE_EVIDENCE','NOT_REALTIME_OR_OPERATIONALLY_APPROVED']


def digest(path):
    h=hashlib.sha256()
    with Path(path).open('rb') as f:
        for block in iter(lambda:f.read(1048576),b''):h.update(block)
    return h.hexdigest()


def encoded(value):
    return json.dumps(value,ensure_ascii=False,separators=(',',':'),sort_keys=True).encode('utf-8')


def write_json(path,value):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_bytes(encoded(value)+b'\n')


def safe_relative(root,name):
    candidate=Path(name)
    if candidate.is_absolute() or any(part in ('..','') for part in candidate.parts):raise ValueError('UNSAFE_PACKAGE_PATH')
    target=(root/candidate).resolve()
    if not target.is_relative_to(root.resolve()):raise ValueError('UNSAFE_PACKAGE_PATH')
    return target


def verified(root,manifest_name='complete.json'):
    manifest=json.loads((root/manifest_name).read_text(encoding='utf-8'))
    for name,sha in manifest['outputs'].items():
        if digest(safe_relative(root,name))!=sha:raise ValueError('SOURCE_CHECKSUM_MISMATCH:'+name)
    return manifest


def rows(path):
    with path.open(encoding='utf-8') as f:
        for line in f:
            if line.strip():yield json.loads(line)


def reference_id(record_type,source_version,identity):
    return record_type+'_'+hashlib.sha256(encoded([source_version,identity])).hexdigest()


def master_key(edge):
    return tuple(edge[k] for k in ('namespace','routeId','fromOrder','toOrder','fromStopId','toStopId'))


def compatibility(edge,index):
    matches=index.get(master_key(edge),[])
    if not matches:return 'MISSING_CURRENT_MASTER'
    if len(matches)!=1:return 'AMBIGUOUS_CURRENT_MASTER'
    if any(edge.get(k)!=matches[0].get(k) for k in ('segmentId','direction')):return 'OCCURRENCE_FIELDS_CHANGED'
    return 'EXACT_MATCH_CURRENT_MASTER'


class Writer:
    def __init__(self,root,kind,source_version,limitations):
        self.root=root;self.kind=kind;self.source_version=source_version;self.limitations=limitations
        root.mkdir(parents=True,exist_ok=False)
        self.temp=tempfile.TemporaryDirectory(prefix='ddubi-transit-package-')
        self.db=sqlite3.connect(str(Path(self.temp.name)/'records.sqlite'))
        self.db.execute('CREATE TABLE records (reference_id TEXT PRIMARY KEY,bucket TEXT NOT NULL,payload TEXT NOT NULL,catalog TEXT NOT NULL)')
        self.counts=collections.Counter();self.examples=[]

    def add(self,record_type,identity,data,source_identity,**metadata):
        if data.get('productionReady') is not False:raise ValueError('UNAPPROVED_SOURCE_FLAG_REQUIRED')
        ref=reference_id(record_type,source_identity,identity)
        detail=dict(referenceId=ref,kind=self.kind,recordType=record_type,sourceVersion=self.source_version,
            referenceOnly=True,productionReady=False,productionEligible=False,realtimeApplied=False,currentnessVerified=False,
            sourcePeriod=None,limitations=self.limitations,data=data,**metadata)
        catalog={k:detail.get(k) for k in ('referenceId','kind','recordType','label','region','routeId','sourcePatternId','masterCompatibility') if detail.get(k) is not None}
        raw=encoded(detail).decode('utf-8')
        if len(raw.encode('utf-8'))>MAX_SHARD_BYTES:raise ValueError('SINGLE_RECORD_TOO_LARGE')
        try:self.db.execute('INSERT INTO records VALUES (?,?,?,?)',(ref,ref[-64:-62],raw,encoded(catalog).decode('utf-8')))
        except sqlite3.IntegrityError as exc:raise ValueError('DUPLICATE_REFERENCE_ID:'+ref) from exc
        self.counts[record_type]+=1
        if len(self.examples)<3:self.examples.append(ref)
        if sum(self.counts.values())%2000==0:self.db.commit()
        return ref

    def finish(self,counts,**metadata):
        self.db.commit();shards={}
        with (self.root/'catalog.jsonl').open('w',encoding='utf-8',newline='\n') as f:
            for catalog, in self.db.execute('SELECT catalog FROM records ORDER BY reference_id'):f.write(catalog+'\n')
        for bucket, in self.db.execute('SELECT DISTINCT bucket FROM records ORDER BY bucket'):
            entries={ref:json.loads(payload) for ref,payload in self.db.execute('SELECT reference_id,payload FROM records WHERE bucket=? ORDER BY reference_id',(bucket,))}
            path='details/'+bucket+'.json';target=self.root/path
            write_json(target,dict(schemaVersion=1,kind=self.kind,entries=entries))
            size=target.stat().st_size
            if size>MAX_SHARD_BYTES:raise ValueError('SHARD_TOO_LARGE:'+path)
            shards[bucket]=dict(path=path,sha256=digest(target),sizeBytes=size)
        manifest=dict(schemaVersion=1,artifactType='TRANSIT_REFERENCE_LOOKUP',kind=self.kind,
            sourceVersion=self.source_version,productionReady=False,realtimeApplied=False,referenceOnly=True,
            counts=dict(recordCount=sum(self.counts.values()),**counts),limitations=self.limitations,
            entriesIndex=dict(algorithm='SHA256_PREFIX_2',shards=shards),
            catalog=dict(path='catalog.jsonl',sha256=digest(self.root/'catalog.jsonl')),exampleReferenceIds=self.examples,**metadata)
        write_json(self.root/'manifest.json',manifest)
        if (self.root/'manifest.json').stat().st_size>1024*1024:raise ValueError('MANIFEST_TOO_LARGE')
        self.db.close();self.temp.cleanup()
        return manifest


def build(subway,road,current_bus,output):
    if output.exists():raise ValueError('OUTPUT_EXISTS')
    subway_manifest=verified(subway)
    road_manifest=verified(road)
    if road_manifest.get('state')!='COMPLETE' or road_manifest.get('exitCode')!=0:raise ValueError('ROAD_INCOMPLETE')
    if road_manifest.get('productionReady') is not False or road_manifest.get('approvedMappings')!=0:raise ValueError('ROAD_REFERENCE_FLAGS_INVALID')
    source_sub=digest(subway/'complete.json');source_road=digest(road/'complete.json');master_sha=digest(current_bus)
    source_version=hashlib.sha256(encoded([source_sub,source_road,master_sha,digest(__file__)])).hexdigest()
    output.mkdir(parents=True)
    source_inputs={str(subway/'complete.json'):source_sub,str(road/'complete.json'):source_road,str(current_bus):master_sha}
    package=json.loads((subway/'reference-master-bundle.json').read_text(encoding='utf-8'))
    if package.get('productionReady') is not False or package.get('usage')!='UNDATED_SCHEDULE_REFERENCE':raise ValueError('SUBWAY_NOT_UNDATED_REFERENCE')
    if any(package['availability'].get(k) is not None for k in ('sourcePublishedAt','sourceAvailableAt','currentValidFrom','currentValidUntil')):raise ValueError('UNVERIFIED_SUBWAY_VALIDITY')
    limitations=COMMON_LIMITATIONS+package['limitations']
    sw=Writer(output/'subway','SUBWAY',source_version,limitations)
    patterns={p['sourcePatternId']:p for p in package['patterns']}
    if len(patterns)!=len(package['patterns']):raise ValueError('DUPLICATE_SOURCE_PATTERN')
    counts=collections.Counter()
    for p in package['patterns']:
        sw.add('SUBWAY_PATTERN',[p['sourcePatternId']],p,source_sub,sourcePatternId=p['sourcePatternId'],
               label=p['route']['route_long_name'],routeId=p['route']['route_id'],region=p['region'],sourceMasterVersion=package['masterDataVersion'])
        counts['patternCount']+=1
    for filename,record_type in [('scheduled-segment-reference.jsonl','SUBWAY_SEGMENT'),('scheduled-headway-reference.jsonl','SUBWAY_HEADWAY')]:
        for row in rows(subway/filename):
            p=patterns[row['sourcePatternId']]
            identity=[row['sourcePatternId'],row['serviceId'],row['sequence']]
            if record_type=='SUBWAY_HEADWAY':identity.append(row['serviceElapsedHour'])
            sw.add(record_type,identity,row,source_sub,sourcePatternId=p['sourcePatternId'],
                parentReferenceId=reference_id('SUBWAY_PATTERN',source_sub,[p['sourcePatternId']]),
                label=p['route']['route_long_name'],routeId=p['route']['route_id'],region=p['region'],sourceMasterVersion=package['masterDataVersion'])
            counts['scheduledSegmentCount' if record_type=='SUBWAY_SEGMENT' else 'headwayBucketCount']+=1
    expected=subway_manifest['summary']
    if (counts['patternCount'],counts['scheduledSegmentCount'],counts['headwayBucketCount'])!=(expected['patternCount'],expected['referenceEdgeCount'],expected['headwayBucketCount']):raise ValueError('SUBWAY_COVERAGE_MISMATCH')
    subway_result=sw.finish(dict(counts),sourceMasterVersion=package['masterDataVersion'],availability=package['availability'])
    current=json.loads(gzip.decompress(current_bus.read_bytes()));index=collections.defaultdict(list)
    for edge in current['edges']:index[master_key(edge)].append(edge)
    limits=COMMON_LIMITATIONS+['ACCINFO_NAMESPACE_EQUIVALENCE_UNVERIFIED','COMPLETE_DIRECTIONAL_PATH_NOT_REVIEWED','SOURCE_VALIDITY_UNVERIFIED']
    rw=Writer(output/'road','ROAD',source_version,limits);counts=collections.Counter();old_versions=set()
    for name in road_manifest['checkpoints']:
        checkpoint=json.loads(safe_relative(road,name).read_text(encoding='utf-8'))
        batch=safe_relative(road,checkpoint['directory'])
        relative_candidates=next(n for n in checkpoint['outputs'] if n.replace('\\','/')=='occurrence/occurrence-candidates.jsonl')
        relative_report=next(n for n in checkpoint['outputs'] if n.replace('\\','/')=='occurrence/report.json')
        candidate_file=safe_relative(batch,relative_candidates);report_file=safe_relative(batch,relative_report)
        for relative,path in [(relative_candidates,candidate_file),(relative_report,report_file)]:
            if digest(path)!=checkpoint['outputs'][relative]:raise ValueError('ROAD_BATCH_CHECKSUM_MISMATCH:'+relative)
        batch_report=json.loads(report_file.read_text(encoding='utf-8'));old_versions.add(batch_report['masterVersion'])
        for row in rows(candidate_file):
            if row.get('runtimeMappingApproved') is not False:raise ValueError('ROAD_SOURCE_PROMOTED')
            edge=row['edge'];status=compatibility(edge,index)
            # Detailed raw sample geometry stays in the checksummed source bundle; all occurrence identities and conclusions remain queryable.
            data={k:v for k,v in row.items() if k!='reviewSamples'}
            identity=[*master_key(edge),row['shapeId'],row['fromShapeVertex'],row['toShapeVertex']]
            rw.add('ROAD_OCCURRENCE',identity,data,source_road,label='버스 구간 도로 연결 검토',routeId=edge['routeId'],
                sourceMasterVersion=batch_report['masterVersion'],currentMasterVersion=current['masterVersion'],masterCompatibility=status,
                sourceEvidence=dict(checkpoint=name,relativeArtifact=(Path(checkpoint['directory'])/relative_candidates).as_posix(),sha256=checkpoint['outputs'][relative_candidates]))
            counts['occurrenceCount']+=1;counts[status]+=1;counts[row['candidatePath']['state']]+=1
    if counts['occurrenceCount']!=road_manifest['counts']['EXACT_STOP_OCCURRENCE_REVIEW']:raise ValueError('ROAD_COVERAGE_MISMATCH')
    road_result=rw.finish(dict(counts),sourceMasterVersions=sorted(old_versions),currentMasterVersion=current['masterVersion'],currentMasterSha256=master_sha)
    summary=dict(productionReady=False,sourceVersion=source_version,subway=subway_result['counts'],road=road_result['counts'])
    write_json(output/'summary.json',summary)
    outputs={p.relative_to(output).as_posix():digest(p) for p in output.rglob('*') if p.is_file()}
    write_json(output/'complete.json',dict(productionReady=False,inputs=source_inputs,scriptSha256=digest(__file__),outputs=outputs,summary=summary))
    print(json.dumps(summary,ensure_ascii=False))


def lookup(package,ref,expected_sha):
    manifest_file=package/'manifest.json'
    if digest(manifest_file)!=expected_sha:raise ValueError('MANIFEST_SHA_MISMATCH')
    manifest=json.loads(manifest_file.read_text(encoding='utf-8'))
    match=ID_PATTERN.fullmatch(ref)
    if not match or not ref.startswith(manifest['kind']+'_'):raise ValueError('INVALID_REFERENCE_ID')
    shard=manifest['entriesIndex']['shards'].get(match[2][:2])
    if not shard:return None
    path=safe_relative(package,shard['path'])
    if path.stat().st_size!=shard['sizeBytes'] or shard['sizeBytes']>MAX_SHARD_BYTES or digest(path)!=shard['sha256']:raise ValueError('SHARD_INVALID')
    data=json.loads(path.read_text(encoding='utf-8'))
    result=data['entries'].get(ref)
    if result and (result.get('productionReady') is not False or result.get('referenceOnly') is not True):raise ValueError('REFERENCE_FLAGS_INVALID')
    return result


def search(package,field,value,limit):
    if field not in ('routeId','sourcePatternId','recordType','region','masterCompatibility') or not 1<=limit<=100:raise ValueError('INVALID_SEARCH')
    manifest=json.loads((package/'manifest.json').read_text(encoding='utf-8'));info=manifest['catalog'];path=safe_relative(package,info['path'])
    if digest(path)!=info['sha256']:raise ValueError('CATALOG_SHA_MISMATCH')
    result=[]
    for row in rows(path):
        if row.get(field)==value:
            result.append(row)
            if len(result)>limit:return dict(entries=result[:limit],truncated=True)
    return dict(entries=result,truncated=False)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);sub=p.add_subparsers(dest='command',required=True)
    b=sub.add_parser('build');b.add_argument('--subway',type=Path,required=True);b.add_argument('--road',type=Path,required=True);b.add_argument('--current-bus-crosswalk',type=Path,required=True);b.add_argument('--output',type=Path,required=True)
    q=sub.add_parser('lookup');q.add_argument('--package',type=Path,required=True);q.add_argument('--reference-id',required=True);q.add_argument('--manifest-sha256',required=True)
    s=sub.add_parser('search');s.add_argument('--package',type=Path,required=True);s.add_argument('--field',required=True);s.add_argument('--value',required=True);s.add_argument('--limit',type=int,default=20)
    a=p.parse_args()
    if a.command=='build':build(a.subway,a.road,a.current_bus_crosswalk,a.output)
    elif a.command=='lookup':print(json.dumps(lookup(a.package,a.reference_id,a.manifest_sha256),ensure_ascii=False))
    else:print(json.dumps(search(a.package,a.field,a.value,a.limit),ensure_ascii=False))
