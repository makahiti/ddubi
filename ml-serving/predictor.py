"""검증된 로컬 v12 묶음으로 CPU 예측을 수행한다. Redis/MySQL 쓰기는 없다.

프로세스 시작 시 모델·규격·참조 SHA256을 검증하고 모델을 한 번 로드한다.
학습 원문에서 분리한 특성 생성/인코더를 재사용한다. 미지원 날짜·출처·ID는 개별
UNAVAILABLE, 파일 오류는 배치 실패다. 원래 bundle의 미승인 상태를 유지하며,
수치 확인용 예측을 운영 추천에 사용 가능한 값으로 승격하지 않는다.
"""

import hashlib
import importlib.metadata
import importlib.util
import json
import time
from datetime import datetime, timedelta
from pathlib import Path
from threading import Lock
from zoneinfo import ZoneInfo

import joblib
import numpy as np
import pandas as pd
from reference_cache import ReferenceCache
from schemas import BatchRequest
from threadpoolctl import threadpool_limits

SEOUL = ZoneInfo("Asia/Seoul")


class BundleError(RuntimeError):
    """모델/참조 묶음 누락·불일치. 외부 응답에 파일 경로나 원본 예외를 노출하지 않는다."""


def checked_path(root, relative):
    """manifest가 지정한 파일도 묶음 디렉터리 안에 있을 때만 읽는다."""
    path = (root / relative).resolve()
    if not path.is_relative_to(root.resolve()):
        raise BundleError("BUNDLE_PATH_INVALID")
    return path


class Predictor:
    """한 버전의 모델과 참조를 소유하는 읽기 전용 추론 객체.

    요청 중 모델 교체나 통계 재학습은 하지 않는다. 모델 변경 시 검증 후 재시작한다.
    HTTP 대기열을 통과한 계산 한 개만 실행한다. 직접 호출도 락으로 보호한다.
    자체 산출물만 로드해야 하며 SHA는 파일 일관성 검사이지 외부 파일의 신뢰 인증이 아니다.
    """

    def __init__(self, bundle_dir, *, cache_mib=256):
        self.root = Path(bundle_dir).resolve()
        self.lock = Lock()
        self.bundle = json.loads((self.root / "bundle.json").read_text(encoding="utf-8"))
        self.manifest = json.loads((self.root / "serving_manifest.json").read_text(encoding="utf-8"))
        # 1. 추론에 쓰는 파일을 모두 같은 버전으로 고정한다. 부분 복사본은 준비 완료가 아니다.
        files = self.bundle["files"]
        required = {"selected_model.joblib", "input_spec.json", "serving_manifest.json", "portable_features.py"}
        if not required.issubset(files) or not any(n.startswith("references/") for n in files):
            raise BundleError("BUNDLE_INCOMPLETE")
        for relative, expected in files.items():
            with checked_path(self.root, relative).open("rb") as handle:
                actual = hashlib.file_digest(handle, "sha256").hexdigest()
            if actual != expected:
                raise BundleError("BUNDLE_CHECKSUM_MISMATCH")
        self.spec = json.loads((self.root / "input_spec.json").read_text(encoding="utf-8"))
        if self.spec != self.manifest["input_spec"]:
            raise BundleError("INPUT_SPEC_MISMATCH")
        if self.manifest["model"] != "lightgbm" or self.manifest["target"] != "hourly_mean_bus_segment_seconds":
            raise BundleError("MODEL_TARGET_UNSUPPORTED")
        if self.manifest["version"] != self.bundle["version"]:
            raise BundleError("MODEL_VERSION_MISMATCH")
        for package, expected in self.bundle["runtime_versions"].items():
            if importlib.metadata.version(package) != expected:
                raise BundleError("MODEL_RUNTIME_VERSION_MISMATCH")
        # 2. 학습 진입점을 import하지 않는다. SHA 확인된 자체 추출 함수만 로드한다.
        spec = importlib.util.spec_from_file_location("ddubi_v12_features", self.root / "portable_features.py")
        self.features = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.features)
        self.model = joblib.load(self.root / "selected_model.joblib")
        self.model.set_params(n_jobs=2)
        self.vocab = {name: set(values) for name, values in self.spec["vocab"].items()}
        # 운영 승인 번들은 승인 boolean 하나만으로 열지 않는다. Backend가 검증하는 버전·관측시각·ID 검증
        # 근거까지 manifest에 함께 있어야 시작할 수 있다. 현재 v12는 미승인이므로 이 경로를 타지 않는다.
        self.production_ready = bool(self.manifest.get("production_ready", False))
        self.feature_version = self.manifest.get("feature_version") or self.spec.get("feature_version")
        self.features_as_of = self.manifest.get("features_as_of")
        self.training_ids_verified = self.manifest.get("training_ids_verified") is True
        if self.production_ready:
            if not isinstance(self.feature_version, str) or not self.feature_version.strip():
                raise BundleError("PRODUCTION_FEATURE_VERSION_MISSING")
            if not isinstance(self.features_as_of, str) or not self.features_as_of.strip():
                raise BundleError("PRODUCTION_FEATURE_TIMESTAMP_MISSING")
            try:
                parsed_features_at = datetime.fromisoformat(self.features_as_of.replace("Z", "+00:00"))
            except ValueError as error:
                raise BundleError("PRODUCTION_FEATURE_TIMESTAMP_INVALID") from error
            if (
                parsed_features_at.tzinfo is None
                or parsed_features_at > datetime.now(parsed_features_at.tzinfo)
                or not self.training_ids_verified
            ):
                raise BundleError("PRODUCTION_EVIDENCE_INCOMPLETE")
        # 3. 검증된 portable 함수의 파일 읽기만 캐시한다. 모델·계산식·묶음 SHA는 변경하지 않는다.
        # 캐시는 지연 로딩하며 새 버전은 새 Predictor/프로세스로만 반영한다.
        self.reference = self.root / "references"
        self.reference_cache = ReferenceCache(
            [checked_path(self.root, name) for name in files if name.startswith("references/")], cache_mib * 1024 * 1024
        )
        self.reference_cache.bind(self.features)

    def metadata(self):
        """수치 확인용 서비스 준비 상태와 지원 날짜를 반환한다. 운영 승인과 구분한다."""
        production_ready = getattr(self, "production_ready", bool(self.manifest.get("production_ready", False)))
        return {
            "model_version": self.manifest["version"],
            "model_type": self.manifest["model"],
            "target": self.manifest["target"],
            "production_ready": production_ready,
            "bundle_production_ready": bool(self.manifest.get("production_ready", False)),
            "feature_version": getattr(self, "feature_version", self.spec.get("feature_version")),
            "features_as_of": getattr(self, "features_as_of", self.manifest.get("features_as_of")),
            "supported_from": self.bundle["supported_from"],
            "supported_until": self.bundle["supported_until"],
        }

    def predict(self, request: BatchRequest, timings=None):
        """배치 순서와 상관키를 보존하며 평균 구간 초와 실제 결측·이력 표본 수를 반환한다.

        같은 입력은 같은 수치를 반환한다. 계산시각만 달라진다. 예측에 사용한 과거 통계의
        표본 수는 같은 시간대의 77~126일 전 관측 개수이며 모델 학습 행 수가 아니다.
        학습 vocab 존재만으로 전체 구간 topology가 검증됐다고 주장하지 않는다.
        """
        # 단계별 시간만 기록한다. 예측값·모델 묶음은 바꾸지 않는다(2026-09-18 관측 추가).
        if timings is not None:
            timings["started_at"] = time.perf_counter()
            timings["segments"] = len(request.segments)
        if not self.lock.acquire(blocking=False):
            raise BundleError("MODEL_BUSY")
        try:
            return self._predict_locked(request, timings)
        finally:
            self.lock.release()

    def _predict_locked(self, request, timings=None):
        # 1. 입력 출처·날짜·vocab을 검사한다. 미지원 행이 유효한 다른 행을 지우지 않는다.
        results = []
        supported = []
        positions = []
        for segment in request.segments:
            row = segment.model_dump(mode="json")
            reason = None
            if segment.provider_namespace != "SEOUL_BUS":
                reason = "UNSUPPORTED_PROVIDER"
            elif not self.bundle["supported_from"] <= row["service_date"] <= self.bundle["supported_until"]:
                reason = "REFERENCE_DATE_UNSUPPORTED"
            elif any(row[name] not in self.vocab[name] for name in self.spec["cats"]):
                reason = "UNSEEN_PROVIDER_ID"
            results.append(
                {"segment_key": segment.segment_key, "status": "UNAVAILABLE", "reason": reason, "prediction": None}
            )
            if reason is None:
                supported.append({name: row[name] for name in [*self.features.KEY, "service_date", "hour_slot"]})
                positions.append(len(results) - 1)
        if supported:
            # 2. 서버 원본과 같은 전처리·순서·dtype로 행렬을 만든 뒤 한 번에 예측한다.
            frame = pd.DataFrame(supported)
            frame["service_date"] = pd.to_datetime(frame.service_date)
            feature_start = time.perf_counter()
            features = self.features.attach_features(frame, self.reference)
            matrix = self.features.matrix(features, self.spec)
            if timings is not None:
                timings["feature_seconds"] = time.perf_counter() - feature_start
            if list(matrix.columns) != list(self.model.feature_name_):
                raise BundleError("MODEL_FEATURE_ORDER_MISMATCH")
            inference_start = time.perf_counter()
            with threadpool_limits(limits=2):
                values = np.maximum(0, self.model.predict(matrix))
            if timings is not None:
                timings["inference_seconds"] = time.perf_counter() - inference_start
            if len(values) != len(supported) or not np.isfinite(values).all():
                raise BundleError("MODEL_OUTPUT_INVALID")
            # 3. 결측 대체를 성공적인 관측으로 표시하지 않는다. 운영 상태는 manifest의 완전한 승인
            # 증거를 통과한 경우에만 사용하며, 현재 v12는 계속 PREVIEW다.
            for i, position in enumerate(positions):
                missing = [name for name in self.spec["numeric"] if pd.isna(features.iloc[i].get(name, np.nan))]
                count = int(features.iloc[i]["history_count_8obs"])
                seconds = float(values[i])
                if not 0 < seconds <= 86400:
                    results[position]["reason"] = "INVALID_DURATION"
                    continue
                results[position].update(
                    status="PRODUCTION" if getattr(self, "production_ready", False) else "PREVIEW",
                    reason=None if getattr(self, "production_ready", False) else "MODEL_NOT_PRODUCTION_READY",
                    prediction={
                        "mean_seconds": seconds,
                        "missing_feature_count": len(missing),
                        "missing_features": missing,
                        "unseen_id_count": 0,
                        "history_sample_count": count,
                        "data_insufficient": count == 0,
                        "training_ids_verified": getattr(self, "training_ids_verified", False),
                    },
                )
        computed_at = datetime.now(SEOUL)
        return {
            "request_id": request.request_id,
            **self.metadata(),
            "computed_at": computed_at.isoformat(),
            "valid_until": (computed_at + timedelta(minutes=5)).isoformat(),
            "results": results,
        }
