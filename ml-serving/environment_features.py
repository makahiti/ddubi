"""날씨·도로 소통·사고/통제/집회 이력을 학습과 요청 준비에서 동일하게 선택한다.

각 이력은 검증한 segmentId와 masterVersion에 이미 매핑되어 있어야 한다. 현재 파일을
과거 관측으로 소급하지 않으며 발표시각과 실제 수집시각 모두 cutoff 이하일 때만 쓴다.
정보가 없는 사건 수를 0으로 채우지 않는다. feedComplete=true일 때만 0건이 의미 있다.
기존 v12 모델에 새 열을 전달하지 않고 별도 전처리 버전으로 출력한다.
"""

import math
import re
from datetime import datetime, timedelta, timezone

VERSION = "environment-asof-v1"
NUMERIC_FIELDS = (
    "temperatureC",
    "humidityPercent",
    "precipitationMm",
    "snowDepthCm",
    "windSpeedMps",
    "roadSpeedKph",
)
EVENT_TYPES = ("accident", "closure", "assembly", "protest")


def instant(value):
    if not isinstance(value, str):
        raise ValueError("ISO timestamp required")
    result = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if result.tzinfo is None:
        raise ValueError("timezone required")
    return result.astimezone(timezone(timedelta(hours=9)))


def prepare(request, observations):
    """구간별 예측 기준시각/통과시각에 사용 가능한 최신 버전을 선택한다. 정답은 읽지 않는다."""
    cutoff = instant(request["asOf"])
    passage = instant(request["passageAt"])
    if passage < cutoff:
        raise ValueError("passage before cutoff")
    required = ("segmentId", "masterVersion")
    if any(not request.get(k) for k in required):
        raise ValueError("mapped segment and version required")
    if not re.fullmatch(r"BUS:[A-Za-z0-9._-]{1,50}(:[A-Za-z0-9._-]{1,50}){2}", request["segmentId"]):
        raise ValueError("canonical bus segment required")
    selected = {}
    conflicts = set()
    rejected = 0
    for item in observations:
        if any(item.get(k) != request[k] for k in required):
            continue
        try:
            if any(not isinstance(item.get(k), str) or not item[k].strip() for k in ("source", "eventId")):
                raise ValueError("observation provenance required")
            kind = item["kind"]
            if kind not in ("weather", "road", "events"):
                raise ValueError("unknown kind")
            observed = instant(item["observedAt"])
            published = instant(item["publishedAt"])
            collected = instant(item["collectedAt"])
            start, end = instant(item["validFrom"]), instant(item["validUntil"])
            if published > cutoff or collected > cutoff:
                continue
            if start >= end or collected < published:
                raise ValueError("invalid interval")
            # 예보 대상은 미래일 수 있다. 실황을 미래 관측으로 제공하는 경우는 거절한다.
            if item.get("isForecast") is not True and (observed > cutoff or observed > collected):
                raise ValueError("future observation")
            if not start <= passage < end:
                continue
            order = (published, observed, collected)
            old = selected.get(kind)
            if old is None or order > old[0]:
                selected[kind] = (order, item)
                conflicts.discard(kind)
            elif order == old[0] and item != old[1]:
                conflicts.add(kind)
        except (KeyError, ValueError, TypeError):
            rejected += 1
    features = dict.fromkeys(NUMERIC_FIELDS)
    features.update({kind + "Count": None for kind in EVENT_TYPES})
    provenance = {}
    for kind, (_, item) in selected.items():
        if kind in conflicts:
            continue
        payload = item.get("values", {})
        if not isinstance(payload, dict):
            rejected += 1
            continue
        if kind in ("weather", "road"):
            allowed = NUMERIC_FIELDS[:-1] if kind == "weather" else ("roadSpeedKph",)
            for name in allowed:
                value = payload.get(name)
                if isinstance(value, (float, int)) and not isinstance(value, bool) and math.isfinite(value):
                    if name == "humidityPercent" and not 0 <= value <= 100:
                        continue
                    if name != "temperatureC" and value < 0:
                        continue
                    features[name] = float(value)
        else:
            for event_type in EVENT_TYPES:
                value = payload.get(event_type + "Count")
                if (
                    item.get("feedComplete") is True
                    and isinstance(value, int)
                    and not isinstance(value, bool)
                    and value >= 0
                ):
                    features[event_type + "Count"] = value
        provenance[kind] = {
            k: item.get(k)
            for k in (
                "eventId",
                "source",
                "observedAt",
                "publishedAt",
                "collectedAt",
                "validFrom",
                "validUntil",
            )
        }
    return dict(
        featureVersion=VERSION,
        segmentId=request["segmentId"],
        masterVersion=request["masterVersion"],
        asOf=request["asOf"],
        passageAt=request["passageAt"],
        features=features,
        missingFeatures=[k for k, v in features.items() if v is None],
        provenance=provenance,
        conflictingKinds=sorted(conflicts),
        rejectedObservations=rejected,
        productionReady=False,
    )
