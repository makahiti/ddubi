import datetime as dt
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('readiness', Path(__file__).parents[1] / 'recommendation-readiness.py')
readiness = importlib.util.module_from_spec(spec)
spec.loader.exec_module(readiness)
NOW = dt.datetime(2026, 9, 17, tzinfo=dt.timezone.utc)


class ReadinessTest(unittest.TestCase):
    def data(self):
        return {'schemaVersion': 'schedule-evaluation-v2', 'state': 'READY',
                'decision': 'KEEP_DEPARTURE', 'computedAt': NOW.isoformat(),
                'validUntil': (NOW + dt.timedelta(seconds=60)).isoformat(),
                'estimatedArrivalAt': (NOW + dt.timedelta(hours=1)).isoformat(),
                'quality': {'preview': False, 'productionEligible': True,
                            'costCoverage': 'COMPLETE'}}

    def test_valid_provider_only_is_not_realtime(self):
        result = readiness.evaluate(self.data(), NOW)
        self.assertTrue(result['evaluationReady'])
        self.assertFalse(result['realtimeApplied'])

    def test_preview_blocked(self):
        data = self.data()
        data['quality']['preview'] = True
        self.assertFalse(readiness.evaluate(data, NOW)['evaluationReady'])

    def test_expiry_boundary(self):
        data = self.data()
        data['validUntil'] = NOW.isoformat()
        self.assertIn('EVIDENCE_EXPIRED', readiness.evaluate(data, NOW)['failures'])

    def test_no_evidence_is_not_ready(self):
        self.assertFalse(readiness.evaluate({}, NOW)['evaluationReady'])

    def test_incomplete_not_ready(self):
        data = self.data()
        data['search'] = {'complete': False}
        self.assertFalse(readiness.evaluate(data, NOW)['evaluationReady'])

    def test_naive_timestamp_rejected(self):
        with self.assertRaises(ValueError):
            readiness.instant('2026-09-17T00:00:00')

    def test_empty_timetable_rejected(self):
        with self.assertRaises(ValueError):
            readiness.timetable_header({}, NOW)


if __name__ == '__main__':
    unittest.main()
