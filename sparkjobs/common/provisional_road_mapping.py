"""검토 후보 중 단일 연속 경로만 PROVISIONAL 버스-도로 대응표로 만든다."""

import collections
import datetime as dt
import hashlib
import json
import re
import uuid
from pathlib import Path

MAPPING_VERSION = "bus-road-link-provisional-v1"
LINK_NAMESPACE = "SEOUL_ACCINFO_LINK"
DEFAULT_TTL_SECONDS = 604_800
_SAFE = re.compile(r"^[A-Za-z0-9._-]+$")
_ACTIVATE = r"""
local actual = redis.call('HLEN', KEYS[1])
if actual ~= tonumber(ARGV[1]) then
  return redis.error_reply('staging row count mismatch: expected=' .. ARGV[1] .. ', actual=' .. actual)
end
redis.call('RENAME', KEYS[1], KEYS[2])
redis.call('EXPIRE', KEYS[2], tonumber(ARGV[2]))
redis.call('SET', KEYS[3], KEYS[2], 'EX', tonumber(ARGV[2]))
redis.call('SET', KEYS[4], ARGV[3], 'EX', tonumber(ARGV[2]))
return actual
"""


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _part(name, value):
    value = str(value or "")
    if not value or not _SAFE.fullmatch(value):
        raise ValueError(f"{name} contains unsafe Redis key characters: {value!r}")
    return value


def mapping_keys(master_version, candidate_sha, run_id=None):
    base = "ml:mapping:bus-road-provisional:{}:{}:{}".format(
        MAPPING_VERSION,
        _part("master_version", master_version),
        _part("candidate_sha", candidate_sha),
    )
    result = {
        "final": base,
        "active": "ml:mapping:bus-road-provisional:active:" + master_version,
        "metadata": base + ":meta",
    }
    if run_id is not None:
        result["staging"] = base + ":staging:" + _part("run_id", run_id)
    return result


def _selected_link_ids(candidate):
    path = candidate.get("candidatePath") or {}
    if path.get("state") != "SINGLE_CONNECTED_CANDIDATE_PATH":
        return None, "PATH_NOT_SINGLE_CONNECTED"
    if candidate.get("sampleCount", 0) <= 0 or candidate.get("directionPermittedSamples") != candidate.get(
        "sampleCount"
    ):
        return None, "INCOMPLETE_SAMPLE_COVERAGE"
    if candidate.get("ambiguousSamples") != 0:
        return None, "AMBIGUOUS_SAMPLE"

    selected = []
    for token in path.get("path") or []:
        ids = set()
        for sample in candidate.get("reviewSamples") or []:
            for road in sample.get("permittedCandidates") or []:
                if road.get("linkId") != token.get("linkId") or road.get("orientation") != token.get("orientation"):
                    continue
                evidence = road.get("directionEvidence") or {}
                link_id = evidence.get("nationalStandardLinkId")
                if evidence.get("permitted") is True and isinstance(link_id, str) and link_id and link_id != "0":
                    ids.add(link_id)
        if len(ids) != 1:
            return None, "CANDIDATE_LINK_ID_NOT_UNIQUE"
        link_id = next(iter(ids))
        if not selected or selected[-1] != link_id:
            selected.append(link_id)
    if not selected:
        return None, "CANDIDATE_LINK_ID_MISSING"
    return selected, None


def build_rows(candidates, master_version, traffic_link_master_version, as_of, valid_until, candidate_sha):
    """같은 segment의 모든 후보 occurrence가 동일할 때만 PROVISIONAL 행을 만든다."""
    try:
        valid_from_time = dt.datetime.fromisoformat(str(as_of).replace("Z", "+00:00"))
        valid_until_time = dt.datetime.fromisoformat(str(valid_until).replace("Z", "+00:00"))
    except (TypeError, ValueError) as exc:
        raise ValueError("timezone-aware provisional validity is required") from exc
    if valid_from_time.tzinfo is None or valid_until_time.tzinfo is None or valid_from_time >= valid_until_time:
        raise ValueError("timezone-aware increasing provisional validity is required")
    grouped = collections.defaultdict(list)
    rejected = collections.Counter()
    for candidate in candidates:
        edge = candidate.get("edge") or {}
        segment_id = edge.get("segmentId")
        if not isinstance(segment_id, str) or not segment_id:
            rejected["SEGMENT_ID_MISSING"] += 1
            continue
        links, reason = _selected_link_ids(candidate)
        grouped[segment_id].append((links, reason))

    rows = {}
    for segment_id, occurrences in grouped.items():
        reasons = [reason for _, reason in occurrences if reason]
        paths = {tuple(links) for links, reason in occurrences if not reason}
        if reasons:
            rejected[reasons[0]] += 1
            continue
        if len(paths) != 1:
            rejected["SEGMENT_PATHS_DISAGREE"] += 1
            continue
        links = list(next(iter(paths)))
        rows[segment_id] = {
            "segmentId": segment_id,
            "masterVersion": master_version,
            "mappingVersion": MAPPING_VERSION,
            "trafficLinkMasterVersion": traffic_link_master_version,
            "mappingAsOf": as_of,
            "topisLinkNamespace": LINK_NAMESPACE,
            "topisLinkIds": links,
            "topisLinkCount": len(links),
            "mappingStatus": "PROVISIONAL",
            "mappingReason": "SINGLE_CONNECTED_GEOMETRY_CANDIDATE",
            "mappingBasis": "UNVERIFIED_NATIONAL_LINK_ID_EQUIVALENCE",
            "candidateSourceSha256": candidate_sha,
            "occurrenceCoverageRatio": 1.0,
            "roadPathValidFrom": as_of,
            "roadPathValidUntil": valid_until,
        }
    return rows, dict(rejected)


def load_rows(path, expected_sha, master_version, traffic_link_master_version, as_of, valid_until):
    actual_sha = sha256(path)
    if actual_sha != expected_sha.lower():
        raise ValueError("candidate SHA mismatch")
    with Path(path).open(encoding="utf-8") as source:
        candidates = [json.loads(line) for line in source if line.strip()]
    return build_rows(candidates, master_version, traffic_link_master_version, as_of, valid_until, actual_sha)


def activate_staged_mapping(client, keys, expected_rows, ttl_seconds, metadata):
    if expected_rows <= 0 or ttl_seconds <= 0:
        raise ValueError("expected rows and TTL must be positive")
    return int(
        client.eval(
            _ACTIVATE,
            4,
            keys["staging"],
            keys["final"],
            keys["active"],
            keys["metadata"],
            expected_rows,
            ttl_seconds,
            json.dumps(metadata, ensure_ascii=False, separators=(",", ":")),
        )
    )


def publish(client, rows, metadata, ttl_seconds=DEFAULT_TTL_SECONDS):
    if not rows:
        raise ValueError("no strict provisional road mappings")
    keys = mapping_keys(metadata["masterVersion"], metadata["candidateSourceSha256"], uuid.uuid4().hex)
    client.delete(keys["staging"])
    client.hset(keys["staging"], "__ddubi_staging__", "1")
    client.expire(keys["staging"], min(int(ttl_seconds), 3600))
    pipe = client.pipeline(transaction=False)
    for index, (segment, row) in enumerate(rows.items(), start=1):
        pipe.hset(keys["staging"], segment, json.dumps(row, ensure_ascii=False, separators=(",", ":")))
        if index % 1000 == 0:
            pipe.execute()
    pipe.execute()
    client.hdel(keys["staging"], "__ddubi_staging__")
    complete_metadata = {
        **metadata,
        "mappingVersion": MAPPING_VERSION,
        "mappingStatus": "PROVISIONAL",
        "rowCount": len(rows),
        "publishedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
    }
    activate_staged_mapping(client, keys, len(rows), int(ttl_seconds), complete_metadata)
    return {
        "activeKey": keys["active"],
        "mappingKey": keys["final"],
        "metadataKey": keys["metadata"],
        "rowCount": len(rows),
        "ttlSeconds": int(ttl_seconds),
    }
