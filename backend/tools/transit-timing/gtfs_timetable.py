"""국가 GTFS(KTDB 대중교통 GTFS)에서 서울교통공사 노선 운행을 build_timetable 의 행 형식으로 바꾼다.

매핑 규칙은 결정적이며 이름 유사도·코드 절삭을 쓰지 않는다.
- route_id  `RR_ACC1_S-1-<호선 2자리>-<패턴>[D|U|I|O]` → 호선(선행 0 제거), 방향 D=DOWN U=UP I=IN O=OUT
- stop_id   `RS_ACC1_S-1-<역사코드 4자리>` → 역사코드(서울교통공사 정적 역 코드와 같은 체계)
- route_long_name 에 '급행' 이 있으면 급행여부 '1'(build 가 EXPRESS_UNSUPPORTED 로 격리한다)
- 규칙에 맞지 않는 노선·역은 미매핑으로 세고, 미매핑 역이 하나라도 있는 운행은 통째로 뺀다(부분 운행 금지).
- 운행일: calendar/calendar_dates 로 service-date 에 운행하는 service_id 만 채택한다.
  달력이 요일을 구분하지 않으면(모든 service 가 매일 동일) dayTypeDistinguishable=false 로 보고한다.
  이때 '주중주말' 값은 요청한 day-type 을 그대로 적은 것이며 자료에서 확인한 것이 아니다.
"""
import collections
import csv
import re
from pathlib import Path

INPUT_FILES = ('routes.txt', 'trips.txt', 'stop_times.txt', 'stops.txt', 'calendar.txt', 'calendar_dates.txt')
ROUTE = re.compile(r'^RR_ACC1_S-1-(\d{2})-\d+([DUIO])$')
STOP = re.compile(r'^RS_ACC1_S-1-(\d{4})$')
DIRECTIONS = {'D': 'DOWN', 'U': 'UP', 'I': 'IN', 'O': 'OUT'}
WEEKDAYS = ('monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday')
RAIL_ROUTE_TYPES = ('1', '2')


def read(path):
    with Path(path).open(encoding='utf-8-sig', newline='') as handle:
        yield from csv.DictReader(handle)


def active_services(gtfs_dir, service_date):
    """service_date 에 운행하는 service_id 와, 달력이 요일을 구분하는지 여부를 돌려준다."""
    gtfs_dir = Path(gtfs_dir)
    ymd = service_date.strftime('%Y%m%d')
    weekday = WEEKDAYS[service_date.weekday()]
    active, distinguishable, calendars = set(), False, 0
    for entry in read(gtfs_dir / 'calendar.txt'):
        calendars += 1
        if len({entry[day] for day in WEEKDAYS}) > 1:
            distinguishable = True
        if entry['start_date'] <= ymd <= entry['end_date'] and entry[weekday] == '1':
            active.add(entry['service_id'])
    dates = gtfs_dir / 'calendar_dates.txt'
    if dates.exists():
        for entry in read(dates):
            if entry['date'] != ymd:
                continue
            distinguishable = True
            if entry['exception_type'] == '1':
                active.add(entry['service_id'])
            else:
                active.discard(entry['service_id'])
    return active, distinguishable, calendars


def rows_from_gtfs(gtfs_dir, service_date, day_type):
    """GTFS 폴더를 읽어 (행 목록, GTFS 보고서) 를 돌려준다. 행 형식은 build_timetable.COLUMNS 와 같다."""
    gtfs_dir = Path(gtfs_dir)
    routes, unmapped_routes = {}, []
    for route in read(gtfs_dir / 'routes.txt'):
        if route.get('route_type') not in RAIL_ROUTE_TYPES:
            continue
        match = ROUTE.match(route['route_id'])
        if not match:
            unmapped_routes.append(dict(routeId=route['route_id'], name=route.get('route_long_name') or route.get('route_short_name')))
            continue
        express = '1' if '급행' in (route.get('route_long_name') or '') else '0'
        routes[route['route_id']] = (str(int(match.group(1))), DIRECTIONS[match.group(2)], express)
    active, distinguishable, calendars = active_services(gtfs_dir, service_date)
    trips, inactive = {}, 0
    for trip in read(gtfs_dir / 'trips.txt'):
        route = routes.get(trip['route_id'])
        if route is None:
            continue
        if trip['service_id'] in active:
            trips[trip['trip_id']] = route
        else:
            inactive += 1
    per_trip = collections.defaultdict(list)
    unmapped_stops = collections.Counter()
    unmapped_trip_ids = set()
    for stop_time in read(gtfs_dir / 'stop_times.txt'):
        route = trips.get(stop_time['trip_id'])
        if route is None:
            continue
        match = STOP.match(stop_time['stop_id'])
        if not match:
            unmapped_stops[stop_time['stop_id']] += 1
            unmapped_trip_ids.add(stop_time['trip_id'])
            continue
        line, direction, express = route
        per_trip[stop_time['trip_id']].append(dict(zip(
            ('호선', '역사코드', '주중주말', '방향', '급행여부', '열차코드', '열차도착시간', '열차출발시간'),
            (line, match.group(1), day_type, direction, express, stop_time['trip_id'],
             stop_time['arrival_time'], stop_time['departure_time']))))
    rows = []
    for trip_id in sorted(per_trip):
        if trip_id in unmapped_trip_ids:
            continue
        rows.extend(sorted(per_trip[trip_id], key=lambda r: r['열차도착시간'] or r['열차출발시간']))
    report = dict(gtfsDir=str(gtfs_dir), serviceDate=service_date.isoformat(), requestedDayType=day_type,
                  dayTypeDistinguishable=distinguishable,
                  dayTypeSource='CALENDAR' if distinguishable else 'REQUESTED_NOT_FROM_DATA',
                  calendars=calendars, activeServiceIds=sorted(active),
                  mappedRoutes=len(routes), unmappedRouteCount=len(unmapped_routes), unmappedRoutes=unmapped_routes[:200],
                  activeTrips=len(trips), inactiveTrips=inactive,
                  tripsWithUnmappedStops=len(unmapped_trip_ids),
                  unmappedStopCount=len(unmapped_stops), unmappedStops=unmapped_stops.most_common(100),
                  convertedTrips=len(per_trip) - len(unmapped_trip_ids & set(per_trip)), convertedRows=len(rows))
    return rows, report
