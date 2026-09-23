"""Complete-poll incident feature snapshot guards."""

from common.traffic_incident_enrichment import VerifiedLinkMaster
from common.traffic_incident_features import build_incident_snapshot


def _master():
    row = {
        "linkId": "L1",
        "geometry": [[127.0, 37.5], [127.001, 37.5]],
        "roadName": None,
        "districtName": None,
        "startNodeId": None,
        "endNodeId": None,
    }
    return VerifiedLinkMaster(
        {"version": "link-v1", "validFrom": "2026-09-01T00:00:00+09:00", "validUntil": "2026-10-01T00:00:00+09:00"},
        {"L1": row},
    )


def _event(items, **changes):
    value = {
        "eventType": "traffic.incident.snapshot",
        "eventId": "poll-1",
        "source": "seoul.traffic_incident",
        "collectedAt": "2026-09-18T10:00:00+09:00",
        "payload": {"items": items, "rowCount": len(items), "completeSnapshot": True},
    }
    value.update(changes)
    return value


def _row(incident_id, link_id):
    return {"incidentId": incident_id, "linkId": link_id, "occurredDate": "20260918", "occurredTime": "095000"}


def test_complete_poll_deduplicates_and_tracks_unmatched_rows():
    snapshot = build_incident_snapshot(_event([_row("A", "L1"), _row("A", "L1"), _row("B", "OTHER")]), _master())
    assert snapshot["sourceRowCount"] == 3
    assert snapshot["verifiedIncidentCount"] == 1
    assert snapshot["unmatchedIncidentCount"] == 1
    assert snapshot["incidents"][0]["matchedLinkId"] == "L1"


def test_partial_or_malformed_poll_cannot_create_zero_snapshot():
    assert (
        build_incident_snapshot(_event([], payload={"items": [], "rowCount": 0, "completeSnapshot": False}), _master())
        is None
    )
    assert (
        build_incident_snapshot(_event([], payload={"items": [], "rowCount": 1, "completeSnapshot": True}), _master())
        is None
    )
    assert build_incident_snapshot(_event([_row("", "L1")]), _master()) is None


def test_verified_snapshot_carries_text_features_for_segment_join():
    class Extractor:
        def enrich_many(self, rows):
            return [
                {
                    **row,
                    "aiExtractionStatus": "VALID",
                    "aiIncidentKind": "ACCIDENT",
                    "aiExtractedAt": "2026-09-18T09:59:00+09:00",
                }
                for row in rows
            ]

    snapshot = build_incident_snapshot(_event([_row("A", "L1")]), _master(), extractor=Extractor())
    incident = snapshot["incidents"][0]
    assert incident["matchedLinkId"] == "L1"
    assert incident["textFeatures"]["aiIncidentKind"] == "ACCIDENT"


def test_provisional_snapshot_keeps_provider_link_only_when_opted_in():
    from common.traffic_incident_enrichment import VerifiedLinkMaster

    event = _event([_row("A", "L1")])
    unavailable = VerifiedLinkMaster.unavailable()
    regular = build_incident_snapshot(event, unavailable)
    assert regular["linkMasterAvailable"] is False
    assert regular["incidents"] == []

    provisional = build_incident_snapshot(
        event,
        unavailable,
        provisional_link_version="provisional-accinfo-input-v1",
    )
    assert provisional["linkMasterAvailable"] is True
    assert provisional["linkMappingStatus"] == "PROVISIONAL"
    assert provisional["incidents"][0]["matchedLinkId"] == "L1"
