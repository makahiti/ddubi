"""Traffic incident Silver normalization with verified-link-only spatial matching."""

from __future__ import annotations

import gzip
import hashlib
import json
import math
import re
from datetime import datetime, timedelta, timezone
from functools import lru_cache
from pathlib import Path

KST = timezone(timedelta(hours=9))
VERSION = "traffic-incident-silver-v1"
SOURCE_CRS = "EPSG:5181"
TARGET_CRS = "EPSG:4326"
LINK_NAMESPACE = "SEOUL_ACCINFO_LINK"
LINK_ID_SYSTEM = "SEOUL_TOPIS_ACCINFO_LINK_ID"


def _sha(raw):
    return hashlib.sha256(raw).hexdigest()


def _clean(value):
    return str(value).strip() if value is not None and str(value).strip() else None


def _float(value):
    try:
        result = float(value)
        return result if math.isfinite(result) else None
    except (TypeError, ValueError):
        return None


def _time(date_value, time_value):
    date_text, time_text = _clean(date_value), _clean(time_value)
    if not date_text and not time_text:
        return None, "MISSING"
    if (
        not date_text
        or not time_text
        or not re.fullmatch(r"\d{8}", date_text)
        or not re.fullmatch(r"\d{4}(?:\d{2})?", time_text)
    ):
        return None, "INVALID"
    if len(time_text) == 4:
        time_text += "00"
    try:
        return datetime.strptime(date_text + time_text, "%Y%m%d%H%M%S").replace(tzinfo=KST).isoformat(), "VALID"
    except ValueError:
        return None, "INVALID"


def _haversine(lat1, lon1, lat2, lon2):
    radius = 6371008.8
    a1, a2 = math.radians(lat1), math.radians(lat2)
    da, do = a2 - a1, math.radians(lon2 - lon1)
    value = math.sin(da / 2) ** 2 + math.cos(a1) * math.cos(a2) * math.sin(do / 2) ** 2
    return 2 * radius * math.asin(min(1.0, math.sqrt(value)))


def _point_segment_distance(lat, lon, a, b):
    """Short Seoul distances in a local metre plane; endpoints are [lon, lat]."""
    scale_x = 111320.0 * math.cos(math.radians(lat))
    ax, ay = (a[0] - lon) * scale_x, (a[1] - lat) * 110540.0
    bx, by = (b[0] - lon) * scale_x, (b[1] - lat) * 110540.0
    dx, dy = bx - ax, by - ay
    if dx == 0 and dy == 0:
        return math.hypot(ax, ay)
    t = max(0.0, min(1.0, -(ax * dx + ay * dy) / (dx * dx + dy * dy)))
    return math.hypot(ax + t * dx, ay + t * dy)


def _distance_to_geometry(lat, lon, geometry):
    if len(geometry) == 1:
        return _haversine(lat, lon, geometry[0][1], geometry[0][0])
    return min(_point_segment_distance(lat, lon, a, b) for a, b in zip(geometry, geometry[1:]))


def description_fields(text):
    """Extract auxiliary words only. These values never select or replace a structured link."""
    value = _clean(text) or ""
    road = re.search(r"([가-힣A-Za-z0-9·]+(?:고속도로|대로|로|길))", value)
    direction = re.search(r"([가-힣A-Za-z0-9·]+\s*(?:방향|방면|쪽))", value)
    lanes = re.search(r"((?:전|전체|편도|\d+\s*개?)\s*차로(?:\s*(?:통제|차단))?)", value)
    region = re.search(r"([가-힣]+구)\s*(?:일대|전역|부근)", value)
    return {
        "descriptionRoadName": road.group(1) if road else None,
        "descriptionDirection": direction.group(1) if direction else None,
        "descriptionLaneControl": lanes.group(1) if lanes else None,
        "descriptionRegion": region.group(1) if region else None,
    }


class VerifiedLinkMaster:
    """A promoted official link artifact. Candidate/audit files are deliberately rejected."""

    def __init__(self, metadata=None, links=None):
        self.metadata = metadata or {}
        self.links = links or {}
        self.available = bool(links)
        self.grid_size = 0.005
        self.grid = {}
        for link_id, row in self.links.items():
            geometry = row["geometry"]
            min_x, max_x = min(p[0] for p in geometry), max(p[0] for p in geometry)
            min_y, max_y = min(p[1] for p in geometry), max(p[1] for p in geometry)
            for gx in range(math.floor(min_x / self.grid_size), math.floor(max_x / self.grid_size) + 1):
                for gy in range(math.floor(min_y / self.grid_size), math.floor(max_y / self.grid_size) + 1):
                    self.grid.setdefault((gx, gy), set()).add(link_id)

    @classmethod
    def unavailable(cls):
        return cls()

    @classmethod
    def load(cls, path, expected_sha):
        if not path and not expected_sha:
            return cls.unavailable()
        if not path or not expected_sha:
            raise ValueError("TRAFFIC_LINK_MASTER_PATH and TRAFFIC_LINK_MASTER_SHA256 must be set together")
        raw = Path(path).read_bytes()
        if _sha(raw) != expected_sha:
            raise ValueError("traffic link master SHA mismatch")
        data = json.loads(gzip.decompress(raw) if str(path).endswith(".gz") else raw)
        required = {
            "schemaVersion": 1,
            "namespace": LINK_NAMESPACE,
            "idSystem": LINK_ID_SYSTEM,
            "sourceCrs": TARGET_CRS,
            "accInfoLinkIdVerified": True,
            "reviewStatus": "APPROVED",
        }
        if any(data.get(key) != value for key, value in required.items()):
            raise ValueError("traffic link master is not an approved AccInfo ID artifact")
        if (
            data.get("sourceDataset") != "OA-13310"
            or not _clean(data.get("version"))
            or not _clean(data.get("evidenceRef"))
        ):
            raise ValueError("traffic link master source/version/evidence is missing")
        try:
            valid_from = datetime.fromisoformat(str(data["validFrom"]).replace("Z", "+00:00"))
            valid_until = datetime.fromisoformat(str(data["validUntil"]).replace("Z", "+00:00"))
            if valid_from.tzinfo is None or valid_until.tzinfo is None or valid_from >= valid_until:
                raise ValueError
        except (KeyError, TypeError, ValueError):
            raise ValueError("traffic link master validity interval is invalid") from None
        links = {}
        for row in data.get("links", []):
            link_id = _clean(row.get("linkId"))
            geometry = row.get("geometry")
            valid_geometry = (
                isinstance(geometry, list)
                and geometry
                and all(
                    isinstance(point, list)
                    and len(point) == 2
                    and _float(point[0]) is not None
                    and _float(point[1]) is not None
                    and 126.0 <= float(point[0]) <= 128.0
                    and 37.0 <= float(point[1]) <= 38.0
                    for point in geometry
                )
            )
            if not link_id or not valid_geometry or link_id in links:
                raise ValueError("traffic link master contains invalid or duplicate links")
            links[link_id] = {
                "linkId": link_id,
                "roadName": _clean(row.get("roadName")),
                "districtName": _clean(row.get("districtName")),
                "startNodeId": _clean(row.get("startNodeId")),
                "endNodeId": _clean(row.get("endNodeId")),
                "geometry": [[float(point[0]), float(point[1])] for point in geometry],
            }
        if not links:
            raise ValueError("traffic link master has no links")
        return cls(
            {key: data.get(key) for key in ("version", "evidenceRef", "sourceDataset", "validFrom", "validUntil")},
            links,
        )

    def exact(self, link_id):
        return self.links.get(link_id)

    def applicable(self, timestamp):
        try:
            value = datetime.fromisoformat(str(timestamp).replace("Z", "+00:00"))
            start = datetime.fromisoformat(str(self.metadata["validFrom"]).replace("Z", "+00:00"))
            end = datetime.fromisoformat(str(self.metadata["validUntil"]).replace("Z", "+00:00"))
            return value.tzinfo is not None and start <= value < end
        except (KeyError, TypeError, ValueError):
            return False

    def nearest(self, latitude, longitude, max_meters):
        matches = []
        gx, gy = math.floor(longitude / self.grid_size), math.floor(latitude / self.grid_size)
        radius = max(1, math.ceil(max_meters / 400.0))
        candidate_ids = set()
        for x in range(gx - radius, gx + radius + 1):
            for y in range(gy - radius, gy + radius + 1):
                candidate_ids.update(self.grid.get((x, y), ()))
        for link_id in candidate_ids:
            row = self.links[link_id]
            distance = _distance_to_geometry(latitude, longitude, row["geometry"])
            if distance <= max_meters:
                matches.append((distance, row))
        matches.sort(key=lambda item: (item[0], item[1]["linkId"]))
        if not matches:
            return None, None, 0
        # Two nearly equal links are ambiguous (often opposite directions); do not auto-select.
        if len(matches) > 1 and matches[1][0] - matches[0][0] < 10.0:
            return None, matches[0][0], len(matches)
        return matches[0][1], matches[0][0], len(matches)


@lru_cache(maxsize=1)
def _transformer():
    from pyproj import Transformer

    return Transformer.from_crs(SOURCE_CRS, TARGET_CRS, always_xy=True)


def transform_grs80(x_value, y_value):
    x, y = _float(x_value), _float(y_value)
    if x is None and y is None:
        return None, None, "MISSING"
    if x is None or y is None:
        return None, None, "INCOMPLETE"
    try:
        longitude, latitude = _transformer().transform(x, y)
    except Exception:
        return None, None, "TRANSFORM_FAILED"
    if not (37.0 <= latitude <= 38.0 and 126.0 <= longitude <= 128.0):
        return latitude, longitude, "OUT_OF_RANGE"
    return latitude, longitude, "TRANSFORMED"


def normalize_incident(envelope, master, stale_seconds=2700, direct_tolerance_meters=200, nearby_max_meters=100):
    payload = envelope.get("payload") if isinstance(envelope.get("payload"), dict) else {}
    raw = payload.get("raw") if isinstance(payload.get("raw"), dict) else {}
    incident_id = _clean(raw.get("incidentId") or payload.get("providerIncidentId"))
    input_link_id = _clean(raw.get("linkId") or payload.get("providerLinkId"))
    collected_at = _clean(envelope.get("collectedAt"))
    starts_at, start_status = _time(raw.get("occurredDate"), raw.get("occurredTime"))
    expected_at, expected_status = _time(raw.get("expectedClearDate"), raw.get("expectedClearTime"))
    latitude, longitude, coordinate_status = transform_grs80(raw.get("grs80tmX"), raw.get("grs80tmY"))
    auxiliary = description_fields(raw.get("description"))
    reasons, flags = [], []
    if not incident_id:
        reasons.append("MISSING_INCIDENT_ID")
    if not collected_at:
        reasons.append("MISSING_COLLECTED_AT")
    if start_status != "VALID":
        reasons.append("MISSING_OR_INVALID_STARTED_AT")
    if expected_status == "INVALID":
        flags.append("INVALID_EXPECTED_CLEAR_AT")
    if expected_at and starts_at and expected_at <= starts_at:
        expected_at = None
        flags.append("EXPECTED_CLEAR_NOT_AFTER_START")
    if coordinate_status != "TRANSFORMED":
        flags.append("COORDINATE_" + coordinate_status)

    location_type, master_status, matched, distance, nearby_count = "UNMATCHED", "UNAVAILABLE", None, None, 0
    master_applicable = master.available and master.applicable(collected_at)
    if master_applicable:
        master_status = "NOT_FOUND"
        namespace_verified = _clean(payload.get("providerLinkNamespace")) == LINK_NAMESPACE
        exact = master.exact(input_link_id) if input_link_id and namespace_verified else None
        if exact:
            master_status = "MATCHED"
            if coordinate_status == "TRANSFORMED":
                distance = _distance_to_geometry(latitude, longitude, exact["geometry"])
                if distance > direct_tolerance_meters:
                    location_type, master_status = "CONFLICT", "LINK_COORDINATE_CONFLICT"
                    coordinate_status = "LINK_CONFLICT"
                    flags.append("LINK_COORDINATE_CONFLICT")
                else:
                    location_type, matched = "DIRECT", exact
                    coordinate_status = "MATCHED_LINK"
            else:
                location_type, matched = "DIRECT", exact
        elif coordinate_status == "TRANSFORMED":
            nearby, distance, nearby_count = master.nearest(latitude, longitude, nearby_max_meters)
            if nearby:
                location_type, matched, master_status = "NEARBY", nearby, "NEARBY_MATCHED"
            elif nearby_count:
                master_status = "NEARBY_AMBIGUOUS"
                flags.append("NEARBY_LINK_AMBIGUOUS")
    elif master.available:
        master_status = "VERSION_OUT_OF_RANGE"
        flags.append("LINK_MASTER_VERSION_OUT_OF_RANGE")
    if location_type == "UNMATCHED" and auxiliary["descriptionRegion"]:
        location_type = "REGIONAL_CONTEXT"
    if master_applicable and input_link_id and _clean(payload.get("providerLinkNamespace")) != LINK_NAMESPACE:
        if not matched:
            master_status = "NAMESPACE_UNVERIFIED"
        flags.append("LINK_NAMESPACE_UNVERIFIED")
    if not matched and input_link_id and master_status in ("NOT_FOUND", "UNAVAILABLE"):
        flags.append("LINK_MASTER_" + master_status)
    if location_type == "UNMATCHED":
        flags.append("LOCATION_UNMATCHED")

    valid_until = None
    if collected_at:
        try:
            valid_until = (
                datetime.fromisoformat(collected_at.replace("Z", "+00:00")) + timedelta(seconds=stale_seconds)
            ).isoformat()
        except ValueError:
            reasons.append("INVALID_COLLECTED_AT")
    status = (
        "REJECTED"
        if reasons
        else ("PARTIAL" if flags or location_type in ("UNMATCHED", "REGIONAL_CONTEXT") else "VALID")
    )
    row = {
        "silverVersion": VERSION,
        "eventId": _clean(envelope.get("eventId")),
        "source": _clean(envelope.get("source")),
        "providerIncidentId": incident_id,
        "contentHash": _clean(payload.get("contentHash"))
        or _sha(json.dumps(raw, ensure_ascii=False, sort_keys=True).encode()),
        "collectedAt": collected_at,
        "startedAt": starts_at,
        "expectedClearAt": expected_at,
        "validUntil": valid_until,
        "lifecycleStatus": "ACTIVE_OBSERVED" if status != "REJECTED" else "UNKNOWN",
        "incidentTypeCode": _clean(raw.get("incidentTypeCode")),
        "incidentDetailTypeCode": _clean(raw.get("incidentDetailTypeCode")),
        "description": _clean(raw.get("description")),
        "inputLinkNamespace": _clean(payload.get("providerLinkNamespace")),
        "inputLinkId": input_link_id,
        "matchedLinkId": matched["linkId"] if matched else None,
        "matchedRoadName": matched["roadName"] if matched else None,
        "matchedDistrictName": matched["districtName"] if matched else None,
        "matchedStartNodeId": matched["startNodeId"] if matched else None,
        "matchedEndNodeId": matched["endNodeId"] if matched else None,
        "locationMatchType": location_type,
        "linkMasterStatus": master_status,
        "linkMasterVersion": master.metadata.get("version"),
        "linkMasterEvidenceRef": master.metadata.get("evidenceRef"),
        "grs80tmX": _float(raw.get("grs80tmX")),
        "grs80tmY": _float(raw.get("grs80tmY")),
        "sourceCrs": SOURCE_CRS,
        "latitude": latitude,
        "longitude": longitude,
        "coordinateStatus": coordinate_status,
        "linkCoordinateDistanceMeters": round(distance, 3) if distance is not None else None,
        "nearbyCandidateCount": nearby_count,
        **auxiliary,
        "processingStatus": status,
        "missingFields": [],
        "qualityFlags": sorted(set(flags)),
        "rejectionReasons": sorted(set(reasons)),
        "rawPayloadJson": json.dumps(payload, ensure_ascii=False, sort_keys=True),
    }
    row["missingFields"] = [
        name
        for name in (
            "providerIncidentId",
            "startedAt",
            "expectedClearAt",
            "inputLinkId",
            "latitude",
            "longitude",
            "matchedLinkId",
        )
        if row.get(name) is None
    ]
    return row
