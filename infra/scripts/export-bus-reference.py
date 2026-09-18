"""검증된 SQLite 기준값을 BE 참고자료 청크로 변환한다. 네트워크/DB 쓰기 없음.

실제 관측과 시간대 평균을 서로 다른 sampleKind로 보존한다. 두 종류를 합산하지 않는다.
--max-chunks는 계약 시험용이며 전체 내보내기 완료로 표시하지 않는다.
"""
import argparse
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import sqlite3
import math
import datetime
import re
import collections


def sha(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda:f.read(1024*1024),b''): h.update(block)
    return h.hexdigest()


def convert(row,master_version,source_sha):
    route,a,b,ao,bo,dow,hour,total,count,start,end=row
    mean=total/count if count else float('nan')
    if not math.isfinite(mean) or not 0<mean<=86400 or int(ao)<1 or int(bo)!=int(ao)+1 or str(a)==str(b):
        raise ValueError('INVALID_REFERENCE_ROW')
    if any(not re.fullmatch(r'[A-Za-z0-9._-]{1,80}',str(v)) for v in (route,a,b)):
        raise ValueError('INVALID_PROVIDER_ID')
    if not 1<=dow<=7 or not 0<=hour<=23 or count<1:
        raise ValueError('INVALID_REFERENCE_KEY')
    if count>(datetime.date.fromisoformat(end)-datetime.date.fromisoformat(start)).days+1:
        raise ValueError('INVALID_OBSERVED_DATES')
    return dict(masterVersion=master_version,namespace='SEOUL_BUS',routeId=str(route),
        fromStopId=str(a),toStopId=str(b),fromOrder=int(ao),toOrder=int(bo),dow=dow,hourSlot=hour,
        sampleKind='HOURLY_SOURCE_MEAN',meanSeconds=mean,sampleCount=count,observedDates=count,
        observationStart=start,observationEnd=end,sourceSha256=source_sha)


def convert_actual(row,edge_index,master_version,source_sha):
    key=(row['segmentId'],row['fromOrder'],row['toOrder'])
    matches=edge_index.get(key,set())
    if len(matches)!=1: raise ValueError('ACTUAL_ID_NOT_UNIQUE_IN_MASTER')
    route,a,b=next(iter(matches))
    dates=row['observedDates'];samples=row['sampleCount']
    if samples<dates or not isinstance(samples,int): raise ValueError('INVALID_SAMPLE_COUNT')
    result=convert((route,a,b,row['fromOrder'],row['toOrder'],row['dow'],row['hourSlot'],
        row['meanSeconds']*dates,dates,row['observationStart'],row['observationEnd']),master_version,source_sha)
    result.update(sampleKind='ACTUAL_TRAVERSAL_MEAN',sampleCount=samples)
    return result


def run(folder,output,max_chunks=0):
    spec=importlib.util.spec_from_file_location('verifier',Path(__file__).with_name('verify-transit-bundle.py'))
    verifier=importlib.util.module_from_spec(spec);spec.loader.exec_module(verifier)
    if not verifier.verify(folder)['integrityVerified']: raise ValueError('BUNDLE_INTEGRITY_FAILED')
    manifest=json.loads((folder/'complete.json').read_text(encoding='utf-8'))
    meta=json.loads((folder/'master-additions-candidate.json').read_text(encoding='utf-8'))
    master=meta['parentMasterVersion']
    # 완료manifest 자체 지문을 버전으로 쓰므로 입력/코드/마스터가 바뀌면 별도 데이터 버전이다.
    code_sha=sha(Path(__file__))
    version='bus-ref-'+hashlib.sha256((sha(folder/'complete.json')+code_sha).encode()).hexdigest()[:24]
    output.mkdir(parents=True,exist_ok=False)
    files={};batch=[];count=0;limited=False;scanned=0;rejected=collections.Counter();kinds=collections.Counter()
    def save(name,value):
        temporary=output/(name+'.tmp')
        temporary.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
        temporary.replace(output/name)
    save('job.json',dict(status='RUNNING',dataVersion=version,source=str(folder.resolve()),
        startedAt=datetime.datetime.now(datetime.timezone.utc).isoformat()))
    def flush():
        nonlocal count,batch
        payload=json.dumps(dict(rows=batch),ensure_ascii=False,separators=(',',':'),allow_nan=False).encode()
        path=output/f'{len(files):06d}.json.gz'
        partial=path.with_suffix('.partial')
        partial.write_bytes(gzip.compress(payload,compresslevel=1,mtime=0));partial.replace(path);files[path.name]=sha(path)
        count+=len(batch);batch=[]
        if len(files)%100==0:
            save('progress.json',dict(rows=count,chunks=len(files),scanned=scanned,rejected=dict(rejected)))
            print(json.dumps(dict(stage='EXPORT',rows=count,chunks=len(files))),flush=True)
    def add(row):
        batch.append(row);kinds[row['sampleKind']]+=1
        if len(batch)==1000: flush()
    def quarantine(name,index,row,reason):
        rejected[reason]+=1
        # 부적합 행은 폐기하지 않고 원본 키와 제외 사유를 보존한다.
        with (output/'quarantine.jsonl').open('a',encoding='utf-8',newline='\n') as stream:
            stream.write(json.dumps(dict(source=name,sourceRowid=index,row=row,reason=reason),ensure_ascii=False)+'\n')
    for name in sorted(manifest['outputs']):
        if not name.startswith('hourly-part-') or not name.endswith('.sqlite'): continue
        with sqlite3.connect((folder/name).resolve().as_uri()+'?mode=ro',uri=True) as db:
            cursor=db.execute('SELECT route,a,b,ao,bo,dow,hour,totalSeconds,observedDates,observationStart,observationEnd FROM hourly_reference ORDER BY rowid')
            for source_index,row in enumerate(cursor,1):
                if max_chunks and len(files)>=max_chunks:
                    limited=True;break
                scanned+=1
                try: converted=convert(row,master,manifest['outputs'][name])
                except (ValueError,TypeError,OverflowError) as error:
                    reason=str(error) if str(error) in ('INVALID_REFERENCE_ROW','INVALID_PROVIDER_ID','INVALID_REFERENCE_KEY','INVALID_OBSERVED_DATES') else 'INVALID_FIELD_TYPE'
                    quarantine(name,source_index,row,reason)
                    continue
                add(converted)
        if limited: break
    if not limited:
        crosses=[(Path(p),digest) for p,digest in manifest['inputs'].items() if p.endswith('.crosswalk.json.gz')]
        if len(crosses)!=1 or sha(crosses[0][0])!=crosses[0][1]: raise ValueError('CROSSWALK_SOURCE_CHANGED')
        with gzip.open(crosses[0][0],'rt',encoding='utf-8') as stream: crosswalk=json.load(stream)
        if crosswalk['masterVersion']!=master: raise ValueError('CROSSWALK_VERSION_MISMATCH')
        edge_index=collections.defaultdict(set)
        for e in crosswalk['edges']:
            if e['namespace']=='SEOUL_BUS':
                edge_index[(e['segmentId'],e['fromOrder'],e['toOrder'])].add((str(e['routeId']),str(e['fromStopId']),str(e['toStopId'])))
        with (folder/'actual-reference.jsonl').open(encoding='utf-8') as stream:
            for source_index,line in enumerate(stream,1):
                if max_chunks and len(files)>=max_chunks: limited=True;break
                scanned+=1;row=json.loads(line)
                try: add(convert_actual(row,edge_index,master,manifest['outputs']['actual-reference.jsonl']))
                except (ValueError,TypeError,KeyError,OverflowError):
                    quarantine('actual-reference.jsonl',source_index,row,'INVALID_ACTUAL_REFERENCE')
    if batch: flush()
    result=dict(schemaVersion=1,dataVersion=version,masterVersion=master,rows=count,chunks=len(files),
        complete=not limited,productionReady=False,outputs=files,scannedRows=scanned,rejectedRows=sum(rejected.values()),rejectionReasons=dict(rejected),
        sourceManifestSha256=sha(folder/'complete.json'),codeSha256=code_sha,sampleKinds=dict(kinds),
        limitations=['WEATHER_NOT_JOINED','SOURCE_AVAILABILITY_UNKNOWN','MASTER_EFFECTIVE_DATE_UNVERIFIED'])
    if (output/'quarantine.jsonl').exists(): result['quarantineSha256']=sha(output/'quarantine.jsonl')
    save('export.json',result)
    save('job.json',dict(status='COMPLETE' if not limited else 'PILOT_COMPLETE',dataVersion=version,
        rows=count,rejectedRows=sum(rejected.values()),finishedAt=datetime.datetime.now(datetime.timezone.utc).isoformat()))
    return {k:v for k,v in result.items() if k!='outputs'}


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('folder',type=Path);p.add_argument('output',type=Path)
    p.add_argument('--max-chunks',type=int,default=0)
    a=p.parse_args()
    if a.max_chunks<0: p.error('max-chunks must not be negative')
    print(json.dumps(run(a.folder,a.output,a.max_chunks),ensure_ascii=False))
