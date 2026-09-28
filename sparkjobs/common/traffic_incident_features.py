"""Complete AccInfo poll -> one verified, time bounded Redis feature source."""

from datetime import datetime, timedelta

from common.traffic_incident_ai import enrich_rows
from common.traffic_incident_enrichment import LINK_NAMESPACE, normalize_incident

SNAPSHOT_KEY = "traffic:latest:incident-snapshot:all-current"
SNAPSHOT_SECONDS = 1800


def _time(value):
    return datetime.fromisoformat(str(value).replace("Z", "+00:00"))


def build_incident_snapshot(
    event,
    master,
    stale_seconds=2700,
    extractor=None,
    direct_tolerance=200,
    nearby_max=100,
    provisional_link_version=None,
):
    payload = event.get("payload") or {}
    items = payload.get("items")
    observed_at = event.get("collectedAt")
    if (
        event.get("eventType") != "traffic.incident.snapshot"
        or payload.get("completeSnapshot") is not True
        or not isinstance(items, list)
        or payload.get("rowCount") != len(items)
        or not observed_at
    ):
        return None
    try:
        collected = _time(observed_at)
    except (ValueError, TypeError):
        return None
    if collected.tzinfo is None:
        return None
    latest = {}
    rejected_count = 0
    for raw in items:
        if not isinstance(raw, dict):
            return None
        normalized = normalize_incident(
            {
                "eventId": event.get("eventId"),
                "source": event.get("source"),
                "collectedAt": observed_at,
                "payload": {"raw": raw, "providerLinkNamespace": LINK_NAMESPACE},
            },
            master,
            stale_seconds=stale_seconds,
            direct_tolerance_meters=direct_tolerance,
            nearby_max_meters=nearby_max,
        )
        if (
            provisional_link_version
            and not master.available
            and normalized.get("processingStatus") != "REJECTED"
            and normalized.get("inputLinkNamespace") == LINK_NAMESPACE
            and normalized.get("inputLinkId")
        ):
            normalized["matchedLinkId"] = normalized["inputLinkId"]
            normalized["locationMatchType"] = "PROVISIONAL_INPUT_LINK"
            normalized["linkMasterStatus"] = "PROVISIONAL"
            normalized["linkMasterVersion"] = provisional_link_version
        incident_id = normalized.get("providerIncidentId")
        if not incident_id:
            return None
        if normalized.get("processingStatus") != "REJECTED":
            latest[incident_id] = normalized
        else:
            rejected_count += 1
    enriched = enrich_rows(latest.values(), extractor)
    verified = [
        {
            "providerIncidentId": row["providerIncidentId"],
            "matchedLinkId": row["matchedLinkId"],
            "startedAt": row["startedAt"],
            "expectedClearAt": row["expectedClearAt"],
            "locationMatchType": row["locationMatchType"],
            "textFeatures": {k: v for k, v in row.items() if k.startswith("ai")},
        }
        for row in enriched
        if row.get("matchedLinkId") and row.get("startedAt")
    ]
    return {
        "snapshotVersion": "traffic-incident-snapshot-v1",
        "eventId": event.get("eventId"),
        "collectedAt": observed_at,
        "validUntil": (collected + timedelta(seconds=SNAPSHOT_SECONDS)).isoformat(),
        "completeSnapshot": True,
        "sourceRowCount": len(items),
        "verifiedIncidentCount": len(verified),
        "unmatchedIncidentCount": len(latest) - len(verified) + rejected_count,
        "linkMasterAvailable": bool(
            master.available and master.applicable(observed_at) or provisional_link_version and not master.available
        ),
        "linkMasterVersion": master.metadata.get("version") or provisional_link_version,
        "linkMappingStatus": "PROVISIONAL" if provisional_link_version and not master.available else "RESOLVED",
        "linkNamespace": LINK_NAMESPACE,
        "incidents": verified,
    }
