"""공식 서울교통공사 CSV(또는 GTFS)를 명시한 운행일의 Backend 시간표로 변환한다.

입력은 CP949 CSV와 매핑에 실제 사용하는 수도권 마스터 JSON(.gz)이다. DB/Redis/API
접속 없이 정적 역 ID·방향·인접 순서를 대조한다. 원본 열차코드는 운행 key에만 쓰고
실시간 trainId는 null로 보존한다. 중간 결측/급행/마스터 불일치 운행은 전체
격리하며 사유별 수와 운행 key를 보고서에 남긴다. 부분 운행을 완전한 운행으로 만들지 않는다.

요일/공휴일을 추측하지 않도록 service-date와 day-type을 모두 요구한다. 입력한 날짜는
Replay 적용일이며 최신 개정 시간표임을 인증하지 않는다. 산출물은 PREVIEW 용도다.

산출물(--output 기준):
  timetable.json                     Backend SubwayTimetable.Data
  timetable.report.json              채택/격리 수, 사유별 수, 운행일 종류 유무, 해시
  timetable.manifest.json            입력·마스터·산출물 SHA-256과 버전(재현 확인용)
  timetable.quarantine.<분류>.json    unmapped / master-mismatch / duplicates / time-reversal / express / other
  timetable.missing-service-days.json 입력에 없는 운행일 종류(DAY/SAT/END)와 선택 결과
GTFS 입력(--gtfs)은 gtfs_timetable.py 의 결정적 규칙으로 같은 행 형식으로 바꾼 뒤 동일하게 검증한다.
"""
import argparse
import collections
import csv
import datetime as dt
import gzip
import hashlib
import json
import sys
from pathlib import Path

KST = dt.timezone(dt.timedelta(hours=9))
COLUMNS = ('호선', '역사코드', '주중주말', '방향', '급행여부', '열차코드',
           '열차도착시간', '열차출발시간')
DAY_TYPES = ('DAY', 'SAT', 'END')
TOOL_VERSION = 'build_timetable/2026-09-18'
# 격리 사유를 산출물 파일로 나누는 기준. 새 사유는 여기에 등록한다(등록하지 않으면 other).
QUARANTINE_CATEGORIES = {
    'unmapped': ('STATION_CROSSWALK_MISSING_OR_AMBIGUOUS',),
    'master-mismatch': ('NON_ADJACENT_STATIONS', 'PROVIDER_LINE_CONFLICT'),
    'duplicates': ('DUPLICATE_STOP_ROW',),
    'time-reversal': ('NON_MONOTONIC_TIME', 'INTERMEDIATE_TIME_MISSING', 'MISSING_BOTH_TIMES', 'INVALID_CLOCK'),
    'express': ('EXPRESS_UNSUPPORTED',),
}


def category(reason):
    """격리 사유를 산출물 분류로 바꾼다. 모르는 사유는 other 로 남겨 잃지 않는다."""
    for name, reasons in QUARANTINE_CATEGORIES.items():
        if reason in reasons:
            return name
    return 'other'


def seconds(value):
    """24/25시를 같은 운행일의 경과 초로 읽는다. 빈 시각을 0초로 치환하지 않는다."""
    if not value:
        return None
    parts = value.split(':')
    if len(parts) != 3 or any(not p.isdigit() for p in parts):
        raise ValueError('INVALID_CLOCK')
    h, m, s = map(int, parts)
    if h >= 30 or m >= 60 or s >= 60:
        raise ValueError('INVALID_CLOCK')
    return h * 3600 + m * 60 + s


def load_master(path):
    """gzip 여부만 확장자로 선택한다. 원본 정적 ID의 선행 0을 변경하지 않는다."""
    raw = path.read_bytes()
    return json.loads(gzip.decompress(raw) if path.suffix == '.gz' else raw)


def sha256_file(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def build(rows, master, service_date, day_type, published_at, source, limitations=()):
    """한 운행일을 결정적으로 전개하고 시간표/격리 보고서를 반환하는 순수 변환이다.

    마스터의 동일 노선·방향·정적 역은 하나의 provider ID로만 대응되어야 한다.
    LOCAL 운행의 모든 인접 역이 마스터에 있어야 하며 이름 유사도/코드 절삭은 사용하지 않는다.
    첫 역 도착과 끝 역 출발만 null을 허용하여 없던 정차시간을 만들어내지 않는다.
    """
    if day_type not in DAY_TYPES:
        raise ValueError('INVALID_DAY_TYPE')
    if (day_type == 'DAY' and service_date.weekday() >= 5) or (day_type == 'SAT' and service_date.weekday() != 5):
        raise ValueError('DAY_TYPE_DATE_CONFLICT')
    if published_at.tzinfo is None:
        raise ValueError('PUBLISHED_AT_OFFSET_REQUIRED')
    if not master.get('dataVersion'):
        raise ValueError('MASTER_VERSION_REQUIRED')
    # 1. 버전 고정 마스터로 검증 인덱스를 만든다. 여러 서비스 패턴의 동일 대응은 중복 제거한다.
    stations = collections.defaultdict(set)
    edges = set()
    for line in master['lines']:
        ordered = line['stations']
        for s in ordered:
            stations[(line['subwayId'], line['direction'], s['stationId'])].add(
                (line.get('realtimeSubwayId'), s.get('realtimeStationId')))
        for a, b in zip(ordered, ordered[1:]):
            edges.add((line['subwayId'], line['direction'], a['stationId'], b['stationId']))
    groups = collections.defaultdict(list)
    counts = collections.Counter()
    day_types_present = collections.Counter()
    for row in rows:
        counts['inputRows'] += 1
        day_types_present[row['주중주말']] += 1
        if row['주중주말'] != day_type:
            continue
        counts['selectedRows'] += 1
        key = (row['호선'], row['방향'], row['열차코드'])
        if not all(key):
            raise ValueError('MISSING_TRIP_ID')
        groups[key].append(row)
    # 요청한 운행일 종류가 입력에 없으면 빈 시간표를 만들지 않고 멈춘다(누락 운행일은 보고서에 남긴다).
    missing_day_types = [d for d in DAY_TYPES if d not in day_types_present]
    if counts['selectedRows'] == 0:
        raise ValueError('NO_ROWS_FOR_DAY_TYPE:' + day_type + ':present=' + ','.join(sorted(day_types_present)))
    start = dt.datetime.combine(service_date, dt.time(), KST)
    trips, rejected = [], []
    reason_counts = collections.Counter()
    # 2. 원본의 운행 전체를 검증한다. 한 행 불량을 지워서 가짜 직통 열차를 만들지 않는다.
    for (line, direction, code), records in sorted(groups.items()):
        key = '{}:{}:{}:{}'.format(service_date, line, direction, code)
        try:
            if any(r['급행여부'] != '0' for r in records):
                raise ValueError('EXPRESS_UNSUPPORTED')
            calls = []
            seen_rows = set()
            for r in records:
                station = r['역사코드']
                signature = (station, r['열차도착시간'], r['열차출발시간'])
                if signature in seen_rows:
                    raise ValueError('DUPLICATE_STOP_ROW')
                seen_rows.add(signature)
                arr, dep = seconds(r['열차도착시간']), seconds(r['열차출발시간'])
                if arr is None and dep is None:
                    raise ValueError('MISSING_BOTH_TIMES')
                matches = stations.get((line, direction, station), set())
                if len(matches) != 1:
                    raise ValueError('STATION_CROSSWALK_MISSING_OR_AMBIGUOUS')
                provider_line, provider_station = next(iter(matches))
                calls.append((arr if arr is not None else dep, station, provider_line, provider_station, arr, dep))
            calls.sort()
            if len(calls) < 2:
                raise ValueError('TOO_FEW_STATIONS')
            previous = None
            for i, (_, station, provider_line, _, arr, dep) in enumerate(calls):
                if (arr is None and i != 0) or (dep is None and i != len(calls) - 1):
                    raise ValueError('INTERMEDIATE_TIME_MISSING')
                a = arr if arr is not None else dep
                d = dep if dep is not None else arr
                if d < a or (previous is not None and a <= previous):
                    raise ValueError('NON_MONOTONIC_TIME')
                if i and (line, direction, calls[i-1][1], station) not in edges:
                    raise ValueError('NON_ADJACENT_STATIONS')
                if provider_line != calls[0][2]:
                    raise ValueError('PROVIDER_LINE_CONFLICT')
                previous = d
            def stamp(value):
                return (start + dt.timedelta(seconds=value)).isoformat() if value is not None else None
            trips.append(dict(key=key, lineId=line, providerLineId=calls[0][2], direction=direction,
                              trainId=None, arrivalDirection=None, positionDirection=None,
                              arrivalDestination=None, positionDestination=None, trainType=None,
                              serviceDate=service_date.isoformat(), calls=[dict(
                                  stationId=c[1], providerStationId=c[3], arrivalAt=stamp(c[4]),
                                  departureAt=stamp(c[5]), stops=True) for c in calls]))
        except ValueError as invalid:
            reason = str(invalid)
            reason_counts[reason] += 1
            rejected.append(dict(key=key, reason=reason, category=category(reason), rows=len(records),
                                 stations=sorted({r['역사코드'] for r in records})))
    if not trips:
        raise ValueError('NO_USABLE_TRIPS')
    quarantine = collections.defaultdict(list)
    for entry in rejected:
        quarantine[entry['category']].append(entry)
    # 3. 입력 해시·명시 운행일을 버전에 포함하고 결과를 반환한다. 파일 전개일이 원천의 유효성 인증은 아니다.
    all_limitations = ['SUBWAY_LOCAL_ONLY_PARTIAL_COVERAGE', 'SUBWAY_SOURCE_CURRENTNESS_UNVERIFIED',
                       'SUBWAY_SERVICE_CALENDAR_MANUALLY_SELECTED']
    for extra in limitations:
        if extra not in all_limitations:
            all_limitations.append(extra)
    data = dict(masterDataVersion=master['dataVersion'], source=source,
                publishedAt=published_at.isoformat(), validFrom=start.isoformat(),
                validUntil=(start + dt.timedelta(hours=30)).isoformat(), trips=trips,
                limitations=all_limitations)
    digest = hashlib.sha256(json.dumps(data, ensure_ascii=False, sort_keys=True).encode()).hexdigest()
    data['version'] = 'seoul-dated-v1-' + digest[:20]
    return data, dict(counts, selectedTrips=len(groups), acceptedTrips=len(trips),
                      rejectedTrips=len(rejected), rejectionCounts=dict(reason_counts), rejected=rejected,
                      quarantineCounts={name: len(entries) for name, entries in sorted(quarantine.items())},
                      quarantine={name: entries for name, entries in sorted(quarantine.items())},
                      dayTypesPresent=dict(day_types_present), missingDayTypes=missing_day_types,
                      realtimeVerifiedTrips=0, serviceDate=str(service_date), dayType=day_type,
                      masterDataVersion=master['dataVersion'], version=data['version'],
                      limitations=all_limitations)


def write_outputs(output, data, report, inputs, master_path, generated_at=None):
    """산출물·보고서·manifest·격리 파일을 output 옆에 쓴다. 같은 입력이면 같은 해시가 나온다."""
    encoded = json.dumps(data, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
    if len(encoded) > 32 * 1024 * 1024:
        raise ValueError('ARTIFACT_EXCEEDS_BACKEND_32_MIB_LIMIT')
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(encoded)
    stem = output.with_suffix('')
    input_hashes = {str(Path(p).name): sha256_file(p) for p in inputs}
    manifest = dict(tool=TOOL_VERSION, generatedAt=(generated_at or dt.datetime.now(KST)).isoformat(),
                    artifact=output.name, artifactSha256=hashlib.sha256(encoded).hexdigest(),
                    artifactBytes=len(encoded), version=data['version'], masterDataVersion=data['masterDataVersion'],
                    masterSha256=sha256_file(master_path), inputSha256=input_hashes, source=data['source'],
                    serviceDate=report['serviceDate'], dayType=report['dayType'],
                    acceptedTrips=report['acceptedTrips'], rejectedTrips=report['rejectedTrips'],
                    quarantineCounts=report['quarantineCounts'], missingDayTypes=report['missingDayTypes'],
                    limitations=data['limitations'],
                    serverReady=False,
                    serverReadyNote='서버 연결 전 사람이 원천 개정판·운행일·격리 수를 확인해야 한다. 이 파일은 그 확인을 대신하지 않는다.')
    report = dict(report, sourceSha256=input_hashes, masterSha256=manifest['masterSha256'],
                  artifactSha256=manifest['artifactSha256'], artifactBytes=len(encoded))
    quarantine = report.pop('quarantine')
    files = {stem.with_suffix('.report.json'): report, stem.with_suffix('.manifest.json'): manifest,
             stem.with_suffix('.missing-service-days.json'): dict(
                 requestedDayType=report['dayType'], dayTypesPresent=report['dayTypesPresent'],
                 missingDayTypes=report['missingDayTypes'], serviceDate=report['serviceDate'],
                 note='운행일 종류가 입력에 없으면 그 요일의 시간표는 만들 수 없다. 다른 요일 자료로 대신하지 않는다.')}
    for name in list(QUARANTINE_CATEGORIES) + ['other']:
        files[stem.with_suffix('.quarantine.' + name + '.json')] = dict(
            category=name, reasons=list(QUARANTINE_CATEGORIES.get(name, ())), count=len(quarantine.get(name, [])),
            trips=quarantine.get(name, []))
    for path, payload in files.items():
        path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding='utf-8')
    return manifest


def read_csv_rows(path):
    with Path(path).open(encoding='cp949', newline='') as handle:
        reader = csv.DictReader(handle)
        if not set(COLUMNS).issubset(reader.fieldnames or []):
            raise ValueError('CSV_COLUMNS_MISSING')
        yield from reader


def main(argv=None):
    """명시한 파일만 읽고 UTF-8 artifact와 감사 보고서를 지정한 경로에 저장한다."""
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    source_group = parser.add_mutually_exclusive_group(required=True)
    source_group.add_argument('--csv', type=Path, help='서울교통공사 공식 CP949 CSV(공공데이터포털 15098251)')
    source_group.add_argument('--gtfs', type=Path, help='GTFS 폴더(routes/trips/stop_times/stops/calendar)')
    parser.add_argument('--master', type=Path, required=True)
    parser.add_argument('--service-date', type=dt.date.fromisoformat, required=True)
    parser.add_argument('--day-type', choices=list(DAY_TYPES), required=True)
    parser.add_argument('--published-at', type=dt.datetime.fromisoformat, required=True)
    parser.add_argument('--source-url', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--allow-undistinguished-day-type', action='store_true',
                        help='GTFS 달력에 요일 구분이 없어도 요청한 day-type 으로 전개한다(제한사항으로 기록, 서버 연결 불가)')
    args = parser.parse_args(argv)
    master = load_master(args.master)
    limitations = []
    if args.csv:
        inputs = [args.csv]
        rows = read_csv_rows(args.csv)
        source_note = ''
    else:
        import gtfs_timetable
        rows, gtfs_report = gtfs_timetable.rows_from_gtfs(args.gtfs, args.service_date, args.day_type)
        inputs = [args.gtfs / name for name in gtfs_timetable.INPUT_FILES if (args.gtfs / name).exists()]
        report_path = args.output.with_suffix('.gtfs-report.json')
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(json.dumps(gtfs_report, ensure_ascii=False, indent=2), encoding='utf-8')
        if not gtfs_report['dayTypeDistinguishable']:
            limitations.append('SUBWAY_GTFS_CALENDAR_WITHOUT_DAY_TYPE')
            if not args.allow_undistinguished_day_type:
                print(json.dumps(dict(status='REFUSED', reason='GTFS_CALENDAR_WITHOUT_DAY_TYPE',
                                      detail='GTFS 달력이 평일/토/휴일을 구분하지 않아 요청한 운행일 종류의 근거가 없다. '
                                             '--allow-undistinguished-day-type 로 전개해도 서버에는 연결하지 않는다.',
                                      gtfsReport=str(report_path)), ensure_ascii=False))
                return 2
        source_note = ';gtfs=' + ','.join(sorted(p.name for p in inputs))
    input_sha = ','.join(sha256_file(p) for p in inputs)
    source = args.source_url + ';sha256=' + input_sha + ';dayType=' + args.day_type + source_note
    data, report = build(rows, master, args.service_date, args.day_type, args.published_at, source, limitations)
    manifest = write_outputs(args.output, data, report, inputs, args.master)
    print(json.dumps({k: v for k, v in report.items() if k not in ('rejected', 'quarantine')}, ensure_ascii=True))
    print(json.dumps(dict(manifest=str(args.output.with_suffix('.manifest.json')),
                          artifactSha256=manifest['artifactSha256']), ensure_ascii=True))
    return 0


if __name__ == '__main__':
    sys.exit(main())
