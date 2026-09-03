"""서울 버스 월간 운행시간·운행횟수 ZIP을 공식 페이지에서 받는다.

페이지의 공개 파일 목록을 먼저 읽으므로 다운로드 식별자를 하드코딩하지 않는다.
완료 파일은 SHA-256과 크기를 manifest JSON에 남기고, 미완료 파일은 .part로 구분한다.
"""

from __future__ import annotations

import argparse
import hashlib
import html
import json
import re
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path


DOWNLOAD_URL = "https://datafile.seoul.go.kr/bigfile/iot/inf/nio_download.do?useCache=false"
DATASETS = {
    "segment-time": {
        "id": "OA-21217",
        "url": "https://data.seoul.go.kr/dataList/OA-21217/S/1/datasetView.do",
        "file_prefix": "tpss_route_section_speedh_",
    },
    "operations": {
        "id": "OA-21220",
        "url": "https://data.seoul.go.kr/dataList/OA-21220/S/1/datasetView.do",
        "file_prefix": "tpss_sta_route_hturn_",
    },
}


def fetch_catalog(dataset: dict[str, str]) -> list[dict[str, str]]:
    request = urllib.request.Request(dataset["url"], headers={"User-Agent": "ddubi-data/1.0"})
    with urllib.request.urlopen(request, timeout=30) as response:
        page = response.read().decode("utf-8", errors="replace")
    file_pattern = re.compile(
        r"downloadFile\('(?P<seq>\d+)'\).*?"
        + rf"(?P<name>{re.escape(dataset['file_prefix'])}(?P<month>\d{{6}})\.zip)",
        re.DOTALL,
    )
    by_month: dict[str, dict[str, str]] = {}
    for match in file_pattern.finditer(page):
        month = match.group("month")
        by_month[month] = {
            "month": month,
            "seq": match.group("seq"),
            "file_name": html.unescape(match.group("name")),
        }
    if not by_month:
        raise RuntimeError("공식 페이지에서 월간 ZIP 목록을 찾지 못했습니다")
    return [by_month[month] for month in sorted(by_month)]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(
    entry: dict[str, str], output_dir: Path, dataset: dict[str, str]
) -> dict[str, object]:
    body = urllib.parse.urlencode(
        {"infId": dataset["id"], "seqNo": "", "seq": entry["seq"], "infSeq": "3"}
    ).encode("ascii")
    request = urllib.request.Request(
        DOWNLOAD_URL,
        data=body,
        headers={"User-Agent": "ddubi-data/1.0", "Referer": dataset["url"]},
        method="POST",
    )
    output = output_dir / entry["file_name"]
    partial = output.with_suffix(output.suffix + ".part")
    with urllib.request.urlopen(request, timeout=120) as response:
        expected = int(response.headers.get("Content-Length", "0"))
        disposition = response.headers.get("Content-Disposition", "")
        if "attachment" not in disposition.lower():
            raise RuntimeError(f"다운로드 응답이 첨부파일이 아닙니다: {entry['month']}")
        if output.exists() and expected > 0 and output.stat().st_size == expected:
            status = "existing"
        else:
            with partial.open("wb") as stream:
                while chunk := response.read(1024 * 1024):
                    stream.write(chunk)
            if expected > 0 and partial.stat().st_size != expected:
                raise RuntimeError(
                    f"크기 불일치 {entry['file_name']}: {partial.stat().st_size} != {expected}"
                )
            partial.replace(output)
            status = "downloaded"
    return {
        **entry,
        "status": status,
        "size_bytes": output.stat().st_size,
        "sha256": sha256(output),
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", choices=DATASETS, default="segment-time")
    parser.add_argument("--from-month", default="202201", help="YYYYMM")
    parser.add_argument("--to-month", default="999912", help="YYYYMM")
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--manifest", type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    dataset = DATASETS[args.dataset]
    entries = [
        entry
        for entry in fetch_catalog(dataset)
        if args.from_month <= entry["month"] <= args.to_month
    ]
    if not entries:
        raise SystemExit("지정 기간에 해당하는 월간 파일이 없습니다")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    manifest_path = args.manifest or args.output_dir / "manifest.json"
    completed: list[dict[str, object]] = []
    for index, entry in enumerate(entries, start=1):
        print(f"[{index}/{len(entries)}] {entry['file_name']}", flush=True)
        completed.append(download(entry, args.output_dir, dataset))
        manifest_path.write_text(
            json.dumps(
                {
                    "dataset": dataset["id"],
                    "source_url": dataset["url"],
                    "generated_at": datetime.now(timezone.utc).isoformat(),
                    "files": completed,
                },
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )
    print(f"complete files={len(completed)} manifest={manifest_path}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
