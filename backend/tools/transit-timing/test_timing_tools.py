"""공식 CSV의 막차·시발종착 결측·순환선과 감사 도구의 이중 합산 방지를 검증한다."""
import datetime as dt
import json
from pathlib import Path
import tempfile
import unittest

from audit_kakao_times import audit
from build_timetable import KST, assign_version, build, seconds
from build_train_crosswalk import apply_crosswalk, build_crosswalk
from export_train_observations import build_observations, normalize_event


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


class TrainCrosswalkTest(unittest.TestCase):
    def timetable(self, duplicate_candidate=False):
        calls = [
            dict(stationId=station, providerStationId=provider, arrivalAt=when, departureAt=when, stops=True)
            for station, provider, when in [
                ('A', 'PA', '2026-09-11T09:00:00+09:00'),
                ('B', 'PB', '2026-09-11T09:02:00+09:00'),
                ('C', 'PC', '2026-09-11T09:04:00+09:00'),
            ]
        ]
        trips = [dict(key='trip-1', lineId='2', providerLineId='1002', direction='IN', trainId=None,
                      arrivalDirection=None, positionDirection=None, arrivalDestination=None,
                      positionDestination=None, trainType=None, serviceDate='2026-09-11', calls=calls)]
        if duplicate_candidate:
            trips.append(dict(trips[0], key='trip-2', calls=[dict(call) for call in calls]))
        data = dict(masterDataVersion='test-master', source='synthetic-test-only',
                    publishedAt='2026-09-10T00:00:00+09:00', validFrom='2026-09-11T00:00:00+09:00',
                    validUntil='2026-09-12T06:00:00+09:00', trips=trips, limitations=[])
        assign_version(data)
        return data

    def observations(self):
        return dict(schemaVersion='subway-train-observations-v1', observations=[
            dict(eventType='subway.arrival', eventId='arrival-A', providerLineId='1002',
                 providerTrainId='T1', providerStationId='PA', matchedAt='2026-09-11T09:00:30+09:00',
                 direction='내선', destination='성수', trainType='일반'),
            dict(eventType='subway.position', eventId='position-B', providerLineId='1002',
                 providerTrainId='T1', providerStationId='PB', matchedAt='2026-09-11T09:02:30+09:00',
                 direction='0', destination='성수'),
            dict(eventType='subway.arrival', eventId='arrival-C', providerLineId='1002',
                 providerTrainId='T1', providerStationId='PC', matchedAt='2026-09-11T09:04:30+09:00',
                 direction='내선', destination='성수', trainType='일반'),
        ])

    def make_crosswalk(self, timetable=None, observations=None):
        return build_crosswalk(timetable or self.timetable(), observations or self.observations(),
                               '2026-09-11T10:00:00+09:00', evidence_sha256='0' * 64)

    def test_unique_station_sequence_and_time_is_exact_and_can_be_applied(self):
        timetable = self.timetable()
        crosswalk, report = self.make_crosswalk(timetable)
        entry = crosswalk['entries'][0]
        self.assertEqual(entry['matchStatus'], 'EXACT')
        self.assertEqual(entry['staticTripKey'], 'trip-1')
        self.assertEqual(entry['scheduleOffsetSeconds'], 30)
        self.assertEqual(report['statusCounts'], {'EXACT': 1})
        self.assertEqual(apply_crosswalk(timetable, crosswalk), 1)
        self.assertEqual(timetable['trips'][0]['trainId'], 'T1')
        self.assertEqual(timetable['trips'][0]['arrivalDirection'], '내선')
        self.assertEqual(timetable['trips'][0]['positionDirection'], '0')

    def test_multiple_schedule_candidates_remain_ambiguous(self):
        crosswalk, report = self.make_crosswalk(self.timetable(duplicate_candidate=True))
        entry = crosswalk['entries'][0]
        self.assertEqual(entry['matchStatus'], 'AMBIGUOUS')
        self.assertEqual(entry['candidateTripKeys'], ['trip-1', 'trip-2'])
        self.assertEqual(report['exactCoverage'], 0.0)

    def test_missing_position_or_too_few_stations_remains_unmapped(self):
        observations = self.observations()
        observations['observations'] = [record for record in observations['observations']
                                        if record['eventType'] == 'subway.arrival']
        crosswalk, _ = self.make_crosswalk(observations=observations)
        self.assertEqual(crosswalk['entries'][0]['matchStatus'], 'UNMAPPED')
        self.assertEqual(crosswalk['entries'][0]['reason'], 'ARRIVAL_AND_POSITION_EVIDENCE_REQUIRED')

    def test_crosswalk_rejects_timetable_version_and_exact_duplicate(self):
        timetable = self.timetable()
        crosswalk, _ = self.make_crosswalk(timetable)
        invalid_version = dict(crosswalk, timetableVersion='other')
        with self.assertRaisesRegex(ValueError, 'TIMETABLE_VERSION_MISMATCH'):
            apply_crosswalk(timetable, invalid_version)
        duplicate = dict(crosswalk)
        duplicate['entries'] = [dict(crosswalk['entries'][0]), dict(crosswalk['entries'][0])]
        with self.assertRaisesRegex(ValueError, 'EXACT_DUPLICATE'):
            apply_crosswalk(self.timetable(), duplicate)


class TrainObservationExporterTest(unittest.TestCase):
    def event(self, event_type, event_id, station, raw, *, basis='provider'):
        return dict(
            eventId=event_id,
            eventType=event_type,
            occurredAt='2026-09-11T09:00:00+09:00',
            collectedAt='2026-09-11T09:00:05+09:00',
            payload=dict(
                providerLineId='1002', providerTrainId='T1', providerStationId=station,
                timestampBasis=basis, raw=raw,
            ),
        )

    def test_arrival_uses_provider_time_plus_remaining_seconds(self):
        event = self.event('subway.arrival', 'a1', 'PA', dict(
            recptnDt='2026-09-11 09:00:00', barvlDt='90', updnLine='내선',
            bstatnNm='성수', btrainStt='일반',
        ))
        observation, reason = normalize_event(event)
        self.assertIsNone(reason)
        self.assertEqual(observation['matchedAt'], '2026-09-11T09:01:30+09:00')
        self.assertEqual(observation['trainType'], '일반')

    def test_position_requires_verified_at_station_status(self):
        raw = dict(recptnDt='2026-09-11 09:02:00', trainSttus='0', updnLine='0', statnTnm='성수')
        _, reason = normalize_event(self.event('subway.position', 'p0', 'PB', raw))
        self.assertEqual(reason, 'POSITION_STATUS_NOT_AT_STATION')
        raw['trainSttus'] = '1'
        observation, reason = normalize_event(self.event('subway.position', 'p1', 'PB', raw))
        self.assertIsNone(reason)
        self.assertEqual(observation['matchedAt'], '2026-09-11T09:02:00+09:00')

    def test_received_time_fallback_and_outside_service_window_are_rejected(self):
        raw = dict(recptnDt='2026-09-11 09:00:00', barvlDt='30', updnLine='내선',
                   bstatnNm='성수', btrainStt='일반')
        fallback = self.event('subway.arrival', 'fallback', 'PA', raw, basis='received')
        outside = self.event('subway.arrival', 'outside', 'PA', dict(raw, recptnDt='2026-09-12 06:00:00'))
        document, report = build_observations([fallback, outside], dt.date(2026, 9, 11))
        self.assertEqual(document['observations'], [])
        self.assertEqual(report['rejectionCounts'], {
            'OUTSIDE_SERVICE_WINDOW': 1, 'PROVIDER_TIME_REQUIRED': 1,
        })

    def test_duplicate_archive_rows_are_deduplicated_and_conflicts_removed(self):
        raw = dict(recptnDt='2026-09-11 09:00:00', barvlDt='30', updnLine='내선',
                   bstatnNm='성수', btrainStt='일반')
        first = self.event('subway.arrival', 'same', 'PA', raw)
        duplicate = json.loads(json.dumps(first))
        conflict = json.loads(json.dumps(first))
        conflict['payload']['raw']['barvlDt'] = '60'
        document, report = build_observations([first, duplicate, conflict], dt.date(2026, 9, 11))
        self.assertEqual(document['observations'], [])
        self.assertEqual(report['rejectionCounts'], {'DUPLICATE_EVENT_ID': 1, 'EVENT_ID_CONFLICT': 2})


if __name__ == '__main__':
    unittest.main()
