"""학습 정답의 출처·시간·목표를 준비/학습 두 경계에서 동일하게 검증한다.

ACTUAL_TRAVERSAL은 실제 진입/이탈 시각 차이와 초 단위 정답이 일치해야 한다.
passageAt은 예측 통과시각이므로 실제 진입시각을 대신하지 않는다. 시간대 평균은
구간 집계 목표로만 허용하며 개별 실측으로 승격하지 않는다.
"""

import math

from environment_features import instant

LABEL_VERSION = "environment-label-v2"


def validate_label(request, label):
    """현재 정답으로 확인 가능한 필드만 검증한다. 출처 미확인·시각 역전·예측값은 거절한다."""
    kind = label.get("kind")
    if kind not in ("ACTUAL_TRAVERSAL", "HOURLY_MEAN_BUS_SEGMENT"):
        raise ValueError("prediction is not label")
    if label.get("verified") is not True or any(
        not isinstance(label.get(k), str) or not label[k].strip() for k in ("source", "eventId")
    ):
        raise ValueError("label provenance required")
    seconds = label.get("seconds")
    if isinstance(seconds, bool) or not isinstance(seconds, (int, float)) or not math.isfinite(seconds) or seconds <= 0:
        raise ValueError("positive finite seconds required")
    cutoff = instant(request["asOf"])
    completed = instant(label["completedAt"])
    if completed <= cutoff or instant(label["availableAt"]) < completed:
        raise ValueError("invalid label availability or completion")
    if kind == "ACTUAL_TRAVERSAL":
        if "startedAt" not in label:
            raise ValueError("actual startedAt required")
        started = instant(label["startedAt"])
        if started < cutoff or started >= completed:
            raise ValueError("actual traversal interval invalid")
        if not math.isclose((completed - started).total_seconds(), seconds, rel_tol=0, abs_tol=0.000001):
            raise ValueError("actual duration differs from timestamps")
    return kind


def quality_report(splits):
    """분할별 결측·충돌·원천 제외와 구간/정답 출처를 기록한다. 임의 합격률로 운영 승격하지 않는다."""
    from collections import Counter

    result = {}
    for name, rows in splits.items():
        missing = Counter(k for r in rows for k, v in r["features"].items() if v is None)
        result[name] = dict(
            rows=len(rows),
            allEnvironmentMissingRows=sum(all(v is None for v in r["features"].values()) for r in rows),
            missingByFeature=dict(sorted(missing.items())),
            conflictingRows=sum(bool(r["conflictingKinds"]) for r in rows),
            rejectedObservationReferences=sum(r["rejectedObservations"] for r in rows),
            mappedSegments=len({(r["masterVersion"], r["segmentId"]) for r in rows}),
            labelSources=dict(Counter(r["label"]["source"] for r in rows)),
            asOfMin=min((r["asOf"] for r in rows), key=instant, default=None),
            asOfMax=max((r["asOf"] for r in rows), key=instant, default=None),
        )
    return result
