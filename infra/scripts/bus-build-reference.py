"""보유 SQLite 전체를 읽기 전용 조인해 검토용 버스 기준값을 구성한다.

시간대 평균과 실제 관측은 분리한다. 운영 baseline 업로드/활성화는 없다.
"""
import argparse
import collections
import datetime as dt
import gzip
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sqlite3
import statistics
import sys
import traceback

spec=importlib.util.spec_from_file_location('mapping',Path(__file__).with_name('bus-map-candidates.py'))
mapping=importlib.util.module_from_spec(spec)
spec.loader.exec_module(mapping)


def save(path,value):
    temp=path.with_suffix(path.suffix+'.tmp')
    with temp.open('w',encoding='utf-8') as f: mapping.emit(f,value)
    temp.replace(path)


def connect(path):
    db=sqlite3.connect(path,uri=True)
    db.execute('PRAGMA temp_store=FILE')
    db.execute('PRAGMA cache_size=-65536')
    return db


def attach(db,path,name):
    db.execute(f'ATTACH DATABASE ? AS {name}',(path.resolve().as_uri()+'?mode=ro',))


def overlaps(ranges):
    nonempty=sorted((a,b) for a,b in ranges if a is not None)
    end=None
    for a,b in nonempty:
        if end is not None and a<=end: return True
        end=b
    return False


def daily_sql(names,overlap):
    union=' UNION ALL '.join(f'SELECT day,route,a,b,ao,bo,hour,seconds,conflict FROM {n}.daily' for n in names)
    if not overlap: return union
    # 충돌 표시 또는 서로 다른 seconds가 하나라도 있으면 해당 원천 키 전체 제외.
    return ('SELECT day,route,a,b,ao,bo,hour,min(seconds) seconds,'
            'CASE WHEN max(conflict)>0 OR min(seconds)!=max(seconds) THEN 1 ELSE 0 END conflict '
            f'FROM ({union}) GROUP BY day,route,a,b,ao,bo,hour')


def mapping_table(db,crosswalk):
    matcher=mapping.Mapper(crosswalk,'SEOUL_BUS')
    db.execute('CREATE TABLE edge_map(route TEXT,a TEXT,b TEXT,ao TEXT,bo TEXT,segment TEXT, PRIMARY KEY(route,a,b,ao,bo))')
    for key,edges in matcher.exact.items():
        unique={(e['segmentId'],e.get('direction')) for e in edges}
        if len(unique)==1:
            db.execute('INSERT INTO edge_map VALUES(?,?,?,?,?,?)',(*key[:3],str(key[3]),str(key[4]),edges[0]['segmentId']))
    db.commit()


def aggregate(db,names,overlap):
    sql=daily_sql(names,overlap)
    # 원본을 복제하지 않고 집계하며 순서를 남겨 순환노선 반복 위치를 섞지 않는다.
    db.execute('CREATE TABLE hourly_reference AS SELECT d.route,d.a,d.b,d.ao,d.bo,m.segment,'
        "cast(strftime('%w',d.day) as integer)+1 dow,d.hour,sum(d.seconds) totalSeconds,count(*) observedDates,"
        f'min(d.day) observationStart,max(d.day) observationEnd FROM ({sql}) d '
        'JOIN edge_map m ON m.route=d.route AND m.a=d.a AND m.b=d.b AND m.ao=d.ao AND m.bo=d.bo '
        'WHERE d.conflict=0 GROUP BY d.route,d.a,d.b,d.ao,d.bo,m.segment,dow,d.hour')
    db.execute('CREATE TABLE unmapped_coverage AS SELECT d.route,count(*) dailyKeys '
        f'FROM ({sql}) d LEFT JOIN edge_map m ON m.route=d.route AND m.a=d.a AND m.b=d.b AND m.ao=d.ao AND m.bo=d.bo '
        'WHERE d.conflict=0 AND m.segment IS NULL GROUP BY d.route')
    db.commit()
    return dict(mappedDailyKeys=db.execute('SELECT coalesce(sum(observedDates),0) FROM hourly_reference').fetchone()[0],
                mappedGroups=db.execute('SELECT count(*) FROM hourly_reference').fetchone()[0],
                unmappedDailyKeys=db.execute('SELECT coalesce(sum(dailyKeys),0) FROM unmapped_coverage').fetchone()[0])


def checkpoint(target,paths,crosswalk,overlap,index):
    output=target/f'hourly-part-{index}.sqlite'
    done=target/f'hourly-part-{index}.complete.json'
    if done.exists():
        value=json.loads(done.read_text(encoding='utf-8'))
        if mapping.sha(output)!=value['sha256']: raise ValueError('CHECKPOINT_CHECKSUM_MISMATCH')
        return output,value['result']
    partial=target/f'hourly-part-{index}.{os.getpid()}.partial.sqlite'
    if partial.exists(): raise ValueError('PARTIAL_ALREADY_EXISTS')
    db=connect(partial)
    try:
        names=[]
        for i,p in enumerate(paths):
            name=f'src{i}';attach(db,p,name);names.append(name)
        mapping_table(db,crosswalk)
        result=aggregate(db,names,overlap)
    finally: db.close()
    partial.replace(output)
    save(done,dict(sha256=mapping.sha(output),result=result,inputs=[str(p) for p in paths]))
    return output,result


def actual_reference(source,target,min_samples,min_dates):
    groups=collections.defaultdict(list)
    seen={}
    counters=collections.Counter()
    dates=collections.defaultdict(set)
    with source.open(encoding='utf-8') as f:
        for line in f:
            row=json.loads(line);counters['inputRows']+=1
            event=row['sourceEventId']
            if event in seen:
                if seen[event]!=row: raise ValueError('CONFLICTING_ACTUAL_EVENT')
                counters['duplicateEvents']+=1;continue
            seen[event]=row
            if row['mappingStatus']!='EXACT_ID_ORDER_CANDIDATE':
                counters['unmappedRows']+=1;continue
            time=dt.datetime.fromisoformat(row['enteredAt'])
            if time.utcoffset()!=dt.timedelta(hours=9): raise ValueError('ACTUAL_TIME_NOT_KST')
            key=(row['canonicalSegmentId'],row['fromOrder'],row['toOrder'],(time.weekday()+1)%7+1,time.hour)
            groups[key].append(float(row['durationSeconds']));dates[key].add(time.date().isoformat())
            counters['mappedRows']+=1
    output=target/'actual-reference.jsonl'
    with output.open('w',encoding='utf-8') as f:
        for key,values in sorted(groups.items()):
            enough=len(values)>=min_samples and len(dates[key])>=min_dates
            counters['sampleSufficientGroups' if enough else 'sampleInsufficientGroups']+=1
            mapping.emit(f,dict(zip(('segmentId','fromOrder','toOrder','dow','hourSlot'),key))|
                dict(sampleKind='ACTUAL_TRAVERSAL_CANDIDATE',sampleCount=len(values),observedDates=len(dates[key]),
                    meanSeconds=statistics.mean(values),medianSeconds=statistics.median(values),
                    observationStart=min(dates[key]),observationEnd=max(dates[key]),
                    sampleSufficient=enough,weatherBucket=None,sourceAvailableAt=None,productionReady=False))
    return dict(counters)


def route_proposals(source,target,parent_version,existing_routes=()):
    routes=[]
    existing={(r['namespace'],str(r['routeId'])) for r in existing_routes}
    already_present=[]
    with source.open(encoding='utf-8') as f:
        for line in f:
            r=json.loads(line)
            identity=(r['namespace'],str(r['providerRouteId']))
            if identity in existing:
                # 기존 노선의 정류장/순서가 동일하다는 판정이 아니라 중복 추가 금지다.
                already_present.append(dict(namespace=identity[0],routeId=identity[1],
                    status='ALREADY_IN_MASTER_REQUIRES_EDGE_REVIEW'))
                continue
            stops=[dict(namespace=r['namespace'],stopId=s['stopId'],sequence=s['sequence'],stopName=s['stopName'],
                        longitude=s['longitude'],latitude=s['latitude'],direction=None,boardingAllowed=None,
                        evidence=dict(sheet=s['sourceSheet'],row=s['sourceRow'])) for s in r['stops']]
            routes.append(dict(namespace=r['namespace'],routeId=r['providerRouteId'],routeName=r['stops'][0]['routeName'],
                stops=stops,sourceSnapshotDate=r['sourceSnapshotDate'],ambiguousSequences=r['ambiguousSequences']))
    save(target/'master-additions-candidate.json',dict(schemaVersion=1,parentMasterVersion=parent_version,
        productionReady=False,routes=routes,alreadyPresent=already_present,blockedBy=['DIRECTION_UNKNOWN','BOARDING_PERMISSION_UNKNOWN',
                                                     'EFFECTIVE_DATE_UNVERIFIED','ID_POLICY_APPROVAL_REQUIRED']))
    return dict(routes=len(routes),stops=sum(len(r['stops']) for r in routes),alreadyPresent=len(already_present))


def run(args):
    root=args.output_root
    root.mkdir(parents=True,exist_ok=True)
    job=root/f'job-{os.getpid()}.json'
    save(job,dict(pid=os.getpid(),startedAt=dt.datetime.now(dt.timezone.utc).isoformat(),
        command=[sys.executable,*sys.argv],script=str(Path(__file__).resolve()),
        stdout=str(args.stdout),stderr=str(args.stderr),output=str(root.resolve()),status='RUNNING'))
    try:
        sources=[*args.daily,args.crosswalk,args.master,args.actual,args.route_proposals]
        inputs={str(p.resolve()):mapping.sha(p) for p in sources}
        code={p.name:mapping.sha(p) for p in (Path(__file__),Path(mapping.__file__))}
        fingerprint=hashlib.sha256(json.dumps([inputs,code,args.min_samples,args.min_dates],sort_keys=True).encode()).hexdigest()
        target=root/fingerprint[:20];target.mkdir(exist_ok=True)
        save(job,json.loads(job.read_text(encoding='utf-8'))|dict(output=str(target.resolve())))
        if (target/'complete.json').exists():
            manifest=json.loads((target/'complete.json').read_text(encoding='utf-8'))
            if not all(mapping.sha(target/n)==v for n,v in manifest['outputs'].items()): raise ValueError('OUTPUT_CHECKSUM_MISMATCH')
            result=manifest['result']
        else:
            with gzip.open(args.crosswalk,'rt',encoding='utf-8') as f: crosswalk=json.load(f)
            with gzip.open(args.master,'rt',encoding='utf-8') as f: master=json.load(f)
            if crosswalk['masterSha256']!=inputs[str(args.master.resolve())] or crosswalk['masterVersion']!=master['version']:
                raise ValueError('MASTER_CROSSWALK_MISMATCH')
            ranges=[]
            for path in args.daily:
                c=sqlite3.connect(path.resolve().as_uri()+'?mode=ro',uri=True)
                ranges.append(c.execute('SELECT min(day),max(day) FROM daily').fetchone());c.close()
            overlap=overlaps(ranges)
            print(json.dumps(dict(stage='START',ranges=ranges,overlap=overlap)),flush=True)
            batches=[args.daily] if overlap else [[p] for p in args.daily]
            parts=[];results=[]
            for i,paths in enumerate(batches):
                print(json.dumps(dict(stage='AGGREGATE',part=i)),flush=True)
                part,res=checkpoint(target,paths,crosswalk,overlap,i);parts.append(part);results.append(res)
                print(json.dumps(dict(stage='PART_COMPLETE',part=i,result=res)),flush=True)
            actual=actual_reference(args.actual,target,args.min_samples,args.min_dates)
            additions=route_proposals(args.route_proposals,target,master['version'],master['routes'])
            # 파트들은 날짜 비중복 또는 사전 중복제거됨. 평균은 totalSeconds 합 / observedDates 합으로 읽는다.
            # 큰 재복사 없이 읽기 전용 파트들에 대한 합산 SQL 계약을 저장한다.
            union=' UNION ALL '.join(f'SELECT * FROM part{i}.hourly_reference' for i in range(len(parts)))
            query=('SELECT route,a,b,ao,bo,segment,dow,hour,sum(totalSeconds)/sum(observedDates) meanOfHourlyValuesSeconds,'
                'sum(observedDates) observedDates,min(observationStart) observationStart,max(observationEnd) observationEnd '
                f'FROM ({union}) GROUP BY route,a,b,ao,bo,segment,dow,hour')
            save(target/'hourly-query.json',dict(attachments={f'part{i}':p.name for i,p in enumerate(parts)},sql=query,
                sampleKind='PROVIDER_HOURLY_AGGREGATE',weatherBucket=None,productionReady=False,
                minSamplesPolicy=dict(actualMinSamples=args.min_samples,actualMinDates=args.min_dates),
                note='observedDates counts source daily hourly means, not individual vehicle samples; no p80/p95'))
            result=dict(hourlyParts=results,actual=actual,masterAdditions=additions,dateRanges=ranges,overlapDeduplicated=overlap)
            outputs={p.name:mapping.sha(p) for p in target.iterdir() if p.is_file() and '.partial.' not in p.name and p.suffix!='.tmp'}
            save(target/'complete.json',dict(schemaVersion=1,inputs=inputs,codeSha256=code,productionReady=False,
                result=result,outputs=outputs,blockedBy=['SOURCE_AVAILABILITY_UNKNOWN','WEATHER_NOT_JOINED','MASTER_EFFECTIVE_DATE_UNVERIFIED']))
        save(job,json.loads(job.read_text(encoding='utf-8'))|dict(status='COMPLETE',exitCode=0,finishedAt=dt.datetime.now(dt.timezone.utc).isoformat()))
        print(json.dumps(dict(output=str(target),result=result)),flush=True)
    except Exception:
        save(job,json.loads(job.read_text(encoding='utf-8'))|dict(status='FAILED',exitCode=1,error=traceback.format_exc()))
        raise


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--daily',type=Path,nargs='+',required=True)
    for name in ('crosswalk','master','actual','route-proposals','output-root','stdout','stderr'):
        p.add_argument('--'+name,type=Path,required=True)
    p.add_argument('--min-samples',type=int,default=20)
    p.add_argument('--min-dates',type=int,default=3)
    a=p.parse_args()
    if a.min_samples<1 or a.min_dates<1: p.error('positive sample thresholds required')
    run(a)
