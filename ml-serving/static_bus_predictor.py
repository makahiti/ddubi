"""고정 v13 묶음을 한 번 읽고 메모리에서만 버스 인접 구간을 예측한다.

v12 날짜별 reference 조회와 독립적이다. 공식 현재 마스터와 학습 관측의 교집합에
있는 route/from/to/sequence만 허용하며 개별 vocab 포함만으로 매핑 성공 처리하지 않는다.
독립 실험 객체이며 현재 HTTP 서비스나 Backend에는 연결하지 않는다. 운영 승인은
항상 false, 결과는 SHADOW다. 실패 edge는 null로 남기며 서비스 적용 여부는 판단하지 않는다.
"""

import hashlib
import json
import threading
from datetime import UTC, date, datetime, timedelta
from pathlib import Path

import lightgbm as lgb
import numpy as np
import pandas as pd
from predictor import BundleError, checked_path
from static_bus_features import FEATURES, KEY, VERSION, matrix


class StaticBusPredictor:
    """SHA 검증 완료 모델·달력·공식 인접 edge를 소유하는 읽기 전용 서빙 객체다."""

    def __init__(self, root):
        root = Path(root).resolve()
        bundle = json.loads((root / "bundle.json").read_text(encoding="utf-8"))
        required = {
            "model.txt",
            "edges.parquet",
            "input_spec.json",
            "serving_manifest.json",
            "feature_schema.json",
            "preprocessing.json",
            "coverage.json",
            "metrics.json",
            "source_usage.json",
            "static_bus_features.py",
            "fixed_statistics.parquet",
        }
        if bundle.get("format") != "ddubi-static-bus-v13" or not required <= bundle.get("files", {}).keys():
            raise BundleError("STATIC_BUNDLE_INVALID")
        for name, expected in bundle["files"].items():
            path = checked_path(root, name)
            if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
                raise BundleError("BUNDLE_SHA_MISMATCH")
        if (
            hashlib.sha256(Path(__file__).with_name("static_bus_features.py").read_bytes()).hexdigest()
            != bundle["files"]["static_bus_features.py"]
        ):
            raise BundleError("FEATURE_CODE_MISMATCH")
        self.manifest = json.loads((root / "serving_manifest.json").read_text(encoding="utf-8"))
        self.spec = json.loads((root / "input_spec.json").read_text(encoding="utf-8"))
        if (
            self.spec.get("feature_version") != VERSION
            or self.spec.get("features") != FEATURES
            or self.manifest.get("feature_version") != VERSION
            or self.manifest.get("production_ready") is not False
            or self.manifest.get("model_type") != "lightgbm"
            or self.manifest.get("target") != "hourly_mean_bus_segment_seconds"
            or self.manifest.get("approval") != "SHADOW"
            or any(
                not isinstance(self.manifest.get(k), str) or not self.manifest[k].strip()
                for k in ("model_version", "data_version", "master_version")
            )
        ):
            raise BundleError("STATIC_CONTRACT_MISMATCH")
        self.model = lgb.Booster(model_file=str(root / "model.txt"))
        if self.model.feature_name() != FEATURES:
            raise BundleError("FEATURE_ORDER_MISMATCH")
        edges = pd.read_parquet(root / "edges.parquet")
        if edges.duplicated(KEY).any() or edges.training_sample_count.le(0).any():
            raise BundleError("EDGE_EVIDENCE_INVALID")
        # 공식 topology 파일 자체가 있어도 현재 모델의 vocab/학습 근거와 맞아야 한다.
        for name in KEY[:3]:
            if not edges[name].isin(self.spec["vocab"][name]).all():
                raise BundleError("EDGE_VOCAB_MISMATCH")
        self.edges = {tuple(row[k] for k in KEY): row for row in edges.to_dict("records")}
        self.start = date.fromisoformat(self.manifest["supported_from"])
        self.end = date.fromisoformat(self.manifest["supported_until"])
        if self.start > self.end:
            raise BundleError("DATE_RANGE_INVALID")
        self.mode = "SHADOW"
        self.lock = threading.Lock()

    def metadata(self):
        """지원일은 달력/기술적 범위다. 미래 정확도나 실시간 혼잡 반영을 보장하지 않는다."""
        return {
            k: self.manifest[k]
            for k in (
                "model_version",
                "feature_version",
                "data_version",
                "master_version",
                "model_type",
                "target",
                "production_ready",
                "supported_from",
                "supported_until",
            )
        }

    def predict(self, request):
        """개별 edge 불일치는 UNAVAILABLE, 모델 전체 실패는 예외다. 온라인 파일 I/O는 없다.

        1. 날짜·인접 조합 검증 → 2. 고정 전처리·일괄 추론 → 3. 관측 수/시각과 초 반환.
        유효시각 5분은 응답 재사용 한도이며 모델이 실시간 상태를 수집했다는 뜻이 아니다.
        """
        if not self.lock.acquire(blocking=False):
            raise BundleError("MODEL_BUSY")
        try:
            results, accepted = [], []
            for row in request.segments:
                key = (
                    row.provider_route_id,
                    row.provider_from_stop_id,
                    row.provider_to_stop_id,
                    row.from_stop_sequence,
                )
                reason = (
                    "UNSUPPORTED_PROVIDER"
                    if row.provider_namespace != "SEOUL_BUS"
                    else "DATE_UNSUPPORTED"
                    if not self.start <= row.service_date <= self.end
                    else "EDGE_NOT_VERIFIED"
                    if key not in self.edges
                    else None
                )
                result = dict(segment_key=row.segment_key, status="UNAVAILABLE", reason=reason, prediction=None)
                results.append(result)
                if reason is None:
                    accepted.append((row, self.edges[key], result))
            if accepted:
                frame = pd.DataFrame([row.model_dump() for row, _, _ in accepted])
                predictions = self.model.predict(matrix(frame, self.spec), num_threads=2)
                for (_, evidence, result), seconds in zip(accepted, predictions, strict=True):
                    if not np.isfinite(seconds) or not 0 < seconds <= 86400:
                        result["reason"] = "INVALID_DURATION"
                        continue
                    result.update(
                        status="PREVIEW",
                        reason="SHADOW_ONLY",
                        prediction=dict(
                            mean_seconds=float(seconds),
                            training_ids_verified=True,
                            training_sample_count=int(evidence["training_sample_count"]),
                            observed_from=str(evidence["observed_from"].date()),
                            observed_until=str(evidence["observed_until"].date()),
                            missing_feature_count=0,
                            missing_features=[],
                            unseen_id_count=0,
                            data_insufficient=False,
                        ),
                    )
            now = datetime.now(UTC)
            return dict(
                self.metadata(),
                request_id=request.request_id,
                approval=self.mode,
                computed_at=now.isoformat(),
                valid_until=(now + timedelta(minutes=5)).isoformat(),
                results=results,
            )
        finally:
            self.lock.release()
