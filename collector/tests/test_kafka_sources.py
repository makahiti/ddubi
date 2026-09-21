"""API fixture → SOURCE → main.run_cycle → Publisher → JSON 계약 검증."""

import json
import os
import sqlite3
import sys
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import httpx
import jsonschema

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from config import Settings
from main import load_source, run_cycle
from publisher import Publisher
from request_budget import BudgetExceeded, RequestBudget
from sources.kafka_adapter import POLICIES, _limit_bus_targets, to_record
from test_bus_arrival import SAMPLE_JSON as BUS_ARRIVAL
from test_bus_location import SAMPLE_JSON as BUS_LOCATION
from test_city_realtime import SAMPLE_RESPONSE as CITY
from test_subway_location import SAMPLE_RESPONSE as SUBWAY_LOCATION
from test_topis_notice import SECOND_PAGE_XML as NOTICE
from test_traffic_incident import SECOND_PAGE_XML as INCIDENT

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = {
    "bus_arrival": BUS_ARRIVAL,
    "bus_location": BUS_LOCATION,
    "subway_location": SUBWAY_LOCATION,
    "city_realtime": CITY,
    "subway_arrival": {
        "errorMessage": {"code": "INFO-000", "total": 1},
        "realtimeArrivalList": [
            {"subwayId": "1002", "statnId": "S1", "btrainNo": "T1", "recptnDt": "2026-09-09 10:00:00"}
        ],
    },
    "subway_alert": {
        "getNtceList": {
            "RESULT": {"CODE": "INFO-000"},
            "list_total_count": 1,
            "row": [{"noftTtl": "알림", "noftOcrnDt": "2026-09-09T10:00:00"}],
        }
    },
    "topis_notice": NOTICE,
    "traffic_incident": INCIDENT,
}


def response_for(name):
    data = FIXTURES[name]
    return httpx.Response(200, text=data) if isinstance(data, str) else httpx.Response(200, json=data)


def settings_for(name, path):
    return Settings(
        source=name,
        kafka_bootstrap_servers="unused",
        api_keys={POLICIES[name][0]: "test-secret"},
        extra={
            "API_BUDGET_PATH": str(path),
            "TOPIS_NOTICE_DEDUP_PATH": str(Path(path).with_name("topis-notice.db")),
            "BUS_ROUTE_IDS": "100100118",
            "SUBWAY_LINES": "2호선",
            "SUBWAY_SKIP_ENABLED": "false",
            "CITY_AREA_IDENTIFIERS": "POI009",
        },
    )


class MemoryProducer:
    def __init__(self):
        self.messages = []

    def produce(self, **kwargs):
        self.messages.append(kwargs)
        kwargs["on_delivery"](None, None)

    def poll(self, timeout):
        pass

    def flush(self, timeout):
        return 0


class KafkaSourceTest(unittest.TestCase):
    def test_all_sources_publish_schema_valid_json_and_cooldown_survives_restart(self):
        for name in POLICIES:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "budget.db"
                settings = settings_for(name, path)
                source = load_source(name)
                producer = MemoryProducer()
                publisher = Publisher("unused", "test", producer=producer)
                with httpx.Client(transport=httpx.MockTransport(lambda req, name=name: response_for(name))) as client:
                    count = run_cycle(source, settings, client, publisher)
                    self.assertGreater(count, 0)
                    self.assertEqual(count, publisher.sent)
                    self.assertEqual(run_cycle(load_source(name), settings_for(name, path), client, publisher), 0)
                    self.assertEqual(client.event_hooks["request"], [])
                schema = json.loads(
                    (ROOT / f"docs/contracts/events/{source.schema}-event.schema.json").read_text(encoding="utf-8")
                )
                for message in producer.messages:
                    event = json.loads(message["value"])
                    jsonschema.validate(event, schema, format_checker=jsonschema.FormatChecker())
                    self.assertEqual(message["topic"], source.topic)
                    self.assertEqual(event["eventType"], POLICIES[name][1])
                    self.assertIn("raw", event["payload"])
                    self.assertNotIn("test-secret", message["value"].decode())

    def test_incident_link_namespace_keeps_existing_partition_key(self):
        # 출처를 추가해도 기존 사고 ID:링크 ID Kafka key와 원본 보존 계약은 유지한다.
        raw = dict(incidentId="i1", linkId="l1", occurredDate="20260911", occurredTime="090000")
        record = to_record("traffic_incident", raw)
        self.assertEqual(record.key, "i1:l1")
        self.assertEqual(record.payload["providerLinkNamespace"], "SEOUL_ACCINFO_LINK")
        self.assertEqual(record.payload["raw"], raw)
        self.assertIsNone(record.segment_ids)

    def test_budget_shared_key_concurrent_atomic_limit_and_restart(self):
        with tempfile.TemporaryDirectory() as directory:
            path = str(Path(directory) / "budget.db")

            def reserve(i):
                try:
                    # 각 작업이 별도 컨테이너처럼 독립 연결·초기화를 수행한다.
                    RequestBudget(path, "secret", limit=90).before_request(None)
                    return 1
                except BudgetExceeded:
                    return 0

            with ThreadPoolExecutor(max_workers=9) as pool:
                self.assertEqual(sum(pool.map(reserve, range(180))), 90)
            with self.assertRaises(BudgetExceeded):
                RequestBudget(path, "secret", limit=90).before_request(None)
            db = sqlite3.connect(path)
            try:
                self.assertEqual(db.execute("PRAGMA journal_mode").fetchone()[0].lower(), "wal")
            finally:
                db.close()
            self.assertNotIn(b"secret", Path(path).read_bytes())

    def test_rolling_window_does_not_reset_at_midnight(self):
        now = [86399.0]
        with tempfile.TemporaryDirectory() as directory:
            budget = RequestBudget(str(Path(directory) / "budget.db"), "key", 1, clock=lambda: now[0])
            budget.before_request(None)
            now[0] += 2
            with self.assertRaises(BudgetExceeded):
                budget.before_request(None)
            now[0] += 86400
            budget.before_request(None)

    def test_encoded_keys_share_budget(self):
        with tempfile.TemporaryDirectory() as directory:
            path = str(Path(directory) / "budget.db")
            RequestBudget(path, "a%2Bb", 1).before_request(None)
            with self.assertRaises(BudgetExceeded):
                RequestBudget(path, "a+b", 1).before_request(None)

    def test_bus_targets_are_limited_to_100(self):
        targets = [str(index) for index in range(723)]
        self.assertEqual(len(_limit_bus_targets(targets)), 100)
        self.assertEqual(len(_limit_bus_targets(targets, "25")), 25)
        with self.assertRaises(ValueError):
            _limit_bus_targets(targets, "101")

    def test_redirect_and_failed_request_both_count(self):
        with tempfile.TemporaryDirectory() as directory:
            budget = RequestBudget(str(Path(directory) / "budget.db"), "key", 2)

            def handler(req):
                if req.url.path == "/start":
                    return httpx.Response(307, headers={"Location": "/end"})
                raise httpx.ConnectError("failure", request=req)

            with httpx.Client(
                transport=httpx.MockTransport(handler), event_hooks={"request": [budget.before_request]}
            ) as client:
                with self.assertRaises(httpx.ConnectError):
                    client.get("https://test/start", follow_redirects=True)
                with self.assertRaises(BudgetExceeded):
                    client.get("https://test/next")

    def test_rejects_cycle_before_partial_full_route_fetch(self):
        with tempfile.TemporaryDirectory() as directory:
            budget = RequestBudget(str(Path(directory) / "budget.db"), "key", 900)
            for _ in range(200):
                budget.before_request(None)
            with self.assertRaises(BudgetExceeded):
                budget.claim_cycle("bus_arrival", 86400, 723)

    def test_bus_business_error_not_published_and_secret_not_leaked(self):
        with tempfile.TemporaryDirectory() as directory:
            settings = settings_for("bus_arrival", Path(directory) / "budget.db")
            producer = MemoryProducer()
            with httpx.Client(
                transport=httpx.MockTransport(lambda req: httpx.Response(200, json={"msgHeader": {"headerCd": "7"}}))
            ) as client:
                with self.assertRaises(RuntimeError) as error:
                    run_cycle(
                        load_source("bus_arrival"), settings, client, Publisher("unused", "test", producer=producer)
                    )
                self.assertNotIn("test-secret", str(error.exception))
                self.assertEqual(client.event_hooks["request"], [])
            self.assertEqual(producer.messages, [])

    def test_notice_content_update_preserves_identity_changes_hash(self):
        first = to_record("topis_notice", {"noticeId": "1", "contentText": "before"})
        second = to_record("topis_notice", {"noticeId": "1", "contentText": "after"})
        self.assertEqual(first.key, second.key)
        self.assertNotEqual(first.payload["contentHash"], second.payload["contentHash"])

    def test_default_shared_key_planned_counts(self):
        self.assertEqual(2 * (86400 // POLICIES["bus_arrival"][2]) * 100, 600)
        self.assertEqual(86400 // POLICIES["subway_arrival"][2] * 2 + 86400 // POLICIES["subway_location"][2] * 16, 864)
        self.assertEqual(86400 // POLICIES["city_realtime"][2] * 121 + 3 * (86400 // 900), 772)


@unittest.skipUnless(os.getenv("KAFKA_PROTOCOL_TEST") == "1", "requires local TCP sockets")
class KafkaProtocolTest(unittest.TestCase):
    def test_produce_consume_all_sources_through_librdkafka_mock_broker(self):
        from confluent_kafka import Consumer, Producer

        # librdkafka가 제공하는 임시 Kafka 프로토콜 브로커. 운영 서버 아님.
        broker = Producer({"test.mock.num.brokers": 1})
        metadata = broker.list_topics(timeout=10)
        address = ",".join(f"{b.host}:{b.port}" for b in metadata.brokers.values())
        publisher = Publisher(address, "collector-protocol-test")
        consumer = Consumer(
            {"bootstrap.servers": address, "group.id": "test-collector", "auto.offset.reset": "earliest"}
        )
        expected = 0
        try:
            with tempfile.TemporaryDirectory() as directory:
                for name in POLICIES:
                    with httpx.Client(transport=httpx.MockTransport(lambda req, n=name: response_for(n))) as client:
                        expected += run_cycle(
                            load_source(name), settings_for(name, Path(directory) / f"{name}.db"), client, publisher
                        )
                self.assertEqual(publisher.flush(15), 0)
                self.assertEqual(publisher.failed, 0)
                self.assertEqual(publisher.sent, expected)
                consumer.subscribe(["transport.raw", "city.raw"])
                received = []
                import time

                deadline = time.monotonic() + 25
                while len(received) < expected and time.monotonic() < deadline:
                    message = consumer.poll(1)
                    if message is not None and not message.error():
                        received.append(json.loads(message.value()))
                self.assertEqual(len(received), expected)
                self.assertEqual({r["eventType"] for r in received}, {p[1] for p in POLICIES.values()})
        finally:
            consumer.close()


if __name__ == "__main__":
    unittest.main()
