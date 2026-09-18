"""검토자가 명시한 방향·유효기간·도로 namespace 근거를 검증하고 운영 대응표를 생성한다.

원래 후보를 자동 승인하지 않는다. reviewed JSONL에는 전체 원본 edge와 roadLinks,
coverage, directionVerified, evidenceMethod/evidenceRef, validFrom/Until이 있어야 한다.
버스 원본 SHA 및 모든 occurrence 일치 검증 후에만 새 파일을 배타적으로 쓴다.
"""

import argparse
import hashlib
import json
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "sparkjobs"))
from common.road_impact import RoadImpactIndex


def promote(bus, bus_sha, reviewed, reviewed_sha, version, output):
    """검토 입력 자체의 SHA도 고정한다. 검토 일자나 적용 기간을 현재 날짜로 발명하지 않는다."""
    import gzip

    raw = Path(reviewed).read_bytes()
    if hashlib.sha256(raw).hexdigest() != reviewed_sha:
        raise ValueError("review SHA mismatch")
    if not version:
        raise ValueError("version required")
    rows = [
        json.loads(line) for line in raw.decode("utf-8").splitlines() if line.strip()
    ]
    if not rows:
        raise ValueError("reviewed paths required")
    bus_raw = Path(bus).read_bytes()
    if hashlib.sha256(bus_raw).hexdigest() != bus_sha:
        raise ValueError("bus SHA mismatch")
    master = json.loads(gzip.decompress(bus_raw))
    result = dict(
        schemaVersion=1,
        version=version,
        masterVersion=master["masterVersion"],
        busCrosswalkSha256=bus_sha,
        reviewedSha256=reviewed_sha,
        mappings=rows,
    )
    encoded = json.dumps(
        result, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode()
    digest = hashlib.sha256(encoded).hexdigest()
    with tempfile.TemporaryDirectory() as tmp:
        candidate = Path(tmp) / "candidate.json"
        candidate.write_bytes(encoded)
        RoadImpactIndex(bus, bus_sha, candidate, digest)
    with Path(output).open("xb") as target:
        target.write(encoded)
    return dict(
        path=str(output),
        sha256=digest,
        version=version,
        masterVersion=master["masterVersion"],
        mappingCount=len(rows),
    )


def main():
    parser = argparse.ArgumentParser()
    for name in ("bus", "bus-sha", "reviewed", "reviewed-sha", "version", "output"):
        parser.add_argument("--" + name, required=True)
    args = parser.parse_args()
    print(json.dumps(promote(**vars(args)), ensure_ascii=False))


if __name__ == "__main__":
    main()
