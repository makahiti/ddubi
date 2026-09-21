import sys
import unittest
from pathlib import Path
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from common.city_realtime_runtime import supervise


class CityRuntimeTests(unittest.TestCase):
    @staticmethod
    def query(waits):
        query = Mock()
        query.awaitTermination.side_effect = waits
        query.exception.return_value = None
        query.isActive = True
        return query

    def test_hdfs_start_failure_does_not_stop_redis(self):
        redis = self.query([False, True])
        with self.assertLogs("common.city_realtime_runtime", "ERROR"):
            with self.assertRaisesRegex(RuntimeError, "required city Redis stream stopped"):
                supervise(lambda: redis, Mock(side_effect=RuntimeError("HDFS down")))
        self.assertEqual(redis.awaitTermination.call_count, 2)

    def test_hdfs_runtime_failure_does_not_stop_redis(self):
        redis, silver = self.query([False, True]), Mock()
        silver.isActive = False
        silver.exception.return_value = RuntimeError("disk full")
        with self.assertLogs("common.city_realtime_runtime", "ERROR"):
            with self.assertRaisesRegex(RuntimeError, "required city Redis stream stopped"):
                supervise(lambda: redis, lambda: silver)
        self.assertEqual(redis.awaitTermination.call_count, 2)


if __name__ == "__main__":
    unittest.main()
