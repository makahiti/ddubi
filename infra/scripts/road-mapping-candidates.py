"""Stream local roads into a spatial index and emit auditable GTFS shape candidates.

Candidate-only artifact: never emits the operational RoadImpactIndex schema.
Dependencies: pyproj==3.7.2, pyshp==2.3.1. No network, DB or server writes.
"""
import argparse
import collections
import csv
import hashlib
import json
import math
from pathlib import Path
import sqlite3
import zipfile


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as f:
        for block in iter(lambda: f.read(1048576), b''):
            h.update(block)
    return h.hexdigest()


def write_json(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


def nearest(point, direction, parts):
    """Distance and local heading; never bridge multipart geometry gaps."""
    best = None
    for points in parts:
        for a, b in zip(points, points[1:]):
            dx, dy = b[0]-a[0], b[1]-a[1]
            length = math.hypot(dx, dy)
            if not length:
                continue
            t = max(0, min(1, ((point[0]-a[0])*dx+(point[1]-a[1])*dy)/length**2))
            distance = math.hypot(point[0]-a[0]-t*dx, point[1]-a[1]-t*dy)
            cosine = (dx*direction[0]+dy*direction[1])/length
            candidate = (distance, abs(cosine), 'FORWARD' if cosine >= 0 else 'REVERSE')
            if best is None or distance < best[0]:
                best = candidate
    return best


def sample_path(points, spacing):
    if spacing <= 0:
        raise ValueError('spacing must be positive')
    distance_along = 0.0
    for index, (a, b) in enumerate(zip(points, points[1:])):
        length = math.dist(a, b)
        if not length:
            continue
        count = max(1, math.ceil(length/spacing))
        direction = ((b[0]-a[0])/length, (b[1]-a[1])/length)
        for step in range(count):
            t = (step+.5)/count
            yield index, distance_along+t*length, (a[0]+t*(b[0]-a[0]), a[1]+t*(b[1]-a[1])), direction
        distance_along += length


def topology(previous, current, tolerance=5):
    """Geometry continuity is not evidence of one-way permission or grade separation."""
    if previous['id'] == current['id']:
        return 'SAME_LINK'
    p = previous['parts'][-1][-1] if previous['orientation'] == 'FORWARD' else previous['parts'][0][0]
    c = current['parts'][0][0] if current['orientation'] == 'FORWARD' else current['parts'][-1][-1]
    return 'GEOMETRIC_ENDPOINT_CONTINUITY' if math.dist(p, c) <= tolerance else 'DISCONNECTED_CANDIDATES'


def classify(candidates, margin):
    if not candidates:
        return 'NO_ALIGNED_ROAD_WITHIN_RADIUS'
    if len(candidates) > 1 and candidates[1]['score']-candidates[0]['score'] <= margin:
        return 'MULTIPLE_PLAUSIBLE_ROADS'
    return 'GEOMETRY_CANDIDATE_REQUIRES_REVIEW'


def load_shapes(path, requested, max_shapes):
    shapes, invalid = {}, collections.Counter()
    with path.open(encoding='utf-8-sig', newline='') as f:
        for row in csv.DictReader(f):
            shape_id = row['shape_id']
            if requested and shape_id not in requested:
                continue
            if shape_id not in shapes and max_shapes and len(shapes) >= max_shapes:
                continue
            try:
                lat, lon, seq = float(row['shape_pt_lat']), float(row['shape_pt_lon']), int(row['shape_pt_sequence'])
                if not (math.isfinite(lat) and math.isfinite(lon) and -90 <= lat <= 90 and -180 <= lon <= 180):
                    raise ValueError()
            except (ValueError, KeyError):
                invalid[shape_id] += 1
                continue
            shapes.setdefault(shape_id, []).append((seq, lon, lat))
    for shape_id in list(shapes):
        rows = sorted(shapes[shape_id])
        if invalid[shape_id] or len(rows) < 2 or len({r[0] for r in rows}) != len(rows):
            invalid[shape_id] += 1
            del shapes[shape_id]
        else:
            shapes[shape_id] = [(r[1], r[2]) for r in rows]
    missing = sorted(set(requested)-set(shapes)-set(invalid))
    return shapes, dict(invalid), missing


def build_index(archive, output, projected_shapes, radius):
    import shapefile
    points = [p for shape in projected_shapes.values() for p in shape]
    bounds = (min(p[0] for p in points)-radius, min(p[1] for p in points)-radius,
              max(p[0] for p in points)+radius, max(p[1] for p in points)+radius)
    db = sqlite3.connect(output/'roads.sqlite')
    db.execute('CREATE TABLE roads (id INTEGER PRIMARY KEY, payload TEXT NOT NULL)')
    db.execute('CREATE VIRTUAL TABLE boxes USING rtree(id,minx,maxx,miny,maxy)')
    counts = collections.Counter()
    with zipfile.ZipFile(archive) as z:
        members = [n for n in z.namelist() if n.lower().endswith('.shp')]
        for member in members:
            base = member[:-4]
            with z.open(base+'.shp') as shp, z.open(base+'.dbf') as dbf:
                reader = shapefile.Reader(shp=shp, dbf=dbf, encoding='cp949')
                fields = {field[0] for field in reader.fields[1:]}
                if 'LINK_ID' not in fields or not {'TRAF_ID_P', 'TRAF_ID_N'} <= fields:
                    continue
                for sr in reader.iterShapeRecords():
                    counts['roadRecordsScanned'] += 1
                    shape = sr.shape
                    if shape.shapeType not in (3, 13, 23) or not shape.points:
                        counts['unsupportedGeometry'] += 1
                        continue
                    x1, y1, x2, y2 = shape.bbox
                    if x2 < bounds[0] or y2 < bounds[1] or x1 > bounds[2] or y1 > bounds[3]:
                        continue
                    attrs = sr.record.as_dict()
                    starts = list(shape.parts)+[len(shape.points)]
                    parts = [list(shape.points[a:b]) for a, b in zip(starts, starts[1:])]
                    if not all(len(p) >= 2 for p in parts):
                        counts['invalidGeometry'] += 1
                        continue
                    item = dict(linkId=attrs['LINK_ID'], attributes=attrs, parts=parts)
                    cursor = db.execute('INSERT INTO roads(payload) VALUES (?)', (json.dumps(item, ensure_ascii=False),))
                    db.execute('INSERT INTO boxes VALUES (?,?,?,?,?)', (cursor.lastrowid, x1, x2, y1, y2))
                    counts['indexedRoads'] += 1
                    counts['nonzeroTrafIdP'] += attrs.get('TRAF_ID_P') not in ('', '0', None)
                    counts['nonzeroTrafIdN'] += attrs.get('TRAF_ID_N') not in ('', '0', None)
                    if counts['indexedRoads'] % 5000 == 0:
                        db.commit()
    db.commit()
    return db, dict(counts), bounds


def run(args):
    from pyproj import CRS
    from pyproj.transformer import TransformerGroup
    input_hashes = {str(p): digest(p) for p in (args.roads, args.shapes, Path(__file__))}
    configuration = dict(shapeIds=sorted(args.shape_id), maxShapes=args.max_shapes,
                         spacingMetres=args.spacing, radiusMetres=args.radius,
                         ambiguityMarginMetres=args.ambiguity_margin)
    fingerprint = hashlib.sha256(json.dumps([input_hashes, configuration], sort_keys=True).encode()).hexdigest()
    manifest_path = args.output/'complete.json'
    if manifest_path.exists():
        old = json.loads(manifest_path.read_text(encoding='utf-8'))
        if old['fingerprint'] != fingerprint or any(digest(args.output/name) != sha for name, sha in old['outputs'].items()):
            raise ValueError('Existing result differs or is damaged; use a new output directory')
        print(json.dumps(dict(reused=True, **old['summary'])))
        return
    args.output.mkdir(parents=True, exist_ok=False)
    shapes, invalid, missing = load_shapes(args.shapes, set(args.shape_id), args.max_shapes)
    if not shapes:
        raise ValueError('No valid shapes; invalid='+str(invalid)+' missing='+str(missing))
    with zipfile.ZipFile(args.roads) as z:
        road_prjs = [n for n in z.namelist() if n.lower().endswith('.prj')]
        projections = {z.read(n).decode('utf-8-sig') for n in road_prjs}
    if len(projections) != 1:
        raise ValueError('Expected a single verified source CRS; split mixed-CRS archives')
    prj = projections.pop()
    crs = CRS.from_wkt(prj)
    if not crs.is_projected or any(a.unit_conversion_factor != 1 for a in crs.axis_info):
        raise ValueError('Road CRS must use metres')
    group = TransformerGroup('EPSG:4326', crs, always_xy=True, allow_ballpark=False)
    if not group.transformers or not group.best_available:
        raise ValueError('Accurate coordinate transformation unavailable; ballpark forbidden')
    transformer = group.transformers[0]
    if transformer.accuracy < 0 or transformer.accuracy > 5:
        raise ValueError('Unknown or insufficient coordinate transformation accuracy')
    projected = {key: [transformer.transform(*p, errcheck=True) for p in points] for key, points in shapes.items()}
    db, road_counts, bounds = build_index(args.roads, args.output, projected, args.radius)
    counts, summaries = collections.Counter(), []
    with (args.output/'candidates.jsonl').open('x', encoding='utf-8') as out:
        for shape_id, points in projected.items():
            per_shape, previous = collections.Counter(), None
            for edge, along, point, direction in sample_path(points, args.spacing):
                x, y = point
                rows = db.execute('SELECT r.id,r.payload FROM boxes b JOIN roads r ON b.id=r.id WHERE b.minx<=? AND b.maxx>=? AND b.miny<=? AND b.maxy>=?',
                                  (x+args.radius, x-args.radius, y+args.radius, y-args.radius))
                candidates = []
                for row_id, payload in rows:
                    road = json.loads(payload)
                    match = nearest(point, direction, road['parts'])
                    if not match or match[0] > args.radius or match[1] < .7:
                        continue
                    distance, alignment, orientation = match
                    candidates.append(dict(id=row_id, linkId=road['linkId'], parts=road['parts'],
                        distanceMetres=round(distance, 3), headingAlignment=round(alignment, 6),
                        orientation=orientation, score=distance+20*(1-alignment), attributes=road['attributes']))
                candidates.sort(key=lambda r: (r['score'], r['id']))
                status = classify(candidates, args.ambiguity_margin)
                continuity = topology(previous, candidates[0]) if previous and candidates else 'NOT_EVALUATED'
                if candidates and len(candidates[0]['parts']) > 1:
                    continuity = 'MULTIPART_GEOMETRY_REQUIRES_REVIEW'
                # Never carry a chosen link across ambiguous/unmatched observations.
                previous = candidates[0] if status == 'GEOMETRY_CANDIDATE_REQUIRES_REVIEW' else None
                per_shape[status] += 1
                counts[status] += 1
                counts[continuity] += 1
                out.write(json.dumps(dict(shapeId=shape_id, shapeEdge=edge, distanceAlongMetres=round(along, 3),
                    status=status, continuity=continuity, productionReady=False,
                    candidates=[{k:v for k,v in item.items() if k not in ('parts','id')} for item in candidates[:3]]), ensure_ascii=False)+'\n')
            summaries.append(dict(shapeId=shape_id, counts=dict(per_shape)))
    db.close()
    report = dict(schemaVersion=1, artifactType='ROAD_GEOMETRY_REVIEW_CANDIDATES', productionReady=False,
        summary=dict(shapeCount=len(shapes), approvedMappings=0, counts=dict(counts), **road_counts),
        configuration=configuration, invalidShapes=invalid, requestedShapesMissing=missing,
        projection=dict(sourceWkt=prj, operation=transformer.description, accuracyMetres=transformer.accuracy,
                        indexBoundsMetres=bounds),
        providerContract=dict(runtimeNamespace='SEOUL_ACCINFO_LINK', sourceFields=['LINK_ID','TRAF_ID_P','TRAF_ID_N'],
                              equivalenceVerified=False),
        blockers=['PROVIDER_LINK_NAMESPACE_EQUIVALENCE_UNVERIFIED', 'ROAD_DIRECTION_CODES_UNVERIFIED',
                  'BUS_CANONICAL_STOP_OCCURRENCE_MAPPING_REQUIRED', 'GRADE_SEPARATION_AND_PATH_REVIEW_REQUIRED',
                  'SOURCE_VALIDITY_PERIOD_UNVERIFIED'], shapes=summaries)
    write_json(args.output/'report.json', report)
    outputs = {p.name: digest(p) for p in args.output.iterdir() if p.is_file()}
    write_json(manifest_path, dict(fingerprint=fingerprint, inputs=input_hashes, outputs=outputs,
                                   summary=report['summary'], productionReady=False))
    print(json.dumps(report['summary'], ensure_ascii=False))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--roads', type=Path, required=True)
    p.add_argument('--shapes', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--shape-id', action='append', default=[])
    p.add_argument('--max-shapes', type=int, default=0, help='0 processes all available shapes; positive value is an explicit smoke run')
    p.add_argument('--spacing', type=float, default=40)
    p.add_argument('--radius', type=float, default=50)
    p.add_argument('--ambiguity-margin', type=float, default=8)
    args = p.parse_args()
    if args.max_shapes < 0 or not all(math.isfinite(v) and v > 0 for v in (args.spacing,args.radius,args.ambiguity_margin)):
        p.error('limits must be finite and positive (max-shapes may be zero)')
    run(args)


if __name__ == '__main__':
    main()
