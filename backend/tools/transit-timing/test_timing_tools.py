"""공식 CSV의 막차·시발종착 결측·순환선과 감사 도구의 이중 합산 방지를 검증한다."""
import datetime as dt
import json
from pathlib import Path
import tempfile
import unittest
from build_timetable import build, seconds, KST
from audit_kakao_times import audit


class TimetableTest(unittest.TestCase):
    def setUp(self):
        self.master = dict(dataVersion='test-master', lines=[dict(
            subwayId='2', direction='IN', realtimeSubwayId='1002', stations=[dict(
                stationId=x, realtimeStationId=None) for x in ['A', 'B', 'A']])])

    def row(self, station, arr, dep, code='S1'):
        return dict(zip(('호선', '역사코드', '주중주말', '방향', '급행여부', '열차코드',
                         '열차도착시간', '열차출발시간'), ('2', station, 'DAY', 'IN', '0', code, arr, dep)))

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

    def test_residual_zero_does_not_authorize_wait_addition(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / '1.json'
            path.write_text(json.dumps(dict(status='OK', routes=[dict(properties=dict(totalTime=60),
                steps=[dict(properties=dict(time=60, type='SUBWAY'))])])), encoding='utf-8')
            report = audit([path])
            self.assertEqual(report['zeroResidual'], 1)
            self.assertFalse(report['decompositionVerified'])


if __name__ == '__main__':
    unittest.main()
