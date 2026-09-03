"""T-Data 날짜별 OpenAPI 원천을 재현 가능하게 내려받는다.

인증키는 명령행에 쓰지 않고 ``TDATA_API_KEY`` 환경변수에서만 읽는다. 응답은
날짜별 JSONL로 저장하고 원문 응답의 키 구조가 바뀌면 즉시 실패한다.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import urllib.parse
import urllib.request
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Iterable


DATASETS = {
    "bus-segment-time": {
        "url": "https://t-data.seoul.go.kr/apig/apiman-gateway/tapi/TaimsTpssRouteSectionSpeedH/1.0",
        "date_parameter": "stdrDe",
    },
    "traffic-link-speed": {
        "url": "https://t-data.seoul.go.kr/apig/apiman-gateway/tapi/TopisIccStTimesLinkTrfSectionStats/1.0",
        "date_parameter": "stndDt",
    },
}


def iter_dates(start: date, end: date) -> Iterable[date]:
    current = start
    while current <= end:
        yield current
        current += timedelta(days=1)


def find_rows(payload: Any) -> list[dict[str, Any]]:
    """T-Data 래퍼 안에서 실제 레코드 목록을 찾되 모호하면 실패한다."""
    candidates: list[list[dict[str, Any]]] = []

    def visit(value: Any) -> None:
        if isinstance(value, list) and value and all(isinstance(row, dict) for row in value):
            candidates.append(value)
            return
        if isinstance(value, dict):
            for child in value.values():
                visit(child)

    visit(payload)
    if not candidates:
        return []
    candidates.sort(key=len, reverse=True)
    if len(candidates) > 1 and len(candidates[0]) == len(candidates[1]):
        raise ValueError("T-Data response contains ambiguous row arrays")
    return candidates[0]


def request_page(
    dataset: dict[str, str], api_key: str, day: date, start_row: int, row_count: int
) -> tuple[list[dict[str, Any]], Any]:
    params = {
        "apikey": api_key,
        "startRow": start_row,
        "rowCnt": row_count,
        dataset["date_parameter"]: day.strftime("%Y%m%d"),
    }
    url = f"{dataset['url']}?{urllib.parse.urlencode(params)}"
    request = urllib.request.Request(url, headers={"User-Agent": "ddubi-data/1.0"})
    with urllib.request.urlopen(request, timeout=60) as response:
        body = response.read()
    try:
        payload = json.loads(body)
    except json.JSONDecodeError as exc:
        raise ValueError(f"T-Data returned non-JSON for {day}") from exc
    if not isinstance(payload, (dict, list)):
        raise ValueError(f"T-Data response is not an object or array for {day}")
    return find_rows(payload), payload


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download_day(
    dataset: dict[str, str], api_key: str, day: date, output_dir: Path, row_count: int
) -> dict[str, Any]:
    final_path = output_dir / f"{day:%Y%m%d}.jsonl"
    partial_path = final_path.with_suffix(".jsonl.part")
    if final_path.exists():
        return {"date": day.isoformat(), "status": "existing", "size_bytes": final_path.stat().st_size, "sha256": sha256(final_path)}

    output_dir.mkdir(parents=True, exist_ok=True)
    total = 0
    start_row = 1
    with partial_path.open("w", encoding="utf-8") as stream:
        while True:
            rows, payload = request_page(dataset, api_key, day, start_row, row_count)
            if not rows:
                if total == 0:
                    # 정상적인 무자료일과 API 오류를 구분할 수 있도록 원문을 남긴다.
                    error_text = json.dumps(payload, ensure_ascii=False).lower()
                    if any(token in error_text for token in ("error", "invalid", "fail", "인증")):
                        raise ValueError(f"T-Data API error for {day}: {error_text[:300]}")
                break
            for row in rows:
                stream.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")
            total += len(rows)
            if len(rows) < row_count:
                break
            start_row += len(rows)
    partial_path.replace(final_path)
    return {
        "date": day.isoformat(),
        "status": "downloaded",
        "rows": total,
        "size_bytes": final_path.stat().st_size,
        "sha256": sha256(final_path),
    }


def parse_date(value: str) -> date:
    return datetime.strptime(value, "%Y-%m-%d").date()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", choices=DATASETS, required=True)
    parser.add_argument("--from-date", type=parse_date, required=True)
    parser.add_argument("--to-date", type=parse_date, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--row-count", type=int, default=1000)
    parser.add_argument("--manifest", type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.from_date > args.to_date:
        raise SystemExit("--from-date must not be after --to-date")
    api_key = os.environ.get("TDATA_API_KEY")
    if not api_key:
        raise SystemExit("TDATA_API_KEY is not set")
    dataset = DATASETS[args.dataset]
    files = []
    manifest_path = args.manifest or args.output_dir / "manifest.json"
    for day in iter_dates(args.from_date, args.to_date):
        result = download_day(dataset, api_key, day, args.output_dir, args.row_count)
        files.append(result)
        manifest_path.parent.mkdir(parents=True, exist_ok=True)
        manifest_path.write_text(
            json.dumps(
                {
                    "dataset": args.dataset,
                    "source_url": dataset["url"],
                    "generated_at": datetime.now(timezone.utc).isoformat(),
                    "files": files,
                },
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )
        print(f"{result['date']} rows={result.get('rows', '-')} status={result['status']}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
