"""데이터 없는 서비스를 정상으로 오인하거나 배치 입력이 조용히 변형되는 장애를 방어한다."""

import hashlib
import json
import os
import sys
from pathlib import Path
from threading import Lock
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import pandas as pd
import pytest
from app import create_app
from fastapi.testclient import TestClient
from predictor import BundleError, Predictor, checked_path
from pydantic import ValidationError
from schemas import BatchRequest


def payload(**changes):
    """서울 날짜와 선행 0을 가진 provider ID로 경계 입력을 만든다."""
    row = {
        "segment_key": "edge-1",
        "provider_route_id": "001",
        "provider_from_stop_id": "002",
        "provider_to_stop_id": "003",
        "from_stop_sequence": 1,
        "service_date": "2026-09-10",
        "hour_slot": 9,
    }
    row.update(changes)
    return {"request_id": "test", "segments": [row]}


def test_missing_model_reports_unready(tmp_path, monkeypatch):
    monkeypatch.setenv("ML_BUNDLE_DIR", str(tmp_path / "absent"))
    with TestClient(create_app()) as client:
        assert client.get("/health/live").status_code == 200
        assert client.get("/health/ready").status_code == 503
        assert client.post("/v1/bus/predictions:batch", json=payload()).status_code == 503


@pytest.mark.parametrize(
    "changes",
    [
        {"hour_slot": 24},
        {"hour_slot": True},
        {"from_stop_sequence": -1},
        {"provider_route_id": 1},
        {"service_date": 1788998400},
        {"service_date": "2026-09-10T00:00:00+09:00"},
        {"service_date": "2026-02-30"},
    ],
)
def test_invalid_identity_time_is_rejected(changes):
    with pytest.raises(ValidationError):
        BatchRequest.model_validate(payload(**changes))


def test_duplicate_key_is_not_silently_overwritten():
    data = payload()
    data["segments"] *= 2
    with pytest.raises(ValidationError):
        BatchRequest.model_validate(data)


def test_leading_zero_is_preserved():
    assert BatchRequest.model_validate(payload()).segments[0].provider_route_id == "001"


def test_published_openapi_matches_runtime():
    """Backend와 인프라가 읽는 계약이 현재 HTTP 구현과 다르게 남지 않도록 확인한다."""
    path = Path(__file__).resolve().parents[2] / "docs/contracts/ml-serving.openapi.json"
    assert json.loads(path.read_text(encoding="utf-8")) == create_app().openapi()


def test_validation_response_does_not_echo_input():
    with TestClient(create_app(predictor=object())) as client:
        response = client.post("/v1/bus/predictions:batch", json=payload(provider_route_id="secret-marker"))
        assert response.status_code == 422
        assert "secret-marker" not in response.text


def test_http_response_digest_uses_exact_request_bytes():
    """운영 계약의 input_digest는 파싱 후 JSON이 아니라 Backend가 보낸 원문 바이트와 같아야 한다."""

    class Preview:
        def predict(self, request, timings=None):
            return {
                "request_id": request.request_id,
                "model_version": "test",
                "model_type": "lightgbm",
                "target": "hourly_mean_bus_segment_seconds",
                "production_ready": False,
                "bundle_production_ready": False,
                "feature_version": None,
                "features_as_of": None,
                "supported_from": "2026-09-10",
                "supported_until": "2026-09-30",
                "computed_at": "2026-09-21T12:00:00+09:00",
                "valid_until": "2026-09-21T12:05:00+09:00",
                "results": [
                    {
                        "segment_key": request.segments[0].segment_key,
                        "status": "PREVIEW",
                        "reason": "MODEL_NOT_PRODUCTION_READY",
                        "prediction": {
                            "mean_seconds": 30.0,
                            "missing_feature_count": 0,
                            "missing_features": [],
                            "unseen_id_count": 0,
                            "history_sample_count": 8,
                            "data_insufficient": False,
                            "training_ids_verified": False,
                        },
                    }
                ],
            }

    raw = json.dumps(payload(), ensure_ascii=False, separators=(", ", ": ")).encode()
    with TestClient(create_app(predictor=Preview())) as client:
        response = client.post("/v1/bus/predictions:batch", content=raw, headers={"Content-Type": "application/json"})
    assert response.status_code == 200
    assert response.json()["input_digest"] == hashlib.sha256(raw).hexdigest()


def test_bundle_path_must_stay_inside(tmp_path):
    with pytest.raises(BundleError):
        checked_path(tmp_path, "../outside.joblib")


def test_corrupted_bundle_is_rejected_before_loading_pickle(tmp_path):
    """복사 중 잘린 모델을 joblib에 넘기기 전에 SHA 검사에서 멈춘다."""
    files = {
        name: "0" * 64
        for name in [
            "selected_model.joblib",
            "input_spec.json",
            "serving_manifest.json",
            "portable_features.py",
            "references/contexts/coordinates.parquet",
        ]
    }
    (tmp_path / "bundle.json").write_text(json.dumps({"files": files}))
    (tmp_path / "serving_manifest.json").write_text("{}")
    (tmp_path / "selected_model.joblib").write_bytes(b"incomplete")
    with pytest.raises(BundleError, match="BUNDLE_CHECKSUM_MISMATCH"):
        Predictor(tmp_path)


def test_busy_model_is_503():
    class Busy:
        def predict(self, request, timings=None):
            raise BundleError("MODEL_BUSY")

    with TestClient(create_app(predictor=Busy())) as client:
        response = client.post("/v1/bus/predictions:batch", json=payload())
        assert response.status_code == 503
        assert response.json()["detail"] == "MODEL_BUSY"


def test_partial_batch_preserves_order_and_reports_missing():
    """한 미지원 출처가 다른 행을 지우지 않고 이력 0을 충분한 데이터로 표시하지 않는다."""
    model = Predictor.__new__(Predictor)
    model.lock = Lock()
    model.bundle = {"supported_from": "2026-09-10", "supported_until": "2026-09-17"}
    model.manifest = {"version": "test-only", "model": "fake", "target": "test", "production_ready": False}
    cats = ["provider_route_id", "provider_from_stop_id", "provider_to_stop_id"]
    model.spec = {"cats": cats, "numeric": ["history_count_8obs", "history_recent_seconds"]}
    model.vocab = dict(zip(cats, [{"001"}, {"002"}, {"003"}], strict=True))
    model.reference = Path(".")
    model.features = SimpleNamespace(
        KEY=cats + ["from_stop_sequence"],
        attach_features=lambda frame, _: frame.assign(history_count_8obs=0, history_recent_seconds=float("nan")),
        matrix=lambda frame, _: pd.DataFrame({"x": [1] * len(frame)}),
    )
    model.model = SimpleNamespace(feature_name_=["x"], predict=lambda frame: [30.0] * len(frame))
    data = payload()
    data["segments"].append({**data["segments"][0], "segment_key": "edge-2", "provider_namespace": "GBIS"})
    result = model.predict(BatchRequest.model_validate(data))
    assert [row["segment_key"] for row in result["results"]] == ["edge-1", "edge-2"]
    assert result["results"][0]["prediction"]["mean_seconds"] == 30
    assert result["results"][0]["prediction"]["data_insufficient"] is True
    assert result["results"][0]["prediction"]["missing_feature_count"] == 1
    assert result["results"][1]["reason"] == "UNSUPPORTED_PROVIDER"
    assert result["results"][1]["prediction"] is None
    assert result["production_ready"] is False


def test_real_server_parity():
    """자체 다운로드 모델이 있을 때만 실행한다. 모형 시험을 실모델 통과로 대체하지 않는다."""
    configured = os.environ.get("ML_TEST_BUNDLE_DIR")
    root = Path(configured) if configured else Path(__file__).resolve().parents[2] / "artifacts/ml-serving/20260908-v12"
    if not configured and not root.exists():
        pytest.skip("GPU 서버 모델·참조·golden 자료 미설치")
    golden = json.loads((Path(__file__).resolve().parents[1] / "examples/v12-server-golden.json").read_text())
    assert any(count == 0 for count in golden["unseen_id_counts"]), "실제 수치 비교 표본이 필요하다"
    model = Predictor(root)
    request = BatchRequest.model_validate(
        {
            "request_id": "server-parity",
            "segments": [{"segment_key": str(i), **row} for i, row in enumerate(golden["requests"])],
        }
    )
    result = model.predict(request)
    # 반복 캐시와 변경 전 reader를 모두 비교한다. 수치뿐 아니라 결측·표본·순서도 같아야 한다.
    assert model.predict(request)["results"] == result["results"]
    import pyarrow.parquet as original_pq

    model.features.pd, model.features.pq = pd, original_pq
    assert model.predict(request)["results"] == result["results"]
    for i, row in enumerate(result["results"]):
        if golden["unseen_id_counts"][i]:
            assert row["reason"] == "UNSEEN_PROVIDER_ID"
        else:
            assert row["prediction"]["mean_seconds"] == pytest.approx(golden["predictions"][i], abs=1e-5)
            assert row["prediction"]["missing_feature_count"] == golden["missing_feature_counts"][i]
