"""로컬 원본의 검토용 중간 자료만 생성한다. 서버 접속/운영 활성화 없음.

실측과 시간대 집계를 분리한다. canonical 매핑, 날씨, 원천 공개시각은
추정하지 않는다. 따라서 출력은 DT FACT_SCHEMA나 운영 baseline이 아니다.
입력/코드 해시별 디렉터리를 쓰며 성공한 결과만 재사용한다.
"""
import argparse
import collections
import csv
import datetime as dt
import hashlib
import io
import json
import math
from pathlib import Path
import sqlite3
import zipfile


def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def emit(stream, value):
    stream.write(json.dumps(value, ensure_ascii=False, sort_keys=True) + '\n')


def rows(stream):
    header = stream.readline()
    encoding = 'utf-8-sig'
    try:
        header.decode(encoding)
    except UnicodeDecodeError:
        encoding = 'cp949'
    # ZipExtFile와 로컬 파일 모두 seek 지원. 파일 전체를 메모리에 올리지 않는다.
    stream.seek(0)
    with io.TextIOWrapper(stream, encoding=encoding, newline='') as text:
        for row in csv.DictReader(text):
            yield {str(k).strip('\ufeff').strip(): (v or '').strip('\ufeff').strip()
                   for k, v in row.items()}


def clock(value):
    return dt.datetime.strptime(value, '%Y%m%d%H%M%S').replace(
        tzinfo=dt.timezone(dt.timedelta(hours=9)))


def traversal(source, output):
    trips = collections.defaultdict(dict)
    bad = set()
    reasons = collections.Counter()
    coverage = collections.Counter()
    total = 0
    with source.open('rb') as stream:
        for row in rows(stream):
            total += 1
            key = tuple(row.get(k, '') for k in
                        ('기준일', '노선ID', '운행계획ID', '운행순번'))
            try:
                if not all(key) or not row.get('정류장ID'):
                    raise ValueError('MISSING_ID')
                seq = int(row['정류장순번'])
                arr, dep = clock(row['도착일시']), clock(row['출발일시'])
                if seq < 1 or dep < arr:
                    raise ValueError('INVALID_STOP_TIME_OR_ORDER')
                item = (row['정류장ID'], arr, dep)
                if seq in trips[key]:
                    if trips[key][seq] != item:
                        bad.add(key)
                        reasons['CONFLICTING_STOP_SEQUENCE'] += 1
                    else:
                        reasons['EXACT_DUPLICATE'] += 1
                trips[key][seq] = item
            except (ValueError, KeyError):
                bad.add(key)
                reasons['INVALID_ROW_TRIP_QUARANTINED'] += 1
    accepted = 0
    with (output / 'traversals.jsonl').open('w', encoding='utf-8') as out:
        for key, stops in sorted(trips.items()):
            if key in bad:
                continue
            for seq in sorted(stops):
                if seq + 1 not in stops:
                    if seq != max(stops):
                        reasons['MISSING_ADJACENT_SEQUENCE'] += 1
                    continue
                a, b = stops[seq], stops[seq + 1]
                seconds = (b[1] - a[2]).total_seconds()
                # 1시간 이상 인접 구간은 삭제 대신 검토 대상으로 남긴다.
                if a[0] == b[0] or not 0 < seconds <= 3600:
                    reasons['INVALID_OR_REVIEW_DURATION'] += 1
                    continue
                identity = '|'.join(key + (str(seq), a[0], b[0]))
                emit(out, dict(sourceEventId=hashlib.sha256(identity.encode()).hexdigest(),
                    serviceDate=key[0], providerRouteId=key[1], tripPlanId=key[2],
                    tripSequence=key[3], fromStopId=a[0], toStopId=b[0],
                    fromOrder=seq, toOrder=seq+1, enteredAt=a[2].isoformat(),
                    exitedAt=b[1].isoformat(), durationSeconds=seconds,
                    sampleKind='ACTUAL_TRAVERSAL_CANDIDATE', weatherBucket=None,
                    canonicalSegmentId=None, sourceAvailableAt=None, productionReady=False))
                accepted += 1
                coverage[key[1]] += 1
    return dict(inputRows=total, candidateTraversals=accepted, trips=len(trips),
                quarantinedTrips=len(bad), reasons=dict(reasons),
                routeCoverage=dict(coverage), durationReviewThresholdSeconds=3600)


def hourly(source, output):
    db = sqlite3.connect(output / 'daily.sqlite')
    db.execute('CREATE TABLE daily(day TEXT, route TEXT, a TEXT, b TEXT, ao TEXT, bo TEXT, hour INTEGER, seconds REAL, conflict INTEGER DEFAULT 0, PRIMARY KEY(day,route,a,b,ao,bo,hour))')
    counts = collections.Counter()
    try:
        with zipfile.ZipFile(source) as archive:
            for name in sorted(archive.namelist()):
                if not name.lower().endswith('.csv'):
                    continue
                with archive.open(name) as stream:
                    for row in rows(stream):
                        counts['inputRows'] += 1
                        try:
                            day = dt.datetime.strptime(row['기준_날짜'], '%Y%m%d').date().isoformat()
                            required = ('노선_ID','출발_정류장_ID','도착_정류장_ID','출발_정류장_순서','도착_정류장_순서')
                            if not all(k in row for k in (*required, '운행시간_00시')):
                                raise RuntimeError('SOURCE_SCHEMA_MISMATCH')
                            ids = tuple(row[k] for k in required)
                            if not all(ids) or ids[1] == ids[2] or int(ids[4]) != int(ids[3]) + 1:
                                raise ValueError('INVALID_EDGE')
                        except (ValueError, KeyError):
                            counts['invalidRows'] += 1
                            continue
                        for hour in range(24):
                            value = row.get(f'운행시간_{hour:02d}시', '')
                            try:
                                seconds = float(value)
                                if not math.isfinite(seconds) or seconds <= 0:
                                    raise ValueError('MISSING_OR_NONPOSITIVE')
                            except ValueError:
                                counts['missingOrNonpositiveHourCells'] += 1
                                continue
                            db.execute('INSERT INTO daily(day,route,a,b,ao,bo,hour,seconds) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(day,route,a,b,ao,bo,hour) DO UPDATE SET conflict=CASE WHEN daily.seconds!=excluded.seconds THEN 1 ELSE daily.conflict END', (day, *ids, hour, seconds))
                db.commit()
                print(json.dumps(dict(member=name, rows=counts['inputRows']), ensure_ascii=False), flush=True)
        counts['conflictingDailyKeys'] = db.execute('SELECT count(*) FROM daily WHERE conflict=1').fetchone()[0]
        counts['distinctDailyKeys'] = db.execute('SELECT count(*) FROM daily WHERE conflict=0').fetchone()[0]
        with (output / 'hourly-reference.jsonl').open('w', encoding='utf-8') as out:
            for r in db.execute("SELECT route,a,b,ao,bo,cast(strftime('%w',day) as integer)+1,hour,avg(seconds),count(*),min(day),max(day) FROM daily WHERE conflict=0 GROUP BY route,a,b,ao,bo,strftime('%w',day),hour ORDER BY route,a,b,ao,bo,strftime('%w',day),hour"):
                emit(out, dict(zip(('providerRouteId','fromStopId','toStopId','fromOrder','toOrder','dow','hourSlot','meanOfHourlyValuesSeconds','observedDates','observationStart','observationEnd'),r)) | dict(sampleKind='PROVIDER_HOURLY_AGGREGATE', productionReady=False, canonicalSegmentId=None, weatherBucket=None))
                counts['referenceGroups'] += 1
    finally:
        db.close()
    return dict(counts)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('kind', choices=['traversal','hourly'])
    p.add_argument('--input', type=Path, required=True)
    p.add_argument('--output-root', type=Path, required=True)
    args = p.parse_args()
    fingerprint, code = sha(args.input), sha(Path(__file__))
    target = args.output_root / f'{args.kind}-{fingerprint[:16]}-{code[:12]}'
    if (target / 'complete.json').exists():
        manifest = json.loads((target / 'complete.json').read_text(encoding='utf-8'))
        if all(sha(target / name) == digest for name, digest in manifest['outputs'].items()):
            print(json.dumps(dict(reused=str(target)), ensure_ascii=False)); return
        raise ValueError('OUTPUT_CHECKSUM_MISMATCH')
    # 실패·실행 중 디렉터리를 덮어쓰지 않는다. 원인 확인 후 별도 output-root로 재개한다.
    target.mkdir(parents=True, exist_ok=False)
    result = (traversal if args.kind == 'traversal' else hourly)(args.input, target)
    manifest = dict(source=str(args.input.resolve()), sourceSha256=fingerprint,
        codeSha256=code, productionReady=False,
        blockedBy=['CANONICAL_MAPPING_UNVERIFIED','WEATHER_NOT_JOINED','SOURCE_AVAILABILITY_UNKNOWN'],
        result=result, outputs={f.name:sha(f) for f in target.iterdir() if f.is_file()})
    with (target / 'complete.json').open('x', encoding='utf-8') as stream:
        emit(stream, manifest)
    print(json.dumps(dict(output=str(target), result=result), ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
