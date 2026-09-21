"""학습 완료 후 동일 행의 v12 비교·표본 수별 오차·재현 근거를 추가하고 ZIP을 만든다.

기존 모델이나 원천은 수정하지 않는다. 새 모델 파라미터를 test에 맞춰 바꾸지 않고,
이미 결정한 예측값끼리만 비교한다. ZIP에는 실제 정답 전체를 넣지 않고 서빙 묶음과
요약 근거만 포함한다. 학습 중이면 제한된 시간 동안 해당 실험 완료만 기다린다.
"""

import argparse
import importlib.metadata
import json
import shutil
import sys
import time
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from static_bus_features import KEY, matrix
from train_static_bus import metrics, sha, write


def main():
    """1. 완료·SHA 확인 → 2. 동일 키/정답 비교 → 3. 서빙 ZIP 배타 생성."""
    parser = argparse.ArgumentParser()
    for name in ("run", "old-predictions", "archive", "training-source"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--wait-seconds", type=int, default=0)
    args = parser.parse_args()
    run = Path(args.run)
    until = time.monotonic() + args.wait_seconds
    while True:
        try:
            ready = json.loads((run / "progress.json").read_text())["stage"] == "complete"
        except (FileNotFoundError, json.JSONDecodeError):
            ready = False
        if ready:
            break
        if time.monotonic() >= until:
            raise ValueError("TRAINING_NOT_COMPLETE")
        time.sleep(10)
    bundle = json.loads((run / "bundle.json").read_text())
    if any(sha(run / name) != digest for name, digest in bundle["files"].items()):
        raise ValueError("BUNDLE_SHA_MISMATCH")
    import pandas as pd

    new = pd.read_parquet(run / "test_predictions.parquet")
    old_files = sorted(Path(args.old_predictions).glob("*.parquet"))
    old = pd.concat([pd.read_parquet(f) for f in old_files], ignore_index=True)
    keys = ["service_date", *KEY, "hour_slot"]
    shared = new.merge(
        old[keys + ["target_seconds", "prediction_seconds"]], on=keys, suffixes=("_new", "_old"), validate="one_to_one"
    )
    if shared.empty or not shared.target_seconds_new.eq(shared.target_seconds_old).all():
        raise ValueError("COMPARISON_LABEL_MISMATCH")
    comparison = dict(
        same_row_count=len(shared),
        test_used_for_selection=False,
        v12=metrics(shared.target_seconds_old, shared.prediction_seconds_old),
        v13=metrics(shared.target_seconds_new, shared.prediction_seconds_new),
        historical_test_reused=True,
        old_files=[dict(path=str(f), sha256=sha(f)) for f in old_files],
    )
    edges = pd.read_parquet(run / "edges.parquet")
    grouped = new.merge(edges[KEY + ["training_sample_count"]], on=KEY, how="left", validate="many_to_one")
    grouped["bucket"] = (
        pd.cut(grouped.training_sample_count, [0, 9, 49, 199, float("inf")], labels=["1-9", "10-49", "50-199", "200+"])
        .astype("string")
        .fillna("NOT_CURRENT_VERIFIED_EDGE")
    )
    comparison["by_training_sample_count"] = {
        str(k): metrics(g.target_seconds, g.prediction_seconds) for k, g in grouped.groupby("bucket", observed=True)
    }
    write(run / "comparison_v12.json", comparison)
    # 모델 이동 후 같은 숫자가 나오는지 확인할 golden이며 교통 정확도의 정답이 아니다.
    import lightgbm as lgb

    manifest = json.loads((run / "serving_manifest.json").read_text())
    spec = json.loads((run / "input_spec.json").read_text())
    sample = edges.head(10)[KEY].copy()
    sample["service_date"] = manifest["supported_from"]
    sample["hour_slot"] = 9
    model = lgb.Booster(model_file=str(run / "model.txt"))
    write(
        run / "golden.json",
        dict(
            requests=sample.to_dict("records"), predictions=model.predict(matrix(sample, spec), num_threads=2).tolist()
        ),
    )
    shutil.copyfile(args.training_source, run / "training_source.py")
    write(
        run / "training_runtime.json",
        dict(
            device="cpu",
            cpu_max=16,
            gpu_attempt="CUDA illegal memory access; separate failed run retained",
            versions={n: importlib.metadata.version(n) for n in ("numpy", "pandas", "lightgbm", "pyarrow", "holidays")},
            training_source_sha256=sha(Path(args.training_source)),
        ),
    )
    for name in ("comparison_v12.json", "training_source.py", "training_runtime.json", "golden.json"):
        bundle["files"][name] = sha(run / name)
    write(run / "bundle.json", bundle)
    with zipfile.ZipFile(args.archive, "x", zipfile.ZIP_DEFLATED) as archive:
        for name in ["bundle.json", *bundle["files"]]:
            archive.write(run / name, name)
    print(
        json.dumps(
            dict(
                archive=args.archive,
                sha256=sha(Path(args.archive)),
                same_row_count=len(shared),
                v12=comparison["v12"],
                v13=comparison["v13"],
            )
        ),
        flush=True,
    )


if __name__ == "__main__":
    main()
