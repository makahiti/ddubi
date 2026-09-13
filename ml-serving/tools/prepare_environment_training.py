"""정답과 예측시점 정보를 분리한 JSONL을 시간순 학습/테스트 자료로 준비한다.

소규모 검증/분할 manifest 생성용이다. 대용량 원천 전체를 여기에 입력하지 않는다.
입력 최대 행 수를 명시하고 날짜 단위 7:3 분할한다. 테스트 날짜의 정답이 학습 기간에
들어가거나 학습 cutoff 이후에 알려진 정답을 쓰지 않는다. 모델 학습 완료를 주장하지 않는다.
"""

import argparse
import json
import hashlib
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from environment_features import prepare, instant
from environment_labels import validate_label, quality_report, LABEL_VERSION


def build(samples, observations):
    """검증된 실제 구간 시간 또는 명시된 시간대 평균만 정답으로 받으며 두 목표를 혼합하지 않는다."""
    if not samples:
        raise ValueError("verified labels required")
    rows = []
    kinds = set()
    identities = set()
    # 전체 이력을 매 정답마다 순회하지 않도록 마스터/구간으로 먼저 좁힌다.
    observation_index = {}
    for item in observations:
        observation_index.setdefault(
            (item.get("masterVersion"), item.get("segmentId")), []
        ).append(item)
    for sample in samples:
        request, label = sample["request"], sample["label"]
        kind = validate_label(request, label)
        kinds.add(kind)
        identity = (label["source"], label["eventId"])
        if identity in identities:
            raise ValueError("duplicate label")
        identities.add(identity)
        row = prepare(
            request,
            observation_index.get((request["masterVersion"], request["segmentId"]), []),
        )
        if row["conflictingKinds"]:
            raise ValueError("conflicting feature evidence")
        row["label"] = label
        rows.append(row)
    if len(kinds) != 1:
        raise ValueError("mixed label targets")
    days = sorted({instant(r["asOf"]).date() for r in rows})
    if len(days) < 2:
        raise ValueError("at least two dates required")
    # 날짜를 자르지 않는 후보 중 실제 행 수 70%에 가장 가까운 경계를 선택한다.
    counts = {day: 0 for day in days}
    for row in rows:
        counts[instant(row["asOf"]).date()] += 1
    boundaries = []
    cumulative = 0
    for day in days[:-1]:
        cumulative += counts[day]
        boundaries.append(
            (abs(cumulative - len(rows) * 0.7), days[days.index(day) + 1])
        )
    boundary = min(boundaries)[1]
    first_test = min(
        instant(r["asOf"]) for r in rows if instant(r["asOf"]).date() >= boundary
    )
    train, test, withheld = [], [], []
    for row in sorted(
        rows, key=lambda r: (instant(r["asOf"]), r["segmentId"], r["label"]["eventId"])
    ):
        if instant(row["asOf"]).date() >= boundary:
            test.append(row)
        elif instant(row["label"]["availableAt"]) >= first_test:
            withheld.append(row)
        else:
            train.append(row)
    if not train or not test:
        raise ValueError("usable train and test required")
    return dict(train=train, test=test, withheld=withheld)


def main():
    p = argparse.ArgumentParser()
    for name in ("samples", "observations", "output"):
        p.add_argument("--" + name, required=True)
    p.add_argument("--max-rows", type=int, default=100000)
    a = p.parse_args()
    if a.max_rows < 1:
        raise ValueError("positive max rows required")

    def read(path):
        rows = []
        with open(path, encoding="utf-8") as f:
            for line in f:
                if len(rows) >= a.max_rows:
                    raise ValueError("row limit exceeded; use partitioned job")
                if line.strip():
                    rows.append(json.loads(line))
        return rows

    result = build(read(a.samples), read(a.observations))
    out = Path(a.output)
    out.mkdir(parents=True, exist_ok=False)
    for split, rows in result.items():
        with (out / (split + ".jsonl")).open("w", encoding="utf-8") as f:
            for row in rows:
                f.write(json.dumps(row, ensure_ascii=False) + "\n")
    (out / "manifest.json").write_text(
        json.dumps(
            dict(
                featureVersion="environment-asof-v1",
                labelVersion=LABEL_VERSION,
                quality=quality_report(result),
                rows={k: len(v) for k, v in result.items()},
                sha256={
                    k: hashlib.sha256((out / (k + ".jsonl")).read_bytes()).hexdigest()
                    for k in result
                },
                productionReady=False,
            ),
            indent=2,
        ),
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
