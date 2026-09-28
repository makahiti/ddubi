"""새 AccInfo 전체 스냅샷을 감지해 영향받는 버스 구간의 현재 특성을 다시 조립한다."""

import hashlib
import json
import logging
import os
import time
from collections import defaultdict
from datetime import UTC, datetime

from online_feature_assembler import OnlineFeatureAssembler

LOG = logging.getLogger(__name__)
SNAPSHOT_KEY = "traffic:latest:incident-snapshot:all-current"
STATE_TTL_SECONDS = 86400


def _json(raw):
    try:
        return json.loads(raw) if raw else None
    except (TypeError, ValueError):
        return None


def _instant(value):
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        return parsed.astimezone(UTC) if parsed.tzinfo else None
    except (AttributeError, TypeError, ValueError):
        return None


def mapping_index(client, mapping_key, master_version, link_master_version, crosswalk_sha, accepted_status):
    """검증된 Redis artifact에서 허용된 상태의 segment→link 목록을 읽는다."""
    metadata = _json(client.get(mapping_key + ":meta"))
    if not isinstance(metadata, dict) or any(
        (
            metadata.get("masterVersion") != master_version,
            metadata.get("trafficLinkMasterVersion") != link_master_version,
            metadata.get("crosswalkSha256") != crosswalk_sha,
        )
    ):
        return None
    segments = {}
    row_count = 0
    for _, raw in client.hscan_iter(mapping_key, count=1000):
        row_count += 1
        row = _json(raw)
        if not isinstance(row, dict) or row.get("mappingStatus") != accepted_status:
            continue
        if (
            row.get("masterVersion") != master_version
            or row.get("trafficLinkMasterVersion") != link_master_version
            or row.get("topisLinkNamespace") != "SEOUL_ACCINFO_LINK"
        ):
            return None
        links = row.get("topisLinkIds")
        segment = row.get("segmentId")
        if not isinstance(links, list) or not links or not isinstance(segment, str):
            return None
        segment_links = set()
        for link in links:
            if not isinstance(link, str) or not link:
                return None
            segment_links.add(link)
        segments[segment] = segment_links
    if row_count == 0 or row_count != metadata.get("rowCount"):
        return None
    return segments


class IncidentFeatureRefresher:
    def __init__(
        self,
        client,
        master_version,
        crosswalk_sha,
        now=lambda: datetime.now(UTC),
        allow_provisional_road=False,
    ):
        if not master_version or len(crosswalk_sha) != 64:
            raise ValueError("master version and crosswalk SHA are required")
        self.client = client
        self.master_version = master_version
        self.crosswalk_sha = crosswalk_sha.lower()
        self.allow_provisional_road = bool(allow_provisional_road)
        self.now = now
        self.assembler = OnlineFeatureAssembler(
            client,
            now=now,
            expected_crosswalk_sha=self.crosswalk_sha,
            allow_provisional_road=self.allow_provisional_road,
        )
        self.state_key = "ml:incident-feature-refresh:last:" + master_version

    def refresh_once(self):
        raw = self.client.get(SNAPSHOT_KEY)
        snapshot = _json(raw)
        current = self.now().astimezone(UTC)
        if not isinstance(snapshot, dict) or not (
            snapshot.get("snapshotVersion") == "traffic-incident-snapshot-v1"
            and snapshot.get("completeSnapshot") is True
            and snapshot.get("linkMasterAvailable") is True
            and snapshot.get("linkNamespace") == "SEOUL_ACCINFO_LINK"
            and isinstance(snapshot.get("incidents"), list)
        ):
            return {"status": "SNAPSHOT_UNAVAILABLE", "updated": 0}
        collected, expires = _instant(snapshot.get("collectedAt")), _instant(snapshot.get("validUntil"))
        if collected is None or expires is None or not collected <= current < expires:
            return {"status": "SNAPSHOT_STALE", "updated": 0}
        sources = []
        active_key = "ml:mapping:bus-road:active:" + self.master_version
        mapping_key = self.client.get(active_key)
        if mapping_key and str(mapping_key).startswith("ml:mapping:bus-road:"):
            sources.append((mapping_key, "RESOLVED"))
        if self.allow_provisional_road:
            provisional_key = self.client.get("ml:mapping:bus-road-provisional:active:" + self.master_version)
            if provisional_key and str(provisional_key).startswith("ml:mapping:bus-road-provisional:"):
                sources.append((provisional_key, "PROVISIONAL"))
        if not sources:
            return {"status": "MAPPING_UNAVAILABLE", "updated": 0}
        digest = hashlib.sha256(raw.encode("utf-8") if isinstance(raw, str) else raw).hexdigest()
        previous = _json(self.client.get(self.state_key)) or {}
        mapping_keys = [source[0] for source in sources]
        if previous.get("snapshotSha256") == digest and previous.get("mappingKeys") == mapping_keys:
            return {"status": "UNCHANGED", "updated": 0}
        segment_links = {}
        for source_key, accepted_status in sources:
            index = mapping_index(
                self.client,
                source_key,
                self.master_version,
                snapshot.get("linkMasterVersion"),
                self.crosswalk_sha,
                accepted_status,
            )
            if index is None:
                continue
            for segment, links in index.items():
                segment_links.setdefault(segment, links)
        if not segment_links:
            return {"status": "MAPPING_NOT_VERIFIED", "updated": 0}
        reverse = defaultdict(set)
        for segment, links in segment_links.items():
            for link in links:
                reverse[link].add(segment)
        current_links = {
            row.get("matchedLinkId")
            for row in snapshot["incidents"]
            if isinstance(row, dict) and isinstance(row.get("matchedLinkId"), str)
        }
        old_links = set(previous.get("links") or [])
        current_segments = set()
        for link in current_links:
            current_segments.update(reverse.get(link, ()))
        affected = current_segments | set(previous.get("segments") or [])
        for link in old_links:
            affected.update(reverse.get(link, ()))
        for segment in sorted(affected):
            # 조립기가 최신 스냅샷·매핑 버전·유효기간을 다시 확인한다.
            self.assembler.assemble(
                {
                    "segmentId": segment,
                    "masterVersion": self.master_version,
                    "asOf": current.isoformat(),
                    "passageAt": current.isoformat(),
                }
            )
        current_keys = [self.client.get(active_key)]
        if self.allow_provisional_road:
            current_keys.append(self.client.get("ml:mapping:bus-road-provisional:active:" + self.master_version))
        if [key for key in current_keys if key] != mapping_keys or self.client.get(SNAPSHOT_KEY) != raw:
            return {"status": "SOURCE_CHANGED", "updated": len(affected)}
        self.client.setex(
            self.state_key,
            STATE_TTL_SECONDS,
            json.dumps(
                {
                    "snapshotSha256": digest,
                    "mappingKeys": mapping_keys,
                    "links": sorted(current_links),
                    "segments": sorted(current_segments),
                    "refreshedAt": current.isoformat(),
                },
                separators=(",", ":"),
            ),
        )
        return {"status": "UPDATED", "updated": len(affected)}


def main():
    import redis

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    interval = int(os.environ.get("ML_INCIDENT_REFRESH_POLL_SECONDS", "15"))
    if interval < 1:
        raise ValueError("poll interval must be positive")
    client = redis.Redis(
        host=os.environ.get("REDIS_HOST", "redis"),
        port=int(os.environ.get("REDIS_PORT", "6379")),
        password=os.environ.get("REDIS_PASSWORD") or None,
        decode_responses=True,
        socket_timeout=3,
        socket_connect_timeout=3,
    )
    refresher = IncidentFeatureRefresher(
        client,
        os.environ["ML_INCIDENT_MASTER_VERSION"],
        os.environ["ML_BUS_CROSSWALK_SHA256"],
        allow_provisional_road=str(os.environ.get("ROAD_PROVISIONAL_MAPPING_ENABLED", "false")).lower()
        in ("1", "true", "yes"),
    )
    while True:
        try:
            result = refresher.refresh_once()
            if result["status"] != "UNCHANGED":
                LOG.info("incident feature refresh status=%s updated=%d", result["status"], result["updated"])
        except Exception as exc:
            LOG.warning("incident feature refresh failed type=%s", type(exc).__name__)
        time.sleep(interval)


if __name__ == "__main__":
    main()
