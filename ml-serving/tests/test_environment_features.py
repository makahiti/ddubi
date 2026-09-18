"""사후 공개 자료 누출·사고 0건 오인·훈련/서빙 차이를 손계산 fixture로 검증한다."""

import sys
from pathlib import Path
from copy import deepcopy
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from environment_features import prepare
from tools.prepare_environment_training import build


def request(day=10):
    return dict(
        segmentId="BUS:1:2:3",
        masterVersion="v6",
        asOf=f"2026-09-{day:02d}T09:00:00+09:00",
        passageAt=f"2026-09-{day:02d}T09:10:00+09:00",
    )


def obs(**changes):
    item = dict(
        **{k: v for k, v in request().items() if k in ("segmentId", "masterVersion")},
        kind="weather",
        eventId="w1",
        source="fixture",
        observedAt="2026-09-10T08:30:00+09:00",
        publishedAt="2026-09-10T08:40:00+09:00",
        collectedAt="2026-09-10T08:41:00+09:00",
        validFrom="2026-09-10T08:30:00+09:00",
        validUntil="2026-09-10T10:00:00+09:00",
        values={"temperatureC": 20},
    )
    item.update(changes)
    return item


def test_future_publication_collection_or_version_not_used():
    result = prepare(
        request(),
        [
            obs(),
            obs(
                publishedAt="2026-09-10T09:01:00+09:00",
                collectedAt="2026-09-10T09:02:00+09:00",
                values={"temperatureC": 99},
            ),
            obs(masterVersion="old", values={"temperatureC": 88}),
        ],
    )
    assert result["features"]["temperatureC"] == 20
    assert result["features"]["accidentCount"] is None


def test_zero_requires_complete_feed_and_conflicts_are_missing():
    event = obs(kind="events", values={"accidentCount": 0})
    assert prepare(request(), [event])["features"]["accidentCount"] is None
    event["feedComplete"] = True
    assert prepare(request(), [event])["features"]["accidentCount"] == 0
    out = prepare(request(), [obs(), obs(values={"temperatureC": 21})])
    assert (
        out["conflictingKinds"] == ["weather"]
        and out["features"]["temperatureC"] is None
    )


def test_training_uses_serving_features_and_holds_late_labels():
    samples = []
    for day in range(1, 11):
        samples.append(
            dict(
                request=request(day),
                label=dict(
                    kind="ACTUAL_TRAVERSAL",
                    verified=True,
                    source="fixture",
                    eventId=str(day),
                    seconds=100,
                    startedAt=f"2026-09-{day:02d}T09:09:20+09:00",
                    completedAt=f"2026-09-{day:02d}T09:11:00+09:00",
                    availableAt=f"2026-09-{day:02d}T09:12:00+09:00",
                ),
            )
        )
    samples[0]["label"]["availableAt"] = "2026-09-09T09:12:00+09:00"
    result = build(samples, [obs()])
    assert [len(result[k]) for k in ("train", "test", "withheld")] == [6, 3, 1]
    assert (
        result["test"][-1]["features"]
        == prepare(samples[-1]["request"], [obs()])["features"]
    )
    samples[0]["label"]["kind"] = "ETA"
    with pytest.raises(ValueError, match="prediction is not label"):
        build(samples, [])


def test_invalid_values_and_future_actual_are_missing():
    assert (
        prepare(
            request(),
            [obs(values={"temperatureC": float("nan"), "humidityPercent": 101})],
        )["features"]["temperatureC"]
        is None
    )
    out = prepare(request(), [obs(observedAt="2026-09-10T09:10:00+09:00")])
    assert out["rejectedObservations"] == 1
    assert prepare(request(), [obs(values=[])])["rejectedObservations"] == 1


def test_http_preparation_matches_training_and_missing_model_is_503(
    monkeypatch, tmp_path
):
    from app import create_app
    from fastapi.testclient import TestClient

    monkeypatch.setenv("ML_BUNDLE_DIR", str(tmp_path / "missing"))
    monkeypatch.delenv("ML_ENVIRONMENT_BUNDLE_DIR", raising=False)
    with TestClient(create_app()) as client:
        payload = dict(request=request(), observations=[obs()])
        response = client.post("/v1/bus/environment:prepare", json=payload)
        assert response.status_code == 200
        assert response.json()["features"] == prepare(request(), [obs()])["features"]
        assert (
            client.post("/v1/bus/environment:predict", json=payload).status_code == 503
        )
        payload["label"] = {"seconds": 100}
        assert (
            client.post("/v1/bus/environment:prepare", json=payload).status_code == 422
        )


def test_training_checks_reject_date_overlap_and_missing_histories():
    from tools.train_environment_model import check

    sample = prepare(request(), [])
    sample["label"] = dict(
        kind="ACTUAL_TRAVERSAL",
        verified=True,
        source="fixture",
        eventId="1",
        seconds=100,
        startedAt="2026-09-10T09:09:20+09:00",
        completedAt="2026-09-10T09:11:00+09:00",
        availableAt="2026-09-10T09:12:00+09:00",
    )
    with pytest.raises(ValueError, match="date split overlap"):
        check([sample], [sample])


def test_preparation_cli_writes_verifiable_splits(tmp_path, monkeypatch):
    import json, hashlib
    from tools.prepare_environment_training import main
    from tools.train_environment_model import check

    samples = []
    observations = []
    for day in range(1, 11):
        samples.append(
            dict(
                request=request(day),
                label=dict(
                    kind="ACTUAL_TRAVERSAL",
                    verified=True,
                    source="fixture",
                    eventId=str(day),
                    seconds=100,
                    startedAt=f"2026-09-{day:02d}T09:09:20+09:00",
                    completedAt=f"2026-09-{day:02d}T09:11:00+09:00",
                    availableAt=f"2026-09-{day:02d}T09:12:00+09:00",
                ),
            )
        )
        row = obs()
        for k in (
            "observedAt",
            "publishedAt",
            "collectedAt",
            "validFrom",
            "validUntil",
        ):
            row[k] = row[k].replace("2026-09-10", f"2026-09-{day:02d}")
        observations.append(row)
    for name, rows in [("samples", samples), ("observations", observations)]:
        (tmp_path / (name + ".jsonl")).write_text(
            "".join(json.dumps(r) + "\n" for r in rows), encoding="utf-8"
        )
    output = tmp_path / "prepared"
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "prepare",
            "--samples",
            str(tmp_path / "samples.jsonl"),
            "--observations",
            str(tmp_path / "observations.jsonl"),
            "--output",
            str(output),
        ],
    )
    main()
    manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
    splits = {}
    for name in ("train", "test"):
        raw = (output / (name + ".jsonl")).read_bytes()
        assert manifest["sha256"][name] == hashlib.sha256(raw).hexdigest()
        splits[name] = [json.loads(line) for line in raw.decode().splitlines()]
    assert check(splits["train"], splits["test"]) == "ACTUAL_TRAVERSAL"


def test_missing_observation_provenance_cannot_enter_training_features():
    for field in ("source", "eventId"):
        bad = obs(**{field: ""})
        result = prepare(request(), [bad])
        assert result["features"]["temperatureC"] is None
        assert result["rejectedObservations"] == 1


def test_actual_label_requires_measured_start_and_consistent_duration():
    from environment_labels import validate_label

    label = dict(
        kind="ACTUAL_TRAVERSAL",
        verified=True,
        source="fixture",
        eventId="trip",
        seconds=100,
        startedAt="2026-09-10T09:10:00+09:00",
        completedAt="2026-09-10T09:11:40+09:00",
        availableAt="2026-09-10T09:12:00+09:00",
    )
    assert validate_label(request(), label) == "ACTUAL_TRAVERSAL"
    with pytest.raises(ValueError, match="duration differs"):
        validate_label(request(), dict(label, seconds=60))
    missing = dict(label)
    missing.pop("startedAt")
    with pytest.raises(ValueError, match="startedAt required"):
        validate_label(request(), missing)
    with pytest.raises(ValueError, match="interval invalid"):
        validate_label(request(), dict(label, startedAt="2026-09-10T08:59:00+09:00"))


def test_quality_report_exposes_missing_environment_instead_of_success():
    from environment_labels import quality_report

    row = prepare(request(), [])
    row["label"] = dict(source="fixture")
    report = quality_report({"train": [row], "test": []})
    assert report["train"]["allEnvironmentMissingRows"] == 1
    assert report["train"]["missingByFeature"]["accidentCount"] == 1
    assert report["test"]["asOfMin"] is None
