"""검증된 돌발 문장 추출 결과를 구간별 특성으로 모은다."""

from datetime import datetime

NAMES = (
    "incidentAiCoverageRatio",
    "textAccidentCount",
    "textConstructionCount",
    "textBreakdownCount",
    "textFullClosureCount",
    "textPartialClosureCount",
    "maxExplicitClosedLaneCount",
    "busDetourMentionCount",
    "textPlannedIncidentCount",
    "textCompletedIncidentCount",
)


def aggregate_text_features(incidents, as_of):
    """수집시각뿐 아니라 AI 추출 완료시각도 cutoff 이하일 때만 사용한다."""
    features = dict.fromkeys(NAMES)
    unique = {row["providerIncidentId"]: row for row in incidents}
    valid = []
    for row in unique.values():
        value = row.get("textFeatures")
        if not isinstance(value, dict) or value.get("aiExtractionStatus") != "VALID":
            continue
        try:
            extracted = datetime.fromisoformat(value["aiExtractedAt"].replace("Z", "+00:00"))
            if extracted.tzinfo is None or extracted > as_of:
                continue
        except (KeyError, ValueError, TypeError, AttributeError):
            continue
        valid.append(value)
    features["incidentAiCoverageRatio"] = len(valid) / len(unique) if unique else 1.0
    if len(valid) != len(unique):
        return features
    kinds = [row.get("aiIncidentKind") for row in valid]
    if all(
        kind in {"ACCIDENT", "CONSTRUCTION", "BREAKDOWN", "ASSEMBLY", "EVENT", "WEATHER", "FIRE", "OTHER"}
        for kind in kinds
    ):
        features.update(
            textAccidentCount=kinds.count("ACCIDENT"),
            textConstructionCount=kinds.count("CONSTRUCTION"),
            textBreakdownCount=kinds.count("BREAKDOWN"),
        )
    scopes = [row.get("aiControlScope") for row in valid]
    if all(scope in {"FULL", "PARTIAL", "NONE"} for scope in scopes):
        features.update(textFullClosureCount=scopes.count("FULL"), textPartialClosureCount=scopes.count("PARTIAL"))
    counts = [row.get("aiClosedLaneCount") for row in valid]
    explicit = [count for count in counts if type(count) is int and count > 0]
    features["maxExplicitClosedLaneCount"] = max(explicit) if explicit else None
    detours = [row.get("aiBusDetourMentioned") for row in valid]
    if all(type(value) is bool for value in detours):
        features["busDetourMentionCount"] = sum(detours)
    states = [row.get("aiStatusMention") for row in valid]
    if all(state in {"PLANNED", "ACTIVE", "COMPLETED"} for state in states):
        features.update(
            textPlannedIncidentCount=states.count("PLANNED"), textCompletedIncidentCount=states.count("COMPLETED")
        )
    return features
