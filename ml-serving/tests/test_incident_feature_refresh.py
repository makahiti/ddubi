import json
import sys
from datetime import UTC, datetime, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from incident_feature_refresh import IncidentFeatureRefresher

MASTER = "master-v1"
SHA = "a" * 64
MAPPING = "ml:mapping:bus-road:v1:master-v1:sha"
NOW = datetime(2026, 9, 18, 1, 0, tzinfo=UTC)


class FakeRedis:
    def __init__(self):
        self.values = {}
        self.rows = {}
        self.writes = []

    def get(self, key):
        return self.values.get(key)

    def setex(self, key, ttl, value):
        self.writes.append((key, ttl))
        self.values[key] = value

    def hscan_iter(self, key, count=1000):
        return iter(self.rows.get(key, {}).items())

    def hget(self, key, field):
        return self.rows.get(key, {}).get(field)

    def mget(self, keys):
        return [self.values.get(key) for key in keys]


def configured():
    client = FakeRedis()
    client.values[f"ml:mapping:bus-road:active:{MASTER}"] = MAPPING
    client.values[MAPPING + ":meta"] = json.dumps(
        {
            "masterVersion": MASTER,
            "trafficLinkMasterVersion": "links-v1",
            "crosswalkSha256": SHA,
            "rowCount": 2,
        }
    )
    client.rows[MAPPING] = {
        "BUS:1:A:B": json.dumps(
            {
                "segmentId": "BUS:1:A:B",
                "masterVersion": MASTER,
                "trafficLinkMasterVersion": "links-v1",
                "mappingStatus": "RESOLVED",
                "topisLinkNamespace": "SEOUL_ACCINFO_LINK",
                "topisLinkIds": ["L1"],
                "mappingAsOf": "2026-09-17T00:00:00+00:00",
                "roadPathValidFrom": "2026-09-01T00:00:00+00:00",
                "roadPathValidUntil": "2026-10-01T00:00:00+00:00",
            }
        ),
        "BUS:1:B:C": json.dumps(
            {
                "segmentId": "BUS:1:B:C",
                "masterVersion": MASTER,
                "trafficLinkMasterVersion": "links-v1",
                "mappingStatus": "UNMAPPED",
                "topisLinkIds": None,
            }
        ),
    }
    return client


def snapshot(links):
    return json.dumps(
        {
            "snapshotVersion": "traffic-incident-snapshot-v1",
            "completeSnapshot": True,
            "linkMasterAvailable": True,
            "linkNamespace": "SEOUL_ACCINFO_LINK",
            "linkMasterVersion": "links-v1",
            "collectedAt": (NOW - timedelta(minutes=1)).isoformat(),
            "validUntil": (NOW + timedelta(minutes=20)).isoformat(),
            "incidents": [
                {
                    "providerIncidentId": str(i),
                    "matchedLinkId": link,
                    "startedAt": (NOW - timedelta(minutes=2)).isoformat(),
                }
                for i, link in enumerate(links)
            ],
        }
    )


def test_new_and_removed_incident_refresh_same_segment():
    client = configured()
    client.values["traffic:latest:incident-snapshot:all-current"] = snapshot(["L1"])
    refresher = IncidentFeatureRefresher(client, MASTER, SHA, now=lambda: NOW)
    calls = []
    refresher.assembler.assemble = lambda request: calls.append(request)
    assert refresher.refresh_once() == {"status": "UPDATED", "updated": 1}
    assert calls[0]["segmentId"] == "BUS:1:A:B"
    assert refresher.refresh_once() == {"status": "UNCHANGED", "updated": 0}
    client.values["traffic:latest:incident-snapshot:all-current"] = snapshot([])
    assert refresher.refresh_once() == {"status": "UPDATED", "updated": 1}
    assert len(calls) == 2


def test_unapproved_or_stale_source_cannot_refresh():
    client = configured()
    client.values["traffic:latest:incident-snapshot:all-current"] = snapshot(["L1"])
    refresher = IncidentFeatureRefresher(client, MASTER, SHA, now=lambda: NOW)
    calls = []
    refresher.assembler.assemble = lambda request: calls.append(request)
    client.values[MAPPING + ":meta"] = json.dumps({"masterVersion": MASTER})
    assert refresher.refresh_once()["status"] == "MAPPING_NOT_VERIFIED"
    client.values.pop("traffic:latest:incident-snapshot:all-current")
    assert refresher.refresh_once()["status"] == "SNAPSHOT_UNAVAILABLE"
    assert not calls


def test_refresh_writes_current_feature_snapshot():
    client = configured()
    client.values["traffic:latest:incident-snapshot:all-current"] = snapshot(["L1"])
    result = IncidentFeatureRefresher(client, MASTER, SHA, now=lambda: NOW).refresh_once()
    assert result == {"status": "UPDATED", "updated": 1}
    key = "ml:feature:bus-segment:bus-segment-feature-v3:master-v1:BUS:1:A:B"
    feature = json.loads(client.get(key))
    assert feature["features"]["verifiedIncidentCount"] == 1
    assert feature["provenance"]["incident"]["linkMasterVersion"] == "links-v1"


def test_provisional_mapping_refresh_is_opt_in():
    client = configured()
    client.values.pop(f"ml:mapping:bus-road:active:{MASTER}")
    provisional = "ml:mapping:bus-road-provisional:v1:master-v1:sha"
    client.values[f"ml:mapping:bus-road-provisional:active:{MASTER}"] = provisional
    client.values[provisional + ":meta"] = json.dumps(
        {
            "masterVersion": MASTER,
            "trafficLinkMasterVersion": "links-v1",
            "crosswalkSha256": SHA,
            "rowCount": 1,
        }
    )
    client.rows[provisional] = {
        "BUS:1:A:B": json.dumps(
            {
                "segmentId": "BUS:1:A:B",
                "masterVersion": MASTER,
                "trafficLinkMasterVersion": "links-v1",
                "mappingStatus": "PROVISIONAL",
                "topisLinkNamespace": "SEOUL_ACCINFO_LINK",
                "topisLinkIds": ["L1"],
                "mappingAsOf": "2026-09-17T00:00:00+00:00",
                "roadPathValidFrom": "2026-09-01T00:00:00+00:00",
                "roadPathValidUntil": "2026-10-01T00:00:00+00:00",
            }
        )
    }
    client.values["traffic:latest:incident-snapshot:all-current"] = snapshot(["L1"])

    assert (
        IncidentFeatureRefresher(client, MASTER, SHA, now=lambda: NOW).refresh_once()["status"] == "MAPPING_UNAVAILABLE"
    )
    result = IncidentFeatureRefresher(client, MASTER, SHA, now=lambda: NOW, allow_provisional_road=True).refresh_once()
    assert result == {"status": "UPDATED", "updated": 1}
