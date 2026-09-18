"""공식 CSV의 막차·시발종착 결측·순환선과 감사 도구의 이중 합산 방지, 격리 분류·manifest·GTFS 변환을 검증한다."""
import datetime as dt
import json
from pathlib import Path
import tempfile
import unittest
from build_timetable import build, seconds, write_outputs, category, KST, QUARANTINE_CATEGORIES
from audit_kakao_times import audit
import gtfs_timetable


class TimetableTest(unittest.TestCase):
    def setUp(self):
        self.master = dict(dataVersion='test-master', lines=[dict(
            subwayId='2', direction='IN', realtimeSubwayId='1002', stations=[dict(
                stationId=x, realtimeStationId=None) for x in ['A', 'B', 'A']])])

    def row(self, station, arr, dep, code='S1', day='DAY'):
        return dict(zip(('호선', '역사코드', '주중주말', '방향', '급행여부', '열차코드',
                         '열차도착시간', '열차출발시간'), ('2', station, day, 'IN', '0', code, arr, dep)))

    def make(self, rows, **kwargs):
        return build(rows, self.master, kwargs.get('date', dt.date(2026, 9, 11)), kwargs.get('day', 'DAY'),
                     dt.datetime(2026, 9, 10, tzinfo=KST), 'synthetic-test-only')

    def test_midnight_endpoint_null_and_missing_live_id_remain_explicit(self):
        data, report = self.make([self.row('A', '', '24:01:00'), self.row('B', '24:05:00', '')])
        trip = data['trips'][0]
        self.assertIsNone(trip['trainId'])
        self.assertIsNone(trip['calls'][0]['providerStationId'])
        self.assertIsNone(trip['calls'][0]['arrivalAt'])
        self.assertEqual(trip['calls'][1]['arrivalAt'], '2026-09-12T00:05:00+09:00')
        self.assertEqual(report['realtimeVerifiedTrips'], 0)

    def test_circular_return_is_kept_in_temporal_order(self):
        rows = [self.row('A', '09:00:00', '09:00:30'), self.row('B', '09:02:00', '09:02:30'),
                self.row('A', '09:04:00', '09:04:30')]
        data, _ = self.make(rows[::-1])
        self.assertEqual([c['stationId'] for c in data['trips'][0]['calls']], ['A', 'B', 'A'])
        self.assertEqual(data, self.make(rows)[0])

    def test_bad_trip_is_quarantined_whole_not_shortened(self):
        good = [self.row('A', '', '09:00:00'), self.row('B', '09:02:00', '')]
        bad = [self.row('A', '', '09:00:00', 'bad'), self.row('B', '', '09:02:00', 'bad'),
               self.row('A', '09:04:00', '', 'bad')]
        data, report = self.make(good + bad)
        self.assertEqual(len(data['trips']), 1)
        self.assertEqual(report['rejectionCounts'], {'INTERMEDIATE_TIME_MISSING': 1})

    def test_day_selection_requires_explicit_holiday_and_offsets(self):
        rows = [self.row('A', '', '09:00:00'), self.row('B', '09:02:00', '')]
        with self.assertRaisesRegex(ValueError, 'DAY_TYPE_DATE_CONFLICT'):
            self.make(rows, date=dt.date(2026, 9, 12))
        for invalid in ['30:00:00', '25:60:00', '-1:00:00', '09:00']:
            with self.assertRaises(ValueError):
                seconds(invalid)

    def test_quarantine_is_split_by_category_and_missing_day_types_are_reported(self):
        good = [self.row('A', '', '09:00:00'), self.row('B', '09:02:00', '')]
        unmapped = [self.row('A', '', '09:10:00', 'u'), self.row('Z', '09:12:00', '', 'u')]
        duplicate = [self.row('A', '', '09:20:00', 'd'), self.row('B', '09:22:00', '09:22:30', 'd'),
                     self.row('B', '09:22:00', '09:22:30', 'd')]
        reversed_time = [self.row('A', '', '09:30:00', 'r'), self.row('B', '09:29:00', '', 'r')]
        express = [dict(self.row('A', '', '09:40:00', 'e'), 급행여부='1'), dict(self.row('B', '09:42:00', '', 'e'), 급행여부='1')]
        saturday_only = [self.row('A', '', '10:00:00', 'sat', day='SAT'), self.row('B', '10:02:00', '', 'sat', day='SAT')]
        data, report = self.make(good + unmapped + duplicate + reversed_time + express + saturday_only)
        self.assertEqual(len(data['trips']), 1)
        self.assertEqual(report['quarantineCounts'],
                         {'duplicates': 1, 'express': 1, 'time-reversal': 1, 'unmapped': 1})
        self.assertEqual([t['key'] for t in report['quarantine']['unmapped']], ['2026-09-11:2:IN:u'])
        self.assertEqual(report['quarantine']['unmapped'][0]['stations'], ['A', 'Z'])
        self.assertEqual(report['dayTypesPresent'], {'DAY': 11, 'SAT': 2})
        self.assertEqual(report['missingDayTypes'], ['END'])
        # 요청한 운행일 종류가 입력에 없으면 다른 요일 자료로 시간표를 만들지 않는다.
        with self.assertRaisesRegex(ValueError, 'NO_ROWS_FOR_DAY_TYPE:END'):
            build(good, self.master, dt.date(2026, 9, 13), 'END', dt.datetime(2026, 9, 10, tzinfo=KST), 'x')
        for reason in ['NON_ADJACENT_STATIONS', 'PROVIDER_LINE_CONFLICT']:
            self.assertEqual(category(reason), 'master-mismatch')
        self.assertEqual(category('SOMETHING_NEW'), 'other')

    def test_outputs_are_reproducible_with_manifest_and_separate_quarantine_files(self):
        rows = [self.row('A', '', '09:00:00'), self.row('B', '09:02:00', ''),
                self.row('A', '', '09:10:00', 'u'), self.row('Z', '09:12:00', '', 'u')]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'source.csv'
            source.write_text('synthetic', encoding='utf-8')
            master_path = root / 'master.json'
            master_path.write_text(json.dumps(self.master), encoding='utf-8')
            fixed = dt.datetime(2026, 9, 18, tzinfo=KST)
            data, report = self.make(rows)
            first = write_outputs(root / 'out' / 'timetable.json', data, report, [source], master_path, fixed)
            second = write_outputs(root / 'out2' / 'timetable.json', data, report, [source], master_path, fixed)
            self.assertEqual(first['artifactSha256'], second['artifactSha256'])
            self.assertEqual(first['inputSha256'], {'source.csv': first['inputSha256']['source.csv']})
            self.assertFalse(first['serverReady'])
            written = {p.name for p in (root / 'out').iterdir()}
            expected = {'timetable.json', 'timetable.report.json', 'timetable.manifest.json',
                        'timetable.missing-service-days.json', 'timetable.quarantine.other.json'}
            expected |= {'timetable.quarantine.%s.json' % name for name in QUARANTINE_CATEGORIES}
            self.assertEqual(written, expected)
            unmapped = json.loads((root / 'out' / 'timetable.quarantine.unmapped.json').read_text(encoding='utf-8'))
            self.assertEqual(unmapped['count'], 1)
            self.assertEqual(unmapped['trips'][0]['reason'], 'STATION_CROSSWALK_MISSING_OR_AMBIGUOUS')
            missing = json.loads((root / 'out' / 'timetable.missing-service-days.json').read_text(encoding='utf-8'))
            self.assertEqual(missing['missingDayTypes'], ['SAT', 'END'])
            artifact = json.loads((root / 'out' / 'timetable.json').read_text(encoding='utf-8'))
            self.assertEqual(artifact['version'], first['version'])
            self.assertIn('SUBWAY_SOURCE_CURRENTNESS_UNVERIFIED', artifact['limitations'])

    def test_residual_zero_does_not_authorize_wait_addition(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / '1.json'
            path.write_text(json.dumps(dict(status='OK', routes=[dict(properties=dict(totalTime=60),
                steps=[dict(properties=dict(time=60, type='SUBWAY'))])])), encoding='utf-8')
            report = audit([path])
            self.assertEqual(report['zeroResidual'], 1)
            self.assertFalse(report['decompositionVerified'])


class GtfsTest(unittest.TestCase):
    def write_gtfs(self, root, calendar_rows):
        def write(name, header, rows):
            (root / name).write_text('\n'.join([','.join(header)] + [','.join(r) for r in rows]) + '\n', encoding='utf-8')
        write('routes.txt', ['route_id', 'agency_id', 'route_short_name', 'route_long_name', 'route_type'], [
            ['RR_ACC1_S-1-02-1I', 'A1', '서울2호선', '서울2호선(본선)<내선>', '1'],
            ['RR_ACC1_S-1-09-2D', 'A1', '서울9호선', '서울9호선(급행)<하행>', '1'],
            ['RR_TAGO_NAT_X', 'A1', '경의중앙', '경의중앙선<상행>', '2'],
            ['BR_TAGO_1', 'A1', '100', '-', '3']])
        write('trips.txt', ['route_id', 'service_id', 'trip_id'], [
            ['RR_ACC1_S-1-02-1I', 'B1', 'T1'], ['RR_ACC1_S-1-02-1I', 'B1', 'T2'],
            ['RR_ACC1_S-1-02-1I', 'OFF', 'T3'], ['RR_ACC1_S-1-09-2D', 'B1', 'T9'], ['RR_TAGO_NAT_X', 'B1', 'TX']])
        write('stops.txt', ['stop_id', 'stop_name', 'stop_lat', 'stop_lon'], [
            ['RS_ACC1_S-1-0201', '시청', '37.5', '127.0'], ['RS_ACC1_S-1-0202', '을지로입구', '37.5', '127.0'],
            ['RS_TAGO_NAT1', '먼역', '37.5', '127.0']])
        write('stop_times.txt', ['trip_id', 'arrival_time', 'departure_time', 'stop_id', 'stop_sequence'], [
            ['T1', '', '09:00:00', 'RS_ACC1_S-1-0201', '1'], ['T1', '09:02:00', '', 'RS_ACC1_S-1-0202', '2'],
            ['T2', '', '09:10:00', 'RS_ACC1_S-1-0201', '1'], ['T2', '09:12:00', '', 'RS_TAGO_NAT1', '2'],
            ['T3', '', '09:20:00', 'RS_ACC1_S-1-0201', '1'], ['T3', '09:22:00', '', 'RS_ACC1_S-1-0202', '2'],
            ['T9', '', '09:30:00', 'RS_ACC1_S-1-0201', '1'], ['T9', '09:32:00', '', 'RS_ACC1_S-1-0202', '2'],
            ['TX', '', '09:40:00', 'RS_TAGO_NAT1', '1']])
        write('calendar.txt', ['service_id', 'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday',
                               'sunday', 'start_date', 'end_date'], calendar_rows)

    def test_gtfs_rows_follow_deterministic_mapping_and_report_unmapped_parts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_gtfs(root, [['B1', '1', '1', '1', '1', '1', '1', '1', '20170101', '20301231'],
                                   ['OFF', '0', '0', '0', '0', '0', '0', '0', '20170101', '20301231']])
            rows, report = gtfs_timetable.rows_from_gtfs(root, dt.date(2026, 9, 18), 'DAY')
            self.assertEqual([(r['호선'], r['역사코드'], r['방향'], r['급행여부'], r['열차코드']) for r in rows], [
                ('2', '0201', 'IN', '0', 'T1'), ('2', '0202', 'IN', '0', 'T1'),
                ('9', '0201', 'DOWN', '1', 'T9'), ('9', '0202', 'DOWN', '1', 'T9')])
            self.assertEqual(report['unmappedRoutes'], [dict(routeId='RR_TAGO_NAT_X', name='경의중앙선<상행>')])
            self.assertEqual(report['tripsWithUnmappedStops'], 1)          # T2 는 통째로 제외
            self.assertEqual(report['unmappedStops'], [('RS_TAGO_NAT1', 1)])
            self.assertEqual(report['inactiveTrips'], 1)                   # T3 는 운행하지 않는 service
            self.assertFalse(report['dayTypeDistinguishable'])
            self.assertEqual(report['dayTypeSource'], 'REQUESTED_NOT_FROM_DATA')
            master = dict(dataVersion='m', lines=[dict(subwayId='2', direction='IN', realtimeSubwayId='1002',
                                                        stations=[dict(stationId='0201'), dict(stationId='0202')])])
            data, built = build(rows, master, dt.date(2026, 9, 18), 'DAY', dt.datetime(2026, 9, 18, tzinfo=KST), 'x',
                                ['SUBWAY_GTFS_CALENDAR_WITHOUT_DAY_TYPE'])
            self.assertEqual([t['key'] for t in data['trips']], ['2026-09-18:2:IN:T1'])
            self.assertEqual(built['quarantineCounts'], {'express': 1})
            self.assertIn('SUBWAY_GTFS_CALENDAR_WITHOUT_DAY_TYPE', data['limitations'])

    def test_gtfs_calendar_with_day_types_selects_by_date(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_gtfs(root, [['B1', '1', '1', '1', '1', '1', '0', '0', '20260101', '20261231'],
                                   ['OFF', '0', '0', '0', '0', '0', '1', '1', '20260101', '20261231']])
            (root / 'calendar_dates.txt').write_text('service_id,date,exception_type\nB1,20260918,2\n', encoding='utf-8')
            rows, report = gtfs_timetable.rows_from_gtfs(root, dt.date(2026, 9, 18), 'DAY')
            self.assertTrue(report['dayTypeDistinguishable'])
            self.assertEqual(report['activeServiceIds'], [])                # 예외일로 B1 제외
            self.assertEqual(rows, [])
            rows, report = gtfs_timetable.rows_from_gtfs(root, dt.date(2026, 9, 17), 'DAY')
            self.assertEqual(report['activeServiceIds'], ['B1'])
            self.assertEqual({r['열차코드'] for r in rows}, {'T1', 'T9'})


if __name__ == '__main__':
    unittest.main()
