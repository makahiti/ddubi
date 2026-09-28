"""공식 노선 링크 감사 후보(mapping-candidates.jsonl)를 PROVISIONAL 버스-도로 대응표로 만든다.

입력은 backend/tools/road-impact/audit.py 가 BIS 구간링크·TOPIS 표준링크 공식 자료로 만든
후보 한 줄(segment occurrence)이다. GTFS 형상 기반 후보를 받는 provisional_road_mapping 과 달리
`topisServiceLinkIds` 가 그대로 도로 링크 목록이다. 이 ID 는 TOPIS AccInfo(실시간 돌발)의
`LINK_ID` 와 같은 체계로 관측됐지만(2026-09-23 서울 돌발 35건 중 21건 일치), 공식 문서로 동일성이
확인된 것은 아니어서 mappingStatus 는 PROVISIONAL 로만 발행한다. 정식 RESOLVED 포인터는 건드리지 않는다.

받는 줄: 링크가 끝까지 이어진 occurrence 만. `unmappedBisLinkIds` 가 남아 있으면(경로 일부 누락)
버린다. 같은 segmentId 의 모든 occurrence 가 같은 링크 목록일 때만 한 행을 만든다.
"""

import collections
import datetime as dt
import json
from pathlib import Path

from common.provisional_road_mapping import _SAFE, LINK_NAMESPACE, MAPPING_VERSION, sha256

EVIDENCE_METHOD = "OFFICIAL_ROUTE_LINKS"
MAPPING_REASON = "OFFICIAL_ROUTE_LINKS_COMPLETE"
MAPPING_BASIS = "UNVERIFIED_TOPIS_SERVICE_LINK_ACCINFO_EQUIVALENCE"


def _validity(as_of, valid_until):
    try:
        valid_from_time = dt.datetime.fromisoformat(str(as_of).replace("Z", "+00:00"))
        valid_until_time = dt.datetime.fromisoformat(str(valid_until).replace("Z", "+00:00"))
    except (TypeError, ValueError) as exc:
        raise ValueError("timezone-aware provisional validity is required") from exc
    if valid_from_time.tzinfo is None or valid_until_time.tzinfo is None or valid_from_time >= valid_until_time:
        raise ValueError("timezone-aware increasing provisional validity is required")


def selected_link_ids(candidate):
    """한 occurrence 의 도로 링크 목록과 거절 사유. 사유가 있으면 링크는 None."""
    if candidate.get("evidenceMethod") != EVIDENCE_METHOD:
        return None, "EVIDENCE_METHOD_UNSUPPORTED"
    if candidate.get("unmappedBisLinkIds"):
        return None, "BIS_LINKS_UNMAPPED"
    links = candidate.get("topisServiceLinkIds")
    if not isinstance(links, list) or not links:
        return None, "TOPIS_LINKS_MISSING"
    selected = []
    for link_id in links:
        if not isinstance(link_id, str) or not link_id or link_id == "0" or not _SAFE.fullmatch(link_id):
            return None, "LINK_ID_UNSAFE"
        # 같은 링크가 연속으로 반복되면 하나로 본다. 순서는 원본 그대로 둔다.
        if not selected or selected[-1] != link_id:
            selected.append(link_id)
    return selected, None


def build_rows(candidates, master_version, traffic_link_master_version, as_of, valid_until, candidate_sha):
    """같은 segment 의 모든 occurrence 가 같은 링크 목록일 때만 PROVISIONAL 행을 만든다."""
    _validity(as_of, valid_until)
    grouped = collections.defaultdict(list)
    rejected = collections.Counter()
    for candidate in candidates:
        segment_id = candidate.get("segmentId")
        if not isinstance(segment_id, str) or not segment_id:
            rejected["SEGMENT_ID_MISSING"] += 1
            continue
        grouped[segment_id].append(selected_link_ids(candidate))

    rows = {}
    for segment_id, occurrences in grouped.items():
        reasons = [reason for _, reason in occurrences if reason]
        paths = {tuple(links) for links, reason in occurrences if not reason}
        if reasons:
            # 한 occurrence 라도 경로가 불완전하면 그 segment 는 통째로 보류한다. 일부만 믿지 않는다.
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
            "mappingReason": MAPPING_REASON,
            "mappingBasis": MAPPING_BASIS,
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
