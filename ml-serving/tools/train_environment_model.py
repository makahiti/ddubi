"""공통 전처리의 train/test JSONL에서 별도 환경 모델을 GPU 0으로 학습·미래 검증한다.

준비된 날짜 7:3 분할을 재검증하고 test를 모델 선택에 사용하지 않는다. 이미 정한 설정으로
train 전체를 학습한 후 미래 test MAE/RMSE/훈련 중앙값 baseline을 보고한다. 재학습 완료나
productionReady를 사전 가정하지 않는다. GPU 실행 실패는 CPU로 몰래 바꾸지 않고 실패한다.
소규모 검증 입력 상한은 100,000행이며 대규모 기존 v12 전체를 메모리에 읽지 않는다.
"""

import argparse
import hashlib
import json
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from environment_features import VERSION, instant
from environment_labels import LABEL_VERSION, quality_report, validate_label
from environment_model import FEATURES, vector


def check(train, test):
    """중복 정답·미래 공개 정답·목표 혼합·특성 버전 변경은 모델 학습 전에 거절한다."""
    if not train or not test:
        raise ValueError("train and test required")
    cutoff = min(instant(r["asOf"]) for r in test)
    if max(instant(r["asOf"]).date() for r in train) >= cutoff.date():
        raise ValueError("date split overlap")
    ids = set()
    targets = set()
    for r in train + test:
        label = r["label"]
        validate_label(r, label)
        targets.add(label["kind"])
        for provenance in r.get("provenance", {}).values():
            if any(instant(provenance[k]) > instant(r["asOf"]) for k in ("publishedAt", "collectedAt")):
                raise ValueError("future feature leakage")
        if r["featureVersion"] != VERSION or label.get("verified") is not True:
            raise ValueError("unverified row")
        identity = (label["source"], label["eventId"])
        if identity in ids:
            raise ValueError("duplicate label")
        ids.add(identity)
        seconds = label["seconds"]
        if (
            isinstance(seconds, bool)
            or not isinstance(seconds, (int, float))
            or not math.isfinite(seconds)
            or seconds <= 0
        ):
            raise ValueError("invalid seconds")
        if instant(label["completedAt"]) <= instant(r["asOf"]) or instant(label["availableAt"]) < instant(
            label["completedAt"]
        ):
            raise ValueError("label time invalid")
    if len(targets) != 1 or next(iter(targets)) not in (
        "ACTUAL_TRAVERSAL",
        "HOURLY_MEAN_BUS_SEGMENT",
    ):
        raise ValueError("target mismatch")
    if any(instant(r["label"]["availableAt"]) >= cutoff for r in train):
        raise ValueError("late training label")
    if not any(any(v is not None for v in r["features"].values()) for r in train):
        raise ValueError("environment histories required")
    return next(iter(targets))


def main():
    parser = argparse.ArgumentParser()
    for name in ("prepared", "output", "version"):
        parser.add_argument("--" + name, required=True)
    a = parser.parse_args()

    def read(name):
        rows = []
        with (Path(a.prepared) / (name + ".jsonl")).open(encoding="utf-8") as f:
            for line in f:
                if len(rows) >= 100000:
                    raise ValueError("bounded training limit exceeded")
                if line.strip():
                    rows.append(json.loads(line))
        return rows

    manifest = json.loads((Path(a.prepared) / "manifest.json").read_text(encoding="utf-8"))
    for name in ("train", "test"):
        if hashlib.sha256((Path(a.prepared) / (name + ".jsonl")).read_bytes()).hexdigest() != manifest.get(
            "sha256", {}
        ).get(name):
            raise ValueError("prepared SHA mismatch")
    if manifest.get("labelVersion") != LABEL_VERSION:
        raise ValueError("prepared label version mismatch; prepare again")
    train, test = read("train"), read("test")
    target = check(train, test)
    import lightgbm as lgb
    import numpy as np

    categories = {k: i for i, k in enumerate(sorted({r["masterVersion"] + "|" + r["segmentId"] for r in train}))}
    usable = [r for r in test if r["masterVersion"] + "|" + r["segmentId"] in categories]
    if not usable:
        raise ValueError("no comparable future labels")
    x = np.asarray([vector(r, categories) for r in train])
    y = np.asarray([r["label"]["seconds"] for r in train])
    model = lgb.train(
        dict(
            objective="regression",
            device_type="cuda",
            gpu_device_id=0,
            num_threads=16,
            seed=42,
            learning_rate=0.05,
            num_leaves=31,
            verbosity=-1,
        ),
        lgb.Dataset(x, label=y, feature_name=FEATURES, categorical_feature=["segmentCode"]),
        num_boost_round=300,
    )
    predictions = model.predict(np.asarray([vector(r, categories) for r in usable]))
    actual = np.asarray([r["label"]["seconds"] for r in usable])
    if not np.isfinite(predictions).all() or not (predictions > 0).all():
        raise ValueError("invalid model predictions")
    encoded = model.model_to_string().encode("utf-8")
    manifest = dict(
        featureVersion=VERSION,
        labelVersion=LABEL_VERSION,
        preparedSha256=manifest["sha256"],
        quality=quality_report(dict(train=train, test=test)),
        features=FEATURES,
        modelVersion=a.version,
        target=target,
        modelSha256=hashlib.sha256(encoded).hexdigest(),
        categories=categories,
        productionReady=False,
        rows=dict(
            train=len(train),
            test=len(test),
            evaluated=len(usable),
            unseen=len(test) - len(usable),
        ),
        metrics=dict(
            maeSeconds=float(np.mean(abs(predictions - actual))),
            rmseSeconds=float(np.sqrt(np.mean((predictions - actual) ** 2))),
            trainingMedianBaselineMaeSeconds=float(np.mean(abs(np.median(y) - actual))),
        ),
    )
    out = Path(a.output)
    out.mkdir(parents=True, exist_ok=False)
    (out / "model.txt").write_bytes(encoded)
    (out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: v for k, v in manifest.items() if k != "categories"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
