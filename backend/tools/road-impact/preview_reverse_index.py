"""검토용 감사 후보에서 사고 LINK_ID → 버스 segmentId 역조회표를 만든다.

후보의 출처·방향·유효기간 승인이 끝나지 않았으므로 운영 매핑이나 ML feature로
배포하지 않는다. 한 segmentId의 모든 버스 occurrence가 동일한 전체 경로를
가질 때만 역조회표에 포함한다.
"""

import argparse
import json
from collections import Counter, defaultdict
from pathlib import Path


def read_jsonl(path):
    with Path(path).open(encoding="utf-8") as stream:
        for number, line in enumerate(stream, 1):
            if line.strip():
                try:
                    yield json.loads(line)
                except json.JSONDecodeError as exc:
                    raise ValueError(f"{path}:{number}: invalid JSON") from exc


def build_index(audit, candidates, rejected):
    """불완전하거나 서로 다른 occurrence 경로는 segmentId 역조회에서 제외한다."""
    occurrences = defaultdict(list)
    seen = set()
    candidate_count = 0
    for is_candidate, rows in ((True, candidates), (False, rejected)):
        for row in rows:
            key = (row["namespace"], row["routeId"], row["fromOrder"])
            if key in seen:
                raise ValueError(f"duplicate bus occurrence: {key}")
            seen.add(key)
            occurrences[row["segmentId"]].append((is_candidate, row))
            candidate_count += int(is_candidate)

    if len(seen) != audit["seoulEdgeCount"]:
        raise ValueError("audit edge count does not match candidate and rejected rows")
    expected = audit.get("counts", {}).get("BIS_DIRECTIONAL_PATH")
    if expected is not None and candidate_count != expected:
        raise ValueError("audit candidate count does not match candidate rows")

    reverse = defaultdict(set)
    statuses = {}
    for segment_id, entries in occurrences.items():
        complete_paths = {
            tuple(row["topisServiceLinkIds"])
            for is_candidate, row in entries
            if is_candidate and not row["unmappedBisLinkIds"] and row["topisServiceLinkIds"]
        }
        all_complete = all(
            is_candidate and not row["unmappedBisLinkIds"] and row["topisServiceLinkIds"]
            for is_candidate, row in entries
        )
        if all_complete and len(complete_paths) == 1:
            statuses[segment_id] = "CANDIDATE_COMPLETE"
            for link_id in next(iter(complete_paths)):
                reverse[link_id].add(segment_id)
        elif len(complete_paths) > 1:
            statuses[segment_id] = "AMBIGUOUS"
        elif complete_paths:
            statuses[segment_id] = "PARTIAL"
        else:
            statuses[segment_id] = "UNMAPPED"

    return (
        {link_id: sorted(segment_ids) for link_id, segment_ids in sorted(reverse.items())},
        statuses,
        dict(Counter(statuses.values())),
    )


def match_snapshot(reverse, snapshot):
    """사고 링크와 버스 구간의 ID 겹침만 조회한다. 시간·사건 방향은 판정하지 않는다."""
    if "items" in snapshot:
        rows = snapshot["items"]
        complete = snapshot.get("isComplete") is True
        reported = snapshot["apiReportedTotalCount"]
    else:
        rows = snapshot["rows"]
        complete = True
        reported = snapshot["reportedCount"]
    if not complete or int(reported) != len(rows):
        raise ValueError("incident snapshot is incomplete")
    return [
        {
            "incidentId": row["incidentId"],
            "linkId": row["linkId"],
            "candidateSegmentIds": reverse.get(row["linkId"], []),
        }
        for row in rows
    ]


def segment_preview_features(statuses, matches):
    """매핑 불명과 이 스냅샷에서 일치 사건 0건을 구분한다."""
    incident_ids = defaultdict(set)
    for row in matches:
        for segment_id in row["candidateSegmentIds"]:
            incident_ids[segment_id].add(row["incidentId"])
    return {
        segment_id: {
            "state": "LINK_OVERLAP_CANDIDATE" if status == "CANDIDATE_COMPLETE" else "UNKNOWN_MAPPING",
            "candidateIncidentCountInSnapshot": (
                len(incident_ids[segment_id]) if status == "CANDIDATE_COMPLETE" else None
            ),
        }
        for segment_id, status in sorted(statuses.items())
    }


def preview(directory, incident_snapshot):
    root = Path(directory)
    audit = json.loads((root / "mapping-audit.json").read_text(encoding="utf-8"))
    reverse, statuses, counts = build_index(
        audit,
        read_jsonl(root / "mapping-candidates.jsonl"),
        read_jsonl(root / "mapping-rejected.jsonl"),
    )
    snapshot = json.loads(Path(incident_snapshot).read_text(encoding="utf-8"))
    matches = match_snapshot(reverse, snapshot)
    return {
        "purpose": "REVIEW_ONLY_LINK_OVERLAP_NOT_APPROVED_ML_FEATURE",
        "temporalAndIncidentDirectionVerified": False,
        "snapshotCollectedAt": snapshot.get("collectedAt"),
        "masterVersion": audit["masterVersion"],
        "busCrosswalkSha256": audit["busCrosswalkSha256"],
        "segmentStatusCounts": counts,
        "indexedLinkCount": len(reverse),
        "reverseIndex": reverse,
        "incidents": matches,
        "segmentFeatures": segment_preview_features(statuses, matches),
        "matchedIncidentLinkCount": len({row["linkId"] for row in matches if row["candidateSegmentIds"]}),
        "matchedSegmentCount": len({sid for row in matches for sid in row["candidateSegmentIds"]}),
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", required=True, help="audit.py output directory")
    parser.add_argument("--incident-snapshot", required=True, help="collector traffic_incident.py JSON or incidentId/linkId snapshot")
    parser.add_argument("--output", required=True, help="review-only JSON output")
    args = parser.parse_args(argv)
    result = preview(args.directory, args.incident_snapshot)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: v for k, v in result.items() if k not in {"reverseIndex", "incidents", "segmentFeatures"}}, ensure_ascii=False))


if __name__ == "__main__":
    main()
