"""별도 환경 특성 모델의 학습/서빙 행 구성과 읽기 전용 추론 어댑터다.

v12에 새 열을 몰래 추가하지 않는다. JSON manifest/모델 SHA를 검증하고 환경 특성 버전이
같은 모델만 읽는다. 모델에 없던 구간/마스터는 예측하지 않으며 train/serve에서 동일한
시각 파생·결측 NaN을 쓴다. 실제 모델이 없는 배포는 기존 v12 경로를 유지한다.
"""

import hashlib
import json
import math
from pathlib import Path
from environment_features import VERSION, NUMERIC_FIELDS, EVENT_TYPES, instant

FEATURES = (
    ["segmentCode", "dow", "hour", "minute"]
    + list(NUMERIC_FIELDS)
    + [k + "Count" for k in EVENT_TYPES]
)


def vector(row, categories):
    """구간+마스터 범주 사전은 train에서만 만든다. 예측 시 미래/미등록 구간으로 확장하지 않는다."""
    if row.get("featureVersion") != VERSION:
        raise ValueError("feature version mismatch")
    key = row["masterVersion"] + "|" + row["segmentId"]
    if key not in categories:
        raise ValueError("unseen mapped segment")
    at = instant(row["passageAt"])
    return [categories[key], (at.weekday() + 1) % 7 + 1, at.hour, at.minute] + [
        float("nan") if row["features"].get(k) is None else float(row["features"][k])
        for k in FEATURES[4:]
    ]


class EnvironmentModel:
    """하나의 검증 모델을 프로세스에서 재사용한다. output은 검증용 평균 초이며 운영 승인과 다르다."""

    def __init__(self, path):
        import lightgbm as lgb

        root = Path(path)
        self.manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
        if (
            self.manifest.get("featureVersion") != VERSION
            or self.manifest.get("features") != FEATURES
        ):
            raise ValueError("model feature contract mismatch")
        raw = (root / "model.txt").read_bytes()
        if hashlib.sha256(raw).hexdigest() != self.manifest["modelSha256"]:
            raise ValueError("model SHA mismatch")
        self.model = lgb.Booster(model_str=raw.decode("utf-8"))
        if self.model.feature_name() != FEATURES or self.manifest.get("target") not in (
            "ACTUAL_TRAVERSAL",
            "HOURLY_MEAN_BUS_SEGMENT",
        ):
            raise ValueError("model target/features mismatch")
        self.categories = self.manifest["categories"]

    def predict(self, prepared):
        """전체 결측 또는 모호한 이력은 수치 대신 unavailable로 반환한다. 결측 0 대체는 하지 않는다."""
        if prepared["conflictingKinds"] or all(
            prepared["features"].get(k) is None for k in FEATURES[4:]
        ):
            return dict(
                status="UNAVAILABLE",
                reason="ENVIRONMENT_DATA_INSUFFICIENT",
                productionReady=False,
            )
        try:
            row = vector(prepared, self.categories)
        except ValueError:
            return dict(
                status="UNAVAILABLE",
                reason="UNSEEN_MAPPED_SEGMENT",
                productionReady=False,
            )
        seconds = float(self.model.predict([row], num_threads=1)[0])
        if not math.isfinite(seconds) or seconds <= 0:
            raise ValueError("invalid prediction")
        return dict(
            status="PREVIEW",
            productionReady=False,
            meanSeconds=seconds,
            target=self.manifest["target"],
            modelVersion=self.manifest["modelVersion"],
            featureVersion=VERSION,
            missingFeatures=prepared["missingFeatures"],
        )
