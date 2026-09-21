import sys
import unittest
from pathlib import Path
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from common.traffic_incident_runtime import supervise


class TrafficIncidentRuntimeTests(unittest.TestCase):
    def query(self, waits):
        query = Mock()
        query.awaitTermination.side_effect = waits
        query.exception.return_value = None
        query.isActive = True
        return query

    def test_silver_start_failure_keeps_redis(self):
        redis = self.query([False, True])
        with self.assertLogs("common.traffic_incident_runtime", "ERROR"):
            with self.assertRaisesRegex(RuntimeError, "required traffic incident Redis stream stopped"):
                supervise(lambda: redis, Mock(side_effect=RuntimeError("HDFS down")))
        self.assertEqual(redis.awaitTermination.call_count, 2)

    def test_silver_runtime_failure_keeps_redis(self):
        redis, silver = self.query([False, True]), Mock()
        silver.isActive = False
        silver.exception.return_value = RuntimeError("disk full")
        with self.assertLogs("common.traffic_incident_runtime", "ERROR"):
            with self.assertRaisesRegex(RuntimeError, "required traffic incident Redis stream stopped"):
                supervise(lambda: redis, lambda: silver)
        self.assertEqual(redis.awaitTermination.call_count, 2)


if __name__ == "__main__":
    unittest.main()
