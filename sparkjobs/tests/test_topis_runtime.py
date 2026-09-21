"""TOPIS HDFS 선택 저장이 Redis 생존에 영향을 주지 않는지 검증한다."""

import sys
import unittest
from pathlib import Path
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from common.topis_runtime import supervise


class TopisRuntimeTests(unittest.TestCase):
    def test_silver_start_failure_keeps_redis_running(self):
        redis = Mock()
        redis.awaitTermination.side_effect = [False, True]
        redis.exception.return_value = None
        redis.isActive = True
        with self.assertLogs("common.topis_runtime", "ERROR") as logs:
            with self.assertRaisesRegex(RuntimeError, "required TOPIS Redis stream stopped"):
                supervise(lambda: redis, Mock(side_effect=RuntimeError("HDFS unavailable")))
        self.assertEqual(redis.awaitTermination.call_count, 2)
        self.assertTrue(any("Redis remains active" in line for line in logs.output))

    def test_silver_runtime_failure_keeps_redis_running(self):
        redis = Mock()
        redis.awaitTermination.side_effect = [False, True]
        redis.exception.return_value = None
        redis.isActive = True
        silver = Mock()
        silver.isActive = False
        silver.exception.return_value = RuntimeError("disk full")
        with self.assertLogs("common.topis_runtime", "ERROR"):
            with self.assertRaisesRegex(RuntimeError, "required TOPIS Redis stream stopped"):
                supervise(lambda: redis, lambda: silver)
        self.assertEqual(redis.awaitTermination.call_count, 2)


if __name__ == "__main__":
    unittest.main()
