"""공식 서울교통공사 CSV를 명시한 운행일의 Backend 시간표로 변환한다.

입력은 CP949 CSV와 매핑에 실제 사용하는 수도권 마스터 JSON(.gz)이다. DB/Redis/API
접속 없이 정적 역 ID·방향·인접 순서를 대조한다. 원본 열차코드는 운행 key에만 쓰고
실시간 trainId는 기본적으로 null이며, 같은 기본 시간표에서 검증한 Crosswalk의 EXACT만
선택적으로 주입한다. 중간 결측/급행/마스터 불일치 운행은 전체 격리하며 사유별 수와 운행
key를 보고서에 남긴다. 부분 운행을 완전한 운행으로 만들지 않는다.

요일/공휴일을 추측하지 않도록 service-date와 day-type을 모두 요구한다. 입력한 날짜는
Replay 적용일이며 최신 개정 시간표임을 인증하지 않는다. 산출물은 PREVIEW 용도다.
"""
import argparse
import collections
import csv
import datetime as dt
import gzip
import hashlib
import json
from pathlib import Path

from build_train_crosswalk import apply_crosswalk

KST = dt.timezone(dt.timedelta(hours=9))
COLUMNS = ('호선', '역사코드', '주중주말', '방향', '급행여부', '열차코드',
           '열차도착시간', '열차출발시간')


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


def assign_version(data):
    """내용 기반 버전을 다시 계산한다. 기존 version 값은 해시 입력에 넣지 않는다."""
    versionless = {key: value for key, value in data.items() if key != 'version'}
    digest = hashlib.sha256(json.dumps(versionless, ensure_ascii=False, sort_keys=True).encode()).hexdigest()
    data['version'] = 'seoul-dated-v1-' + digest[:20]
    return data['version']


def build(rows, master, service_date, day_type, published_at, source):
    """한 운행일을 결정적으로 전개하고 시간표/격리 보고서를 반환하는 순수 변환이다.

    마스터의 동일 노선·방향·정적 역은 하나의 provider ID로만 대응되어야 한다.
    LOCAL 운행의 모든 인접 역이 마스터에 있어야 하며 이름 유사도/코드 절삭은 사용하지 않는다.
    첫 역 도착과 끝 역 출발만 null을 허용하여 없던 정차시간을 만들어내지 않는다.
    """
    if day_type not in ('DAY', 'SAT', 'END'):
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
    for row in rows:
        counts['inputRows'] += 1
        if row['주중주말'] != day_type:
            continue
        counts['selectedRows'] += 1
        key = (row['호선'], row['방향'], row['열차코드'])
        if not all(key):
            raise ValueError('MISSING_TRIP_ID')
        groups[key].append(row)
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
            for r in records:
                station = r['역사코드']
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
            rejected.append(dict(key=key, reason=reason, rows=len(records)))
    if not trips:
        raise ValueError('NO_USABLE_TRIPS')
    # 3. 입력 해시·명시 운행일을 버전에 포함하고 결과를 반환한다. 파일 전개일이 원천의 유효성 인증은 아니다.
    data = dict(masterDataVersion=master['dataVersion'], source=source,
                publishedAt=published_at.isoformat(), validFrom=start.isoformat(),
                validUntil=(start + dt.timedelta(hours=30)).isoformat(), trips=trips,
                limitations=['SUBWAY_LOCAL_ONLY_PARTIAL_COVERAGE', 'SUBWAY_SOURCE_CURRENTNESS_UNVERIFIED',
                             'SUBWAY_SERVICE_CALENDAR_MANUALLY_SELECTED'])
    assign_version(data)
    return data, dict(counts, selectedTrips=len(groups), acceptedTrips=len(trips),
                      rejectedTrips=len(rejected), rejectionCounts=dict(reason_counts), rejected=rejected,
                      realtimeVerifiedTrips=0, serviceDate=str(service_date), dayType=day_type,
                      masterDataVersion=master['dataVersion'], version=data['version'])


def main():
    """명시한 파일만 읽고 UTF-8 artifact와 감사 보고서를 지정한 경로에 저장한다."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--csv', type=Path, required=True)
    parser.add_argument('--master', type=Path, required=True)
    parser.add_argument('--service-date', type=dt.date.fromisoformat, required=True)
    parser.add_argument('--day-type', choices=['DAY', 'SAT', 'END'], required=True)
    parser.add_argument('--published-at', type=dt.datetime.fromisoformat, required=True)
    parser.add_argument('--source-url', required=True)
    parser.add_argument('--train-crosswalk', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    sha = hashlib.sha256(args.csv.read_bytes()).hexdigest()
    with args.csv.open(encoding='cp949', newline='') as handle:
        reader = csv.DictReader(handle)
        if not set(COLUMNS).issubset(reader.fieldnames or []):
            raise ValueError('CSV_COLUMNS_MISSING')
        data, report = build(reader, load_master(args.master), args.service_date, args.day_type,
                             args.published_at, args.source_url + ';sha256=' + sha + ';dayType=' + args.day_type)
    if args.train_crosswalk:
        crosswalk = json.loads(args.train_crosswalk.read_text(encoding='utf-8'))
        applied = apply_crosswalk(data, crosswalk)
        if applied:
            data['source'] += ';trainCrosswalk=' + crosswalk['version']
            if applied < len(data['trips']):
                data['limitations'].append('SUBWAY_REALTIME_CROSSWALK_PARTIAL')
            assign_version(data)
        report.update(crosswalkVersion=crosswalk['version'], realtimeVerifiedTrips=applied,
                      crosswalkExactMappings=applied, version=data['version'])
    encoded = json.dumps(data, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
    if len(encoded) > 32 * 1024 * 1024:
        raise ValueError('ARTIFACT_EXCEEDS_BACKEND_32_MIB_LIMIT')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(encoded)
    report.update(sourceSha256=sha, artifactSha256=hashlib.sha256(encoded).hexdigest(), artifactBytes=len(encoded))
    args.output.with_suffix('.report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({k: v for k, v in report.items() if k != 'rejected'}, ensure_ascii=True))


if __name__ == '__main__':
    main()
