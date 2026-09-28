"""작은 스트리밍 작업을 한 Spark 앱에서 감독하는 규칙. Spark 없이 시험한다."""

import ast
import unittest
from pathlib import Path
from unittest.mock import Mock

from common.light_runtime import LIGHT_JOBS, parse_jobs, supervise_all

ROOT = Path(__file__).resolve().parents[1]


def query(name, active=True, error=None):
    q = Mock()
    q.name = name
    q.isActive = active
    q.exception.return_value = error
    return q


class ParseJobsTests(unittest.TestCase):
    def test_default_list_is_accepted(self):
        self.assertEqual(parse_jobs(",".join(LIGHT_JOBS)), list(LIGHT_JOBS))

    def test_spaces_are_ignored(self):
        self.assertEqual(parse_jobs(" weather_latest , topis_enriched "), ["weather_latest", "topis_enriched"])

    def test_unknown_duplicate_and_empty_are_refused(self):
        # realtime_latest 는 무거워서 따로 둔다. 합치는 목록에 적으면 시작 전에 막는다.
        for raw in ("realtime_latest", "weather_latest,weather_latest", "", " , "):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                parse_jobs(raw)


class SuperviseAllTests(unittest.TestCase):
    def test_all_jobs_start_and_a_stopped_redis_stream_ends_the_app(self):
        weather, city = query("weather_latest"), query("city_realtime_silver_redis")
        waits = []

        def wait(first, seconds):
            waits.append(seconds)
            city.isActive = False  # 한 바퀴 뒤 도시 Redis 쿼리가 멈춘다
            city.exception.return_value = RuntimeError("kafka gone")

        with self.assertRaisesRegex(RuntimeError, "city_realtime_silver stopped: kafka gone"):
            supervise_all(
                [("weather_latest", lambda: weather, None), ("city_realtime_silver", lambda: city, None)],
                wait=wait,
            )
        self.assertEqual(waits, [30])
        weather.stop.assert_called_once()  # 끝날 때 남은 쿼리를 모두 멈춘다

    def test_silver_failure_is_logged_and_redis_keeps_running(self):
        redis, silver = query("topis_enriched"), query("topis_enriched_silver")
        rounds = []

        def wait(first, seconds):
            rounds.append(seconds)
            if len(rounds) == 1:
                silver.isActive = False
                silver.exception.return_value = RuntimeError("hdfs full")
            else:
                redis.isActive = False

        with self.assertLogs("common.light_runtime", "ERROR") as logs:
            with self.assertRaisesRegex(RuntimeError, "topis_enriched stopped"):
                supervise_all([("topis_enriched", lambda: redis, lambda: silver)], wait=wait)
        self.assertEqual(len(rounds), 2)  # Silver 가 멈춘 뒤에도 한 바퀴 더 돌았다
        self.assertTrue(any("hdfs full" in line for line in logs.output))

    def test_silver_start_failure_does_not_block_redis(self):
        redis = query("traffic_incident_silver")

        def wait(first, seconds):
            redis.isActive = False

        with self.assertLogs("common.light_runtime", "ERROR"):
            with self.assertRaises(RuntimeError):
                supervise_all(
                    [("traffic_incident_silver", lambda: redis, Mock(side_effect=RuntimeError("no hdfs")))],
                    wait=wait,
                )

    def test_redis_start_failure_stops_already_started_streams(self):
        weather = query("weather_latest")
        with self.assertRaisesRegex(RuntimeError, "boom"):
            supervise_all(
                [
                    ("weather_latest", lambda: weather, None),
                    ("city_realtime_silver", Mock(side_effect=RuntimeError("boom")), None),
                ],
                wait=lambda first, seconds: None,
            )
        weather.stop.assert_called_once()


class PrepareContractTests(unittest.TestCase):
    """합칠 작업 파일마다 prepare(spark, settings) 가 있어야 한다. Spark 를 불러오지 않고 소스로 확인한다."""

    def test_every_light_job_exposes_prepare(self):
        for job in LIGHT_JOBS:
            with self.subTest(job=job):
                tree = ast.parse((ROOT / "streaming" / f"{job}.py").read_text(encoding="utf-8"))
                functions = {node.name: node for node in tree.body if isinstance(node, ast.FunctionDef)}
                self.assertIn("prepare", functions)
                self.assertEqual([a.arg for a in functions["prepare"].args.args], ["spark", "settings"])
                self.assertIn("main", functions)


if __name__ == "__main__":
    unittest.main()
