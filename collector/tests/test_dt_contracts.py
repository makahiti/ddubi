import hashlib
import json
import sys
import unittest
from datetime import datetime
from pathlib import Path

import jsonschema

COLLECTOR = Path(__file__).resolve().parents[1]
ROOT = COLLECTOR.parent
sys.path.insert(0, str(COLLECTOR / "src"))
sys.path.insert(0, str(ROOT / "sparkjobs" / "tools"))

from events import build_event
from poll_status import build_poll_status
from main import run_cycle_with_status
from config import Settings
from sources.base import KST, PollBatch, Record, SourceSpec
from export_transit_coordinates import build_records


FIXTURE_DIR = ROOT / "docs" / "contracts" / "fixtures" / "conditional-departure-dt"
EVENT_SCHEMA_DIR = ROOT / "docs" / "contracts" / "events"


class DtContractTests(unittest.TestCase):
    def test_run_cycle_publishes_verified_empty_snapshot_status(self):
        now = datetime.fromisoformat("2026-09-16T08:00:00+09:00")
        spec = SourceSpec(
            "fixture.source", "transport.raw", "transport", 60,
            lambda *_: PollBatch([], complete_snapshot=True, page_count=1, scope_key="all"),
        )
        settings = Settings(source="fixture", kafka_bootstrap_servers="unused")

        class MemoryPublisher:
            def __init__(self):
                self.messages = []

            def publish(self, topic, key, value):
                self.messages.append((topic, key, value))

        publisher = MemoryPublisher()
        self.assertEqual(run_cycle_with_status(spec, settings, None, publisher), 0)
        self.assertEqual(len(publisher.messages), 1)
        topic, _, status = publisher.messages[0]
        self.assertEqual(topic, "source.poll-status")
        self.assertEqual(status["status"], "SUCCESS")
        self.assertTrue(status["completeSnapshot"])
        self.assertEqual(status["rowCount"], 0)

    def test_event_envelope_preserves_three_time_and_quality_meanings(self):
        occurred = datetime.fromisoformat("2026-09-16T08:10:00+09:00")
        collected = datetime.fromisoformat("2026-09-16T08:10:05+09:00")
        record = Record(
            key="r:s",
            event_type="bus.arrival",
            occurred_at=occurred,
            payload={
                "providerNamespace": "SEOUL_BUS",
                "providerRouteId": "r",
                "providerStopId": "s",
                "timestampBasis": "provider",
                "raw": {"mkTm": "2026-09-16 08:10:00", "exps1": "180"},
                "canonicalMapping": {
                    "state": "CONFIRMED", "scope": "ARRIVAL_TARGET_INCOMING_EDGE", "reason": "EXACT",
                    "masterVersion": "master-v1", "idPolicy": "canonical-adjacent-v2",
                    "crosswalkSha256": "a" * 64,
                },
            },
        )
        spec = SourceSpec("seoul.bus_arrival", "transport.raw", "transport", 60, lambda *_: [])
        event = build_event(spec, record, collected)
        self.assertEqual(event["sourceGeneratedAt"], occurred.isoformat())
        self.assertEqual(event["collectedAt"], collected.isoformat())
        self.assertEqual(event["timestampBasis"], "PROVIDER_GENERATED")
        self.assertEqual(event["providerIds"], {"providerRouteId": "r", "providerStopId": "s"})
        self.assertEqual(event["masterVersion"], "master-v1")
        schema = json.loads((EVENT_SCHEMA_DIR / "transport-event.schema.json").read_text(encoding="utf-8"))
        jsonschema.validate(event, schema, format_checker=jsonschema.FormatChecker())

    def test_previous_envelope_remains_schema_compatible(self):
        schema = json.loads((EVENT_SCHEMA_DIR / "transport-event.schema.json").read_text(encoding="utf-8"))
        legacy = {
            "eventId": "00000000-0000-4000-8000-000000000099",
            "eventType": "bus.arrival",
            "source": "seoul.bus_arrival",
            "occurredAt": "2026-09-16T08:10:00+09:00",
            "collectedAt": "2026-09-16T08:10:05+09:00",
            "payload": {"raw": {"exps1": "180"}},
        }
        jsonschema.validate(legacy, schema, format_checker=jsonschema.FormatChecker())

    def test_received_only_never_claims_source_generated_time(self):
        now = datetime(2026, 9, 16, 8, 10, tzinfo=KST)
        record = Record("x", "bus.position", now, {"timestampBasis": "received", "raw": {}})
        spec = SourceSpec("seoul.bus_location", "transport.raw", "transport", 60, lambda *_: [])
        event = build_event(spec, record, now)
        self.assertNotIn("sourceGeneratedAt", event)
        self.assertEqual(event["timestampBasis"], "RECEIVED_ONLY")

    def test_provider_occurrence_is_not_mislabeled_as_generated_time(self):
        now = datetime(2026, 9, 16, 8, 10, tzinfo=KST)
        record = Record("incident", "traffic.incident", now, {"timestampBasis": "provider", "raw": {}})
        spec = SourceSpec("seoul.traffic_incident", "city.raw", "city", 60, lambda *_: [])
        event = build_event(spec, record, now)
        self.assertEqual(event["timestampBasis"], "PROVIDER_OCCURRED")

    def test_poll_status_builder_and_fixtures_validate(self):
        start = datetime.fromisoformat("2026-09-16T08:00:00+09:00")
        event = build_poll_status(
            source="fixture", scope_key="all", started_at=start, finished_at=start,
            status="SUCCESS", row_count=0, complete_snapshot=True, page_count=1,
        )
        schema = json.loads((EVENT_SCHEMA_DIR / "source-poll-status.schema.json").read_text(encoding="utf-8"))
        checker = jsonschema.FormatChecker()
        jsonschema.validate(event, schema, format_checker=checker)
        for path in sorted(FIXTURE_DIR.glob("source-poll-status-*.json")):
            jsonschema.validate(json.loads(path.read_text(encoding="utf-8")), schema, format_checker=checker)

    def test_coordinate_fixture_and_semantics(self):
        schema = json.loads((ROOT / "docs" / "contracts" / "transit-coordinate-artifact.schema.json").read_text(encoding="utf-8"))
        artifact = json.loads((FIXTURE_DIR / "transit-coordinates.json").read_text(encoding="utf-8"))
        jsonschema.validate(artifact, schema, format_checker=jsonschema.FormatChecker())
        subway = [row for row in artifact["records"] if row["mode"] == "SUBWAY"]
        self.assertEqual(subway[0]["coordinateType"], "STATION_CENTER")
        self.assertEqual(subway[1]["mappingStatus"], "UNMAPPED")
        self.assertIsNone(subway[1]["longitude"])

    def test_coordinate_export_preserves_occurrence_and_missing_values(self):
        bus = {"version": "bus-v1", "routes": [{"namespace": "SEOUL_BUS", "routeId": "r", "stops": [
            {"namespace": "SEOUL_BUS", "stopId": "s", "sequence": 1, "direction": "UP", "longitude": 127.0, "latitude": 37.0},
            {"namespace": "SEOUL_BUS", "stopId": "s", "sequence": 9, "direction": "UP", "longitude": 127.0, "latitude": 37.0},
        ]}]}
        subway = {"dataVersion": "sub-v1", "lines": [{"subwayId": "2", "direction": "UP", "stations": [
            {"stationId": "x", "sequence": 1}
        ]}]}
        rows = build_records(bus, subway, {})
        bus_rows = [row for row in rows if row["mode"] == "BUS"]
        self.assertEqual([row["occurrence"] for row in bus_rows], [1, 2])
        subway_row = next(row for row in rows if row["mode"] == "SUBWAY")
        self.assertEqual(subway_row["coordinateType"], "UNKNOWN")
        self.assertEqual(subway_row["mappingStatus"], "UNMAPPED")

    def test_package_manifest_hashes(self):
        manifest = json.loads((FIXTURE_DIR / "package-manifest.json").read_text(encoding="utf-8"))
        for item in manifest["files"]:
            payload = (FIXTURE_DIR / item["path"]).read_bytes()
            self.assertEqual(hashlib.sha256(payload).hexdigest(), item["sha256"])


if __name__ == "__main__":
    unittest.main()
