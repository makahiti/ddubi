"""Resumable offline full-shape road preparation; shared spatial index, per-batch manifests."""
import argparse
import collections
from datetime import datetime,timezone
import importlib.util
import json
import math
import os
from pathlib import Path
import sqlite3
import sys
import traceback
import uuid
import zipfile


def load(name,filename):
    spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename))
    mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod);return mod


geo=load('road_geo','road-mapping-candidates.py')
occ=load('road_occ','road-occurrence-review.py')


def atomic_json(path,value):
    temp=path.with_name(path.name+'.tmp')
    temp.write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')
    temp.replace(path)


def candidate_at(road,point,direction,radius):
    if len(road['parts'])!=1:return None,'MULTIPART_REQUIRES_REVIEW'
    points=road['parts'][0];best=None;distance_along=0
    for a,b in zip(points,points[1:]):
        dx,dy=b[0]-a[0],b[1]-a[1];length=math.hypot(dx,dy)
        if not length:continue
        t=max(0,min(1,((point[0]-a[0])*dx+(point[1]-a[1])*dy)/length**2))
        distance=math.hypot(point[0]-a[0]-t*dx,point[1]-a[1]-t*dy)
        alignment=(dx*direction[0]+dy*direction[1])/length
        if best is None or distance<best[0]:best=(distance,alignment,distance_along+t*length)
        distance_along+=length
    if best is None or best[0]>radius or abs(best[1])<.7:return None,'OUTSIDE_DISTANCE_OR_HEADING'
    distance,alignment,position=best;forward=alignment>=0
    candidate=dict(linkId=road['linkId'],attributes=road['attributes'],orientation='FORWARD' if forward else 'REVERSE',
        distanceMetres=round(distance,3),headingAlignment=round(abs(alignment),6),score=distance+20*(1-abs(alignment)),
        directedStart=points[0] if forward else points[-1],directedEnd=points[-1] if forward else points[0],
        alongLinkMetres=position if forward else distance_along-position)
    candidate['directionEvidence']=occ.direction_evidence(candidate)
    if not candidate['directionEvidence']['permitted']:return None,candidate['directionEvidence']['state']
    return candidate,None


def generate(db,shapes,directory,config,inputs):
    counts=collections.Counter()
    with (directory/'candidates.jsonl').open('x',encoding='utf-8') as out:
        for shape_id,points in shapes.items():
            for edge,along,point,direction in geo.sample_path(points,config['spacingMetres']):
                x,y=point;r=config['radiusMetres']
                roads=db.execute('SELECT r.payload FROM boxes b JOIN roads r ON b.id=r.id WHERE b.minx<=? AND b.maxx>=? AND b.miny<=? AND b.maxy>=?',(x+r,x-r,y+r,y-r))
                candidates=[]
                for payload, in roads:
                    candidate,reason=candidate_at(json.loads(payload),point,direction,r)
                    if candidate:candidates.append(candidate)
                    else:counts['filtered:'+reason]+=1
                candidates.sort(key=lambda c:(c['score'],c['linkId'],c['orientation']))
                state=geo.classify(candidates,config['ambiguityMarginMetres']);counts[state]+=1
                out.write(json.dumps(dict(shapeId=shape_id,shapeEdge=edge,distanceAlongMetres=round(along,3),
                    status=state,productionReady=False,directionFilteredBeforeRanking=True,candidates=candidates[:3]),ensure_ascii=False)+'\n')
    report=dict(productionReady=False,configuration=config,shapeCount=len(shapes),counts=dict(counts),approvedMappings=0)
    atomic_json(directory/'report.json',report)
    atomic_json(directory/'complete.json',dict(inputs=inputs,outputs={name:geo.digest(directory/name) for name in ('candidates.jsonl','report.json')},productionReady=False))
    return report


def verify(directory,manifest):
    if any(geo.digest(directory/name)!=sha for name,sha in manifest['outputs'].items()):raise ValueError('Checkpoint output checksum mismatch')


def execute(args):
    from pyproj import CRS
    from pyproj.transformer import TransformerGroup
    root=args.output;root.mkdir(parents=True,exist_ok=True)
    # SQLite releases this exclusive lock on process exit/crash, unlike stale PID lockfiles.
    lock=sqlite3.connect(root/'run-lock.sqlite',timeout=0)
    lock.execute('CREATE TABLE IF NOT EXISTS run_lock(id INTEGER)');lock.commit();lock.execute('BEGIN EXCLUSIVE')
    started=datetime.now(timezone.utc).isoformat()
    atomic_json(root/'job.json',dict(pid=os.getpid(),startedAt=started,script=str(Path(__file__).resolve()),
        command=[sys.executable,*sys.argv],stdout=args.stdout,stderr=args.stderr,output=str(root.resolve())))
    input_paths=[args.roads,args.gtfs/'shapes.txt',args.gtfs/'trips.txt',args.gtfs/'stop_times.txt',args.gtfs/'stops.txt',args.bus_crosswalk,
                 Path(__file__),Path(geo.__file__),Path(occ.__file__)]
    inputs={str(p):geo.digest(p) for p in input_paths}
    config=dict(spacingMetres=40,radiusMetres=50,ambiguityMarginMetres=8,batchSize=args.batch_size,
                busNamespace=args.bus_namespace,gtfsPrefix=args.gtfs_id_prefix,maxShapes=args.max_shapes,
                reuseIndex=str(args.reuse_index.resolve()) if args.reuse_index else None)
    identity=dict(inputs=inputs,configuration=config)
    identity_path=root/'identity.json'
    if identity_path.exists() and json.loads(identity_path.read_text(encoding='utf-8'))!=identity:raise ValueError('Inputs or code changed; use new output root')
    atomic_json(identity_path,identity)
    shapes,invalid,missing=geo.load_shapes(args.gtfs/'shapes.txt',set(),args.max_shapes)
    if not shapes:raise ValueError('No valid shapes')
    atomic_json(root/'status.json',dict(state='INDEX_PREPARING',shapeCount=len(shapes),invalidShapes=invalid,pid=os.getpid()))
    with zipfile.ZipFile(args.roads) as z:projections={z.read(n).decode('utf-8-sig') for n in z.namelist() if n.lower().endswith('.prj')}
    if len(projections)!=1:raise ValueError('Mixed source CRS')
    crs=CRS.from_wkt(projections.pop())
    if not crs.is_projected or any(a.unit_conversion_factor!=1 for a in crs.axis_info):raise ValueError('Metric source CRS required')
    group=TransformerGroup('EPSG:4326',crs,always_xy=True,allow_ballpark=False)
    if not group.best_available or not group.transformers or not 0<=group.transformers[0].accuracy<=5:raise ValueError('Accurate transform unavailable')
    transform=group.transformers[0]
    projected={key:[transform.transform(*point,errcheck=True) for point in points] for key,points in shapes.items()}
    index_checkpoint=root/'index-complete.json'
    if index_checkpoint.exists():
        manifest=json.loads(index_checkpoint.read_text(encoding='utf-8'));index_dir=root/manifest['directory'];verify(index_dir,manifest)
        db=sqlite3.connect(index_dir/'roads.sqlite');road_counts=manifest['counts']
    elif args.reuse_index:
        original=json.loads((args.reuse_index/'complete.json').read_text(encoding='utf-8'))
        verify(args.reuse_index,original)
        source_hashes=[sha for name,sha in original['inputs'].items() if Path(name).suffix.lower()=='.zip']
        if inputs[str(args.roads)] not in source_hashes:raise ValueError('Reused road source checksum differs')
        original_report=json.loads((args.reuse_index/'report.json').read_text(encoding='utf-8'))
        bounds=original_report['projection']['indexBoundsMetres']
        for points in projected.values():
            for x,y in points:
                if not bounds[0]<=x-config['radiusMetres'] or not x+config['radiusMetres']<=bounds[2] or not bounds[1]<=y-config['radiusMetres'] or not y+config['radiusMetres']<=bounds[3]:
                    raise ValueError('Reused index does not cover all selected shapes')
        index_dir=args.reuse_index.resolve();road_counts={k:v for k,v in original_report['summary'].items() if k in ('roadRecordsScanned','indexedRoads','nonzeroTrafIdP','nonzeroTrafIdN')}
        atomic_json(index_checkpoint,dict(directory=str(index_dir),counts=road_counts,bounds=bounds,outputs={'roads.sqlite':geo.digest(index_dir/'roads.sqlite')}))
        db=sqlite3.connect(index_dir.as_uri()+'/roads.sqlite?mode=ro',uri=True)
    else:
        index_dir=root/('index-attempt-'+uuid.uuid4().hex[:8]);index_dir.mkdir()
        db,road_counts,bounds=geo.build_index(args.roads,index_dir,projected,config['radiusMetres'])
        db.close()
        atomic_json(index_checkpoint,dict(directory=index_dir.name,counts=road_counts,bounds=bounds,outputs={'roads.sqlite':geo.digest(index_dir/'roads.sqlite')}))
        db=sqlite3.connect(index_dir/'roads.sqlite')
    ids=sorted(projected);batch_count=math.ceil(len(ids)/args.batch_size);totals=collections.Counter();checkpoints=[]
    for number in range(batch_count):
        checkpoint=root/f'batch-{number:05d}.json'
        if checkpoint.exists():
            saved=json.loads(checkpoint.read_text(encoding='utf-8'));verify(root/saved['directory'],saved)
            totals.update(saved['counts']);checkpoints.append(checkpoint.name);continue
        directory=root/(f'batch-{number:05d}-attempt-'+uuid.uuid4().hex[:8]);directory.mkdir()
        selected={key:projected[key] for key in ids[number*args.batch_size:(number+1)*args.batch_size]}
        atomic_json(root/'status.json',dict(state='PROCESSING',batch=number,totalBatches=batch_count,completedBatches=len(checkpoints),completedShapes=min(number*args.batch_size,len(ids)),totalShapes=len(ids),pid=os.getpid()))
        generate(db,selected,directory,config,inputs)
        occ_args=argparse.Namespace(candidates=directory/'candidates.jsonl',gtfs=args.gtfs,bus_crosswalk=args.bus_crosswalk,
             bus_namespace=args.bus_namespace,gtfs_id_prefix=args.gtfs_id_prefix,output=directory/'occurrence')
        occ.run(occ_args)
        occurrence=json.loads((directory/'occurrence/report.json').read_text(encoding='utf-8'))
        outputs={str(p.relative_to(directory)):geo.digest(p) for p in directory.rglob('*') if p.is_file()}
        saved=dict(directory=directory.name,shapeIds=list(selected),outputs=outputs,counts=occurrence['counts'])
        atomic_json(checkpoint,saved);totals.update(saved['counts']);checkpoints.append(checkpoint.name)
        print(json.dumps(dict(batchCompleted=number,completedBatches=len(checkpoints),totalBatches=batch_count,counts=occurrence['counts'])),flush=True)
    db.close()
    complete=dict(state='COMPLETE',exitCode=0,productionReady=False,approvedMappings=0,shapeCount=len(ids),invalidShapes=invalid,
                  roadCounts=road_counts,counts=dict(totals),checkpoints=checkpoints,inputs=inputs,
                  outputs={name:geo.digest(root/name) for name in [*checkpoints,'identity.json','index-complete.json']})
    atomic_json(root/'complete.json',complete);atomic_json(root/'status.json',dict(state='COMPLETE',exitCode=0,shapeCount=len(ids),completedBatches=batch_count))
    print(json.dumps(dict(state='COMPLETE',shapeCount=len(ids),counts=dict(totals))),flush=True)
    lock.rollback();lock.close()


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--roads',type=Path,required=True);p.add_argument('--gtfs',type=Path,required=True)
    p.add_argument('--bus-crosswalk',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    p.add_argument('--bus-namespace',required=True);p.add_argument('--gtfs-id-prefix',required=True)
    p.add_argument('--batch-size',type=int,default=10);p.add_argument('--max-shapes',type=int,default=0)
    p.add_argument('--reuse-index',type=Path)
    p.add_argument('--stdout');p.add_argument('--stderr');a=p.parse_args()
    if a.batch_size<=0 or a.max_shapes<0:p.error('Invalid batch or shape count')
    try:execute(a)
    except Exception as exc:
        if a.output.exists():atomic_json(a.output/('failed-'+str(os.getpid())+'.json'),dict(exitCode=1,error=str(exc),traceback=traceback.format_exc()))
        raise


if __name__=='__main__':main()
