"""보유 GTFS/도로망 연결 사전 검사. 추정 매핑이나 운영 활성화는 하지 않는다."""
import argparse
import collections
import csv
import gzip
import hashlib
import json
from pathlib import Path
import struct
import zipfile


def digest(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda:f.read(1048576), b''): h.update(block)
    return h.hexdigest()


def read(path):
    with path.open(encoding='utf-8-sig', newline='') as f:
        yield from csv.DictReader(f)


def main():
    p=argparse.ArgumentParser()
    p.add_argument('--gtfs',type=Path,required=True)
    p.add_argument('--master',type=Path,required=True)
    p.add_argument('--roads',type=Path,required=True)
    p.add_argument('--bus-gtfs',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    a=p.parse_args()
    # 입력 파일 내 서울1호선 등 확인된 route_type=1만 대상으로 삼는다.
    routes={r['route_id']:r for r in read(a.gtfs/'routes.txt') if r['route_type']=='1'}
    trips={r['trip_id']:r for r in read(a.gtfs/'trips.txt') if r['route_id'] in routes}
    stations={r['stop_id']:r for r in read(a.gtfs/'stops.txt')}
    used=set(); counts=collections.Counter(); previous={}; durations=collections.defaultdict(list)
    for r in read(a.gtfs/'stop_times.txt'):
        if r['trip_id'] not in trips: continue
        counts['subwayStopTimeRows']+=1; used.add(r['stop_id'])
        try:
            def seconds(s):
                h,m,s=map(int,s.split(':'))
                if not (0<=h<48 and 0<=m<60 and 0<=s<60): raise ValueError()
                return h*3600+m*60+s
            seq=int(r['stop_sequence']); arr=seconds(r['arrival_time']); dep=seconds(r['departure_time'])
            if dep<arr: raise ValueError()
        except (ValueError,KeyError):
            counts['invalidTimes']+=1; previous.pop(r['trip_id'],None);continue
        old=previous.get(r['trip_id'])
        if old:
            if seq==old[0]+1 and 0<arr-old[2]<=3600:
                key=(trips[r['trip_id']]['route_id'],old[1],r['stop_id'])
                durations[key].append(arr-old[2]);counts['adjacentTimeCandidates']+=1
            else: counts['nonAdjacentOrInvalidDuration']+=1
        previous[r['trip_id']]=(seq,r['stop_id'],dep)
    master=json.loads(gzip.decompress(a.master.read_bytes()))
    master_ids={s['stationId'] for line in master['lines'] for s in line['stations']}
    roads=[]
    with zipfile.ZipFile(a.roads) as z:
        for name in z.namelist():
            if name.lower().endswith('.prj'):
                roads.append(dict(member=name,projection=z.read(name).decode('utf-8',errors='replace')))
            if name.lower().endswith('.dbf'):
                with z.open(name) as f:
                    header=f.read(32); length=struct.unpack('<H',header[8:10])[0]
                    fields=f.read(length-32)
                    names=[fields[i:i+11].split(b'\0')[0].decode('ascii',errors='replace') for i in range(0,len(fields)-1,32)]
                    roads.append(dict(member=name,recordCount=struct.unpack('<I',header[4:8])[0],fields=names))
    report=dict(productionReady=False,gtfsRouteCount=len(routes),gtfsTripCount=len(trips),
        counts=dict(counts),sourceStopCount=len(used),directMasterIdMatches=len(used & master_ids),
        sourceStopExamples=[stations[s] for s in sorted(used)[:3] if s in stations],
        masterVersion=master['dataVersion'],roadSchema=roads,
        busShapesPresent=(a.bus_gtfs/'shapes.txt').exists(),
        blockedBy=['CURRENT_TIMETABLE_VALIDITY_UNVERIFIED','GTFS_STATION_LINE_DIRECTION_CROSSWALK_REQUIRED','ROAD_PROVIDER_LINK_ID_CROSSWALK_UNVERIFIED'],
        sha256={str(f):digest(f) for f in [a.gtfs/'routes.txt',a.gtfs/'trips.txt',a.gtfs/'stops.txt',a.gtfs/'stop_times.txt',a.master,a.roads]})
    a.output.mkdir(parents=True,exist_ok=False)
    with (a.output/'subway-reference-candidates.jsonl').open('x',encoding='utf-8') as f:
        for key, values in sorted(durations.items()):
            f.write(json.dumps(dict(routeId=key[0],fromStopId=key[1],toStopId=key[2],
                scheduledMeanSeconds=sum(values)/len(values),scheduledSamples=len(values),
                productionReady=False,canonicalSegmentId=None),ensure_ascii=False)+'\n')
    (a.output/'audit.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k not in ('sha256','sourceStopExamples','roadSchema')},ensure_ascii=False))


if __name__=='__main__':main()
