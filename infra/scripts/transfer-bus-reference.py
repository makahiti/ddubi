"""버스 참고자료 전송 전 전체 검증 및 명시적 내부 API 업로드.

기본 동작은 파일 검증뿐이다. --upload 없이는 네트워크를 사용하지 않는다.
인증키는 DDUBI_INTERNAL_API_KEY 환경변수로만 받으며 출력/상태파일에 기록하지 않는다.
"""
import argparse
import collections
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import urllib.request
import urllib.parse
import datetime


def digest(data): return hashlib.sha256(data).hexdigest()


def validate_row(row):
    if not isinstance(row,dict): raise ValueError('ROW_OBJECT_REQUIRED')
    for field,maximum in [('masterVersion',100),('namespace',30),('routeId',80),('fromStopId',80),('toStopId',80)]:
        value=row.get(field)
        if not isinstance(value,str) or not re.fullmatch(r'[A-Za-z0-9._-]{1,'+str(maximum)+'}',value): raise ValueError('INVALID_'+field)
    for key in ('fromOrder','toOrder','dow','hourSlot','sampleCount','observedDates'):
        if type(row.get(key)) is not int: raise ValueError('INVALID_'+key)
    if row['fromOrder']<1 or row['toOrder']!=row['fromOrder']+1 or row['fromStopId']==row['toStopId']: raise ValueError('INVALID_OCCURRENCE')
    if not 1<=row['dow']<=7 or not 0<=row['hourSlot']<=23: raise ValueError('INVALID_TIME_BUCKET')
    if type(row.get('meanSeconds')) not in (int,float) or not 0<row['meanSeconds']<=86400: raise ValueError('INVALID_MEAN')
    if row.get('sampleKind') not in ('HOURLY_SOURCE_MEAN','ACTUAL_TRAVERSAL_MEAN'): raise ValueError('INVALID_SAMPLE_KIND')
    if row['observedDates']<1 or row['sampleCount']<row['observedDates']: raise ValueError('INVALID_SAMPLES')
    days=(datetime.date.fromisoformat(row['observationEnd'])-datetime.date.fromisoformat(row['observationStart'])).days+1
    if row['observedDates']>days: raise ValueError('INVALID_PERIOD')
    if not re.fullmatch('[a-f0-9]{64}',row.get('sourceSha256','')): raise ValueError('INVALID_SOURCE_SHA')


def load_manifest(folder):
    m=json.loads((folder/'export.json').read_text(encoding='utf-8'))
    if m.get('complete') is not True or m.get('productionReady') is not False: raise ValueError('COMPLETE_REFERENCE_REQUIRED')
    if not re.fullmatch('[A-Za-z0-9._-]{1,80}',m.get('dataVersion','')): raise ValueError('INVALID_DATA_VERSION')
    outputs=m.get('outputs',{})
    if not outputs or m.get('chunks')!=len(outputs): raise ValueError('CHUNK_COUNT_MISMATCH')
    if sorted(outputs)!=[f'{i:06d}.json.gz' for i in range(len(outputs))]: raise ValueError('INVALID_CHUNK_SEQUENCE')
    if any(not re.fullmatch('[a-f0-9]{64}',v) for v in outputs.values()): raise ValueError('INVALID_CHUNK_SHA')
    return m


def read_chunk(folder,name,expected):
    path=folder/name
    if not path.resolve().is_relative_to(folder.resolve()): raise ValueError('CHUNK_PATH_ESCAPE')
    raw=path.read_bytes()
    if digest(raw)!=expected: raise ValueError('CHUNK_CHECKSUM_MISMATCH:'+name)
    with gzip.open(path,'rb') as stream:
        body=stream.read(8*1024*1024+1)
    if len(body)>8*1024*1024: raise ValueError('CHUNK_TOO_LARGE')
    payload=json.loads(body)
    rows=payload.get('rows')
    if not isinstance(rows,list) or not 1<=len(rows)<=1000: raise ValueError('INVALID_CHUNK_ROWS')
    for row in rows: validate_row(row)
    return body,rows


def verify(folder):
    m=load_manifest(folder);count=0;kinds=collections.Counter()
    for name,expected in sorted(m['outputs'].items()):
        _,rows=read_chunk(folder,name,expected)
        if any(r['masterVersion']!=m['masterVersion'] for r in rows): raise ValueError('MASTER_VERSION_MISMATCH')
        count+=len(rows);kinds.update(r['sampleKind'] for r in rows)
    if count!=m.get('rows') or dict(kinds)!=m.get('sampleKinds'): raise ValueError('ROW_COUNT_MISMATCH')
    if count+m.get('rejectedRows',0)!=m.get('scannedRows'): raise ValueError('SCANNED_COUNT_MISMATCH')
    if m.get('rejectedRows',0):
        if digest((folder/'quarantine.jsonl').read_bytes())!=m.get('quarantineSha256'): raise ValueError('QUARANTINE_CHECKSUM_MISMATCH')
    return dict(verified=True,dataVersion=m['dataVersion'],rows=count,chunks=m['chunks'],sampleKinds=dict(kinds),
        rejectedRows=m.get('rejectedRows',0),productionReady=False)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs): raise ValueError('REDIRECT_NOT_ALLOWED')


def upload(folder,base_url,key,checkpoint):
    parsed=urllib.parse.urlsplit(base_url)
    if parsed.username or parsed.password or parsed.query or parsed.fragment: raise ValueError('INVALID_BASE_URL')
    if not parsed.netloc or parsed.scheme not in ('https','http'): raise ValueError('INVALID_BASE_URL')
    if parsed.scheme=='http' and parsed.hostname not in ('localhost','127.0.0.1','::1'): raise ValueError('HTTPS_REQUIRED')
    if not key: raise ValueError('API_KEY_REQUIRED')
    summary=verify(folder);m=load_manifest(folder)
    target=digest(base_url.rstrip('/').encode());manifest_sha=digest((folder/'export.json').read_bytes())
    state=dict(targetSha256=target,manifestSha256=manifest_sha,completed={})
    if checkpoint.exists():
        previous=json.loads(checkpoint.read_text(encoding='utf-8'))
        if previous.get('targetSha256')!=target or previous.get('manifestSha256')!=manifest_sha: raise ValueError('CHECKPOINT_TARGET_MISMATCH')
        state=previous
    opener=urllib.request.build_opener(NoRedirect())
    for index,(name,expected) in enumerate(sorted(m['outputs'].items())):
        if state['completed'].get(name)==expected: continue
        body,rows=read_chunk(folder,name,expected)
        url=base_url.rstrip('/')+'/internal/bus-references/'+m['dataVersion']+'/chunks/'+str(index)
        req=urllib.request.Request(url,data=body,headers={'Content-Type':'application/json','X-DDUBI-API-KEY':key},method='POST')
        with opener.open(req,timeout=60) as response:
            result=json.loads(response.read(1024*1024))
        data=result.get('data',{})
        if result.get('success') is not True or data.get('receivedRows')!=len(rows) or data.get('productionReady') is not False: raise ValueError('UPLOAD_ACK_MISMATCH')
        state['completed'][name]=expected
        tmp=checkpoint.with_suffix(checkpoint.suffix+'.tmp')
        tmp.write_text(json.dumps(state,sort_keys=True)+'\n',encoding='utf-8',newline='\n');tmp.replace(checkpoint)
        if len(state['completed'])%100==0 or len(state['completed'])==m['chunks']:
            print(json.dumps(dict(phase='UPLOADING',dataVersion=m['dataVersion'],
                acknowledgedChunks=len(state['completed']),totalChunks=m['chunks'])),flush=True)
    status_url=base_url.rstrip('/')+'/internal/bus-references/'+m['dataVersion']+'/status'
    status_req=urllib.request.Request(status_url,headers={'X-DDUBI-API-KEY':key},method='GET')
    with opener.open(status_req,timeout=60) as response: status=json.loads(response.read(1024*1024))
    data=status.get('data',{})
    if status.get('success') is not True or data.get('dataVersion')!=m['dataVersion'] or data.get('totalRows')!=summary['rows'] or data.get('totalChunks')!=summary['chunks'] or data.get('productionReady') is not False:
        raise ValueError('SERVER_TOTALS_MISMATCH')
    return summary|dict(uploadAcknowledgedChunks=len(state['completed']),serverTotalsVerified=True,activated=False)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('folder',type=Path)
    p.add_argument('--upload',action='store_true');p.add_argument('--base-url');p.add_argument('--checkpoint',type=Path)
    a=p.parse_args()
    if a.upload:
        if not a.base_url or not a.checkpoint:p.error('--upload requires --base-url and --checkpoint')
        result=upload(a.folder,a.base_url,os.environ.get('DDUBI_INTERNAL_API_KEY'),a.checkpoint)
    else: result=verify(a.folder)
    print(json.dumps(result,ensure_ascii=False))
