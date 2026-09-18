"""Spark integration for raw archive preservation and eventId conflict policy."""
import importlib.util
import sys
import unittest
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


@unittest.skipUnless(importlib.util.find_spec("pyspark"), "pyspark runtime not installed")
class BusSilverSparkTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        from pyspark.sql import SparkSession
        cls.spark = SparkSession.builder.master("local[1]").appName("test_bus_silver").config("spark.sql.shuffle.partitions", "1").getOrCreate()

    @classmethod
    def tearDownClass(cls):
        cls.spark.stop()

    def test_archive_raw_rows_conflicts_duplicates_and_missing_time(self):
        import json
        from batch.bus_silver import prepare
        good = dict(eventId="p1", eventType="bus.position", source="seoul.bus_location", collectedAt="2026-09-16T08:10:05+09:00", occurredAt="2026-09-16T08:10:00+09:00", payload=dict(providerNamespace="SEOUL_BUS", providerRouteId="00124", providerVehicleId="V123", timestampBasis="received", raw={"gpsX": "126.978", "gpsY": "37.566"}))
        conflict = dict(good, eventId="p2")
        changed = dict(conflict, payload=dict(conflict["payload"], raw={"gpsX": "127.1", "gpsY": "37.5"}))
        values = [json.dumps(x) for x in (good, good, conflict, changed)] + ["{broken", json.dumps(dict(eventType="weather.observation"))]
        archive = self.spark.createDataFrame([(v, 0, i, datetime(2026, 9, 16, 8, 10)) for i, v in enumerate(values)], "value string, kafka_partition int, kafka_offset long, kafka_timestamp timestamp")
        parsed, checked = prepare(archive)
        self.assertEqual(parsed.count(), 6)
        self.assertEqual(checked.filter("repeatIndex > 1").count(), 1)
        unique = checked.filter("repeatIndex = 1")
        self.assertEqual(unique.filter("status = 'ACCEPTED'").count(), 1)
        self.assertEqual(unique.filter("status = 'REJECTED'").count(), 3)
        row = unique.filter("status = 'ACCEPTED'").first()
        self.assertIsNone(row.observedAt)
        self.assertEqual(row.providerRouteId, "00124")

    def test_live_parquet_json_parity_retry_and_redis_failure(self):
        import json
        import tempfile
        from types import SimpleNamespace
        from unittest.mock import patch
        from common.kafka_source import parse_envelope
        from common.bus_stream_sink import prepare_live, save_batch, redis_rows
        from streaming.realtime_latest import transform
        event = dict(eventId="live1", eventType="bus.arrival", source="seoul.bus_arrival",
            collectedAt="2026-09-16T08:10:05+09:00", occurredAt="2026-09-16T08:10:00+09:00",
            payload=dict(providerRouteId="00124", providerStopId="S1", timestampBasis="provider",
                raw=dict(mkTm="2026-09-16 08:10:00", exps1="0", exps2="-5", traTime1="2")))
        raw = self.spark.createDataFrame([("k", json.dumps(event), 0, 1, datetime(2026, 9, 16, 8, 10))],
            "key string, value string, partition int, offset long, timestamp timestamp")
        events = parse_envelope(raw)
        silver = prepare_live(events, transform(events)).cache()
        settings = SimpleNamespace(redis_host="unused", redis_port=6379, redis_password=None)
        try:
            value = json.loads(redis_rows(silver).first().redisValue)
            self.assertEqual(value["firstArrivalSeconds"], 0)
            self.assertIsNone(value["secondArrivalSeconds"])
            self.assertEqual(value["observation"]["firstTravelTimeMinutes"], 2)
            from common.bus_normalizer import ALL_FIELDS
            source = silver.first().asDict()
            for field in ALL_FIELDS:
                self.assertEqual(value[field], source[field], field)
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory).as_uri()
                with patch("common.bus_stream_sink.write_latest_with_row_ttl") as writer:
                    self.assertEqual(save_batch(silver, 3, root, settings), 1)
                    save_batch(silver, 3, root, settings)
                    self.assertEqual(writer.call_count, 2)
                self.assertEqual(self.spark.read.parquet(root + "/batches/batch=3/data").count(), 1)
                with patch("common.bus_stream_sink.write_latest_with_row_ttl", side_effect=RuntimeError("redis down")):
                    with self.assertRaisesRegex(RuntimeError, "redis down"):
                        save_batch(silver, 4, root, settings)
                self.assertFalse((Path(directory) / "batches/batch=4/complete").exists())
        finally:
            silver.unpersist()

    def test_live_missing_source_time_keeps_null_and_latest_order(self):
        import json
        from common.bus_stream_sink import prepare_live, redis_rows
        from common.kafka_source import parse_envelope
        from streaming.realtime_latest import transform
        event = dict(eventId="missing-time", eventType="bus.position", source="gbis.location",
            collectedAt="2026-09-16T08:10:05+09:00", occurredAt="2026-09-16T08:10:05+09:00",
            payload=dict(providerNamespace="GBIS", providerRouteId="R", providerVehicleId="V", timestampBasis="received", raw={}))
        raw = self.spark.createDataFrame([("k", json.dumps(event), 0, 1, datetime(2026, 9, 16, 8, 10))],
            "key string, value string, partition int, offset long, timestamp timestamp")
        events = parse_envelope(raw)
        result = redis_rows(prepare_live(events, transform(events))).first()
        value = json.loads(result.redisValue)
        self.assertEqual(result.redisKey, "transit:latest:bus-position:GBIS:R:V")
        self.assertIsNone(value["observedAt"])
        self.assertIsNone(value["occurredAt"])
        self.assertIsNone(value["validUntil"])
        self.assertIsNotNone(value["_latestOrderAt"])


if __name__ == "__main__":
    unittest.main()
