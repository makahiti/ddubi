"""Publication ledger and real Publisher callbacks without a Kafka broker."""

import importlib.util
import json
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from notice_dedup import NoticeDedup, publish_notices


def load_publisher():
    spec = importlib.util.spec_from_file_location(
        "notice_test_publisher", Path(__file__).resolve().parents[1] / "src/publisher.py"
    )
    module = importlib.util.module_from_spec(spec)
    stub = types.ModuleType("confluent_kafka")
    stub.KafkaError = stub.Message = stub.Producer = object
    with patch.dict(sys.modules, {"confluent_kafka": stub}):
        spec.loader.exec_module(module)
    return module.Publisher


class Producer:
    def __init__(self):
        self.queue = []
        self.values = []

    def produce(self, **kwargs):
        self.queue.append(kwargs)
        self.values.append(json.loads(kwargs["value"]))

    def poll(self, timeout):
        pass

    def deliver(self, error=None):
        queue, self.queue = self.queue, []
        for item in queue:
            msg = types.SimpleNamespace(topic=lambda item=item: item["topic"], key=lambda item=item: item["key"])
            item["on_delivery"](error, msg)


class NoticeDedupTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "ledger.sqlite3"
        self.gate = NoticeDedup(self.path, "city.raw")
        self.producer = Producer()
        self.publisher = load_publisher()("unused", "test", producer=self.producer)

    def record(self, identifier="10", title="집회 안내", **extra):
        return types.SimpleNamespace(
            key=identifier or "fallback",
            payload={
                "providerNoticeId": identifier,
                "observedAt": extra.pop("observedAt", "now"),
                "raw": dict(
                    noticeId=identifier, title=title, contentHtml="<p>원본</p>", updatedAt="2026-09-16", **extra
                ),
            },
        )

    def publish(self, records):
        return publish_notices(records, self.gate, self.publisher, lambda record: {"payload": record.payload})

    def test_first_send_unchanged_skip_new_and_changed_send(self):
        self.assertEqual(self.publish([self.record()])["new"], 1)
        self.producer.deliver()
        stats = self.publish([self.record(observedAt="later"), self.record("11"), self.record("12")])
        self.assertEqual((stats["unchanged"], stats["new"]), (1, 2))
        self.producer.deliver()
        self.assertEqual(self.publish([self.record(title="수정된 집회 안내")])["changed"], 1)
        self.producer.deliver()
        self.assertEqual(self.publish([self.record(title="수정된 집회 안내")])["unchanged"], 1)

    def test_restart_and_topic_isolation(self):
        self.publish([self.record()])
        self.producer.deliver()
        self.gate = NoticeDedup(self.path, "city.raw")
        self.assertEqual(self.publish([self.record()])["unchanged"], 1)
        self.assertEqual(NoticeDedup(self.path, "city.test").reserve(self.record())[1], "new")

    def test_ack_required_pending_suppressed_failure_retried(self):
        self.publish([self.record()])
        self.assertEqual(self.publish([self.record()])["pending"], 1)
        with self.gate.connect() as db:
            self.assertEqual(db.execute("SELECT count(*) FROM topis_publications").fetchone()[0], 0)
        self.producer.deliver(error="failure")
        self.assertEqual(self.publisher.failed, 1)
        self.assertEqual(self.publish([self.record()])["new"], 1)
        self.producer.deliver()
        self.assertEqual(self.publisher.sent, 1)
        self.assertEqual(self.publish([self.record()])["unchanged"], 1)

    def test_changed_failure_keeps_previous_success(self):
        self.publish([self.record()])
        self.producer.deliver()
        self.publish([self.record(title="changed")])
        self.producer.deliver("failure")
        self.assertEqual(self.publish([self.record(title="changed")])["changed"], 1)

    def test_queue_or_event_build_failure_releases_reservation(self):
        with patch.object(self.publisher, "publish", side_effect=BufferError("full")):
            with self.assertRaises(BufferError):
                self.publish([self.record()])
        self.assertFalse(self.gate.pending)
        with self.assertRaises(ValueError):
            publish_notices(
                [self.record()],
                self.gate,
                self.publisher,
                lambda record: (_ for _ in ()).throw(ValueError("invalid event")),
            )
        self.assertFalse(self.gate.pending)
        self.assertEqual(self.publish([self.record()])["new"], 1)

    def test_original_payload_preserved_and_missing_id_fallback(self):
        record = self.record(identifier="")
        snapshot = json.loads(json.dumps(record.payload))
        self.assertEqual(self.publish([record])["missingId"], 1)
        self.assertEqual(self.producer.values[0]["payload"], snapshot)
        self.producer.deliver()
        self.assertEqual(self.publish([self.record(identifier="")])["unchanged"], 1)
        self.assertEqual(self.publish([self.record(identifier="", title="changed")])["new"], 1)

    def test_thousand_unchanged_produce_zero_second_cycle(self):
        records = [self.record(str(i)) for i in range(1000)]
        self.assertEqual(self.publish(records)["new"], 1000)
        self.producer.deliver()
        self.assertEqual(self.publish(records)["unchanged"], 1000)
        self.assertEqual(len(self.producer.values), 1000)


if __name__ == "__main__":
    unittest.main()
