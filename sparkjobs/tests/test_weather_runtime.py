"""Optional Silver isolation and required Redis lifecycle, without Spark."""
import sys
import unittest
from pathlib import Path
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from common.weather_runtime import silver_configuration, supervise


class WeatherRuntimeTests(unittest.TestCase):
    def redis(self, waits):
        query = Mock()
        query.name = "weather_latest"
        query.isActive = True
        query.awaitTermination.side_effect = waits
        query.exception.return_value = None
        query.lastProgress = None
        return query

    def silver(self, active=True, error=None):
        query = Mock()
        query.name = "weather_latest_silver"
        query.isActive = active
        query.exception.return_value = error
        query.lastProgress = None
        return query

    def test_required_missing_or_blank_fails(self):
        for env in ({"WEATHER_REQUIRE_SILVER": "true"}, {"WEATHER_REQUIRE_SILVER": "true", "SILVER_OUTPUT_URI": "  "}):
            with self.assertRaisesRegex(RuntimeError, "SILVER_OUTPUT_URI is required"):
                silver_configuration(env)

    def test_optional_default_is_disabled(self):
        self.assertIsNone(silver_configuration({}))
        self.assertIsNone(silver_configuration({"WEATHER_REQUIRE_SILVER": "false"}))

    def test_target_and_invalid_uri(self):
        self.assertEqual(silver_configuration({"SILVER_OUTPUT_URI": "hdfs://hdfs-namenode:9000/ddubi/silver/"}), "hdfs://hdfs-namenode:9000/ddubi/silver/weather")
        for value in ("/ddubi/silver", "hdfs:///ddubi/silver", "https://example.com/a", "hdfs://host/x?bad=true"):
            with self.assertLogs("common.weather_runtime", "ERROR"):
                self.assertIsNone(silver_configuration({"SILVER_OUTPUT_URI": value}))
            with self.assertRaises(ValueError):
                silver_configuration({"SILVER_OUTPUT_URI": value, "WEATHER_REQUIRE_SILVER": "true"})

    def test_invalid_required_flag(self):
        with self.assertRaisesRegex(ValueError, "must be true or false"):
            silver_configuration({"WEATHER_REQUIRE_SILVER": "yes"})

    def test_silver_start_failure_keeps_redis_until_redis_stops(self):
        redis = self.redis([False, True])
        with self.assertLogs("common.weather_runtime", "ERROR") as logs:
            with self.assertRaisesRegex(RuntimeError, "Redis stream stopped"):
                supervise(lambda: redis, Mock(side_effect=RuntimeError("HDFS permission denied")))
        self.assertTrue(any("Silver failed to start; Redis remains active" in line for line in logs.output))
        self.assertEqual(redis.awaitTermination.call_count, 2)

    def test_silver_runtime_failure_keeps_redis_until_redis_stops(self):
        redis = self.redis([False, False, True])
        silver = self.silver(False, RuntimeError("disk full"))
        with self.assertLogs("common.weather_runtime", "ERROR") as logs:
            with self.assertRaisesRegex(RuntimeError, "Redis stream stopped"):
                supervise(lambda: redis, lambda: silver)
        self.assertTrue(any("Silver stream failed; Redis remains active: disk full" in line for line in logs.output))
        self.assertEqual(redis.awaitTermination.call_count, 3)

    def test_required_redis_runtime_failure_stops_active_silver(self):
        redis = self.redis([True])
        redis.exception.return_value = RuntimeError("Redis unavailable")
        silver = self.silver()
        with self.assertLogs("common.weather_runtime", "ERROR"):
            with self.assertRaisesRegex(RuntimeError, "required Redis stream failed: Redis unavailable"):
                supervise(lambda: redis, lambda: silver)
        silver.stop.assert_called_once()

    def test_silver_monitoring_error_does_not_stop_redis(self):
        redis = self.redis([False, False, True])
        silver = self.silver()
        type(silver).isActive = property(lambda _self: (_ for _ in ()).throw(RuntimeError("HDFS client error")))
        with self.assertLogs("common.weather_runtime", "ERROR") as logs:
            with self.assertRaisesRegex(RuntimeError, "Redis stream stopped"):
                supervise(lambda: redis, lambda: silver)
        self.assertTrue(any("Could not inspect Weather Silver stream; Redis remains active" in line for line in logs.output))
        self.assertEqual(redis.awaitTermination.call_count, 3)

    def test_redis_start_failure_does_not_start_silver(self):
        silver_start = Mock()
        with self.assertLogs("common.weather_runtime", "ERROR"):
            with self.assertRaisesRegex(RuntimeError, "Kafka unavailable"):
                supervise(Mock(side_effect=RuntimeError("Kafka unavailable")), silver_start)
        silver_start.assert_not_called()

    def test_progress_is_logged(self):
        redis = self.redis([False, True])
        redis.lastProgress = {"batchId": 8, "numInputRows": 14, "durationMs": {"triggerExecution": 150}}
        with self.assertLogs("common.weather_runtime", "INFO") as logs:
            with self.assertRaisesRegex(RuntimeError, "stopped unexpectedly"):
                supervise(lambda: redis)
        self.assertTrue(any("query=weather_latest batch=8 inputRows=14" in line for line in logs.output))


if __name__ == "__main__":
    unittest.main()
