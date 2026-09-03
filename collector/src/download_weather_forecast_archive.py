"""기상청 API허브의 과거 단기예보 격자 원본을 재현 가능하게 수집한다.

승인 대상인 ``nph-dfs_shrt_grd`` API는 발표시각(``tmfc``), 발효시각
(``tmef``), 변수(``vars``) 하나를 받아 전국 격자 ASCII를 반환한다. 인증키는
명령행에 남기지 않고 ``KMA_API_KEY`` 환경변수에서만 읽는다.

기본 모드는 각 발효시각 직전에 실제로 이용 가능했던 최신 발표본 하나를 고른다.
따라서 학습 조인에서 미래에 발표된 예보를 섞지 않으면서 요청량도 제한할 수 있다.
응답은 손실 없이 gzip으로 보존하고 manifest에는 키를 제외한 메타데이터만 남긴다.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
import time
import urllib.parse
import urllib.request
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Iterable


API_URL = "https://apihub.kma.go.kr/api/typ01/cgi-bin/url/nph-dfs_shrt_grd"
ISSUE_HOURS = (2, 5, 8, 11, 14, 17, 20, 23)
DEFAULT_VARIABLES = ("TMP", "REH", "WSD", "SKY", "PTY", "POP", "PCP")
KST = timezone(timedelta(hours=9))


def parse_date(value: str) -> date:
    return datetime.strptime(value, "%Y-%m-%d").date()


def iter_valid_hours(start: date, end: date) -> Iterable[datetime]:
    current = datetime(start.year, start.month, start.day, tzinfo=KST)
    stop = datetime(end.year, end.month, end.day, 23, tzinfo=KST)
    while current <= stop:
        yield current
        current += timedelta(hours=1)


def latest_available_issue(valid_at: datetime, publication_delay_minutes: int) -> datetime:
    cutoff = valid_at - timedelta(minutes=publication_delay_minutes)
    candidates: list[datetime] = []
    for day_offset in (0, -1):
        day = (cutoff + timedelta(days=day_offset)).date()
        candidates.extend(
            datetime(day.year, day.month, day.day, hour, tzinfo=KST)
            for hour in ISSUE_HOURS
        )
    available = [issued_at for issued_at in candidates if issued_at <= cutoff]
    if not available:
        raise RuntimeError(f"발효시각 {valid_at.isoformat()} 이전 발표본을 찾지 못했습니다")
    return max(available)


def sha256_bytes(body: bytes) -> str:
    return hashlib.sha256(body).hexdigest()


def validate_grid(body: bytes, variable: str, issued_at: datetime, valid_at: datetime) -> int:
    text = body.decode("utf-8", errors="replace").strip()
    lowered = text.lower()
    if any(token in lowered for token in ("error", "invalid", "인증키", "resultcode")):
        raise ValueError(
            f"KMA API error variable={variable} issued={issued_at:%Y%m%d%H} "
            f"valid={valid_at:%Y%m%d%H}: {' '.join(text.split())[:240]}"
        )
    tokens = [token.rstrip(",") for token in text.split()]
    if len(tokens) < 30_000:
        raise ValueError(
            f"KMA grid is unexpectedly short variable={variable} values={len(tokens)}"
        )
    try:
        for token in tokens:
            float(token)
    except ValueError as exc:
        raise ValueError(f"KMA grid contains a non-numeric token: {token!r}") from exc
    return len(tokens)


def fetch_grid(
    api_key: str,
    variable: str,
    issued_at: datetime,
    valid_at: datetime,
    retries: int = 3,
) -> bytes:
    params = {
        "tmfc": issued_at.strftime("%Y%m%d%H"),
        "tmef": valid_at.strftime("%Y%m%d%H"),
        "vars": variable,
        "authKey": api_key,
    }
    request = urllib.request.Request(
        f"{API_URL}?{urllib.parse.urlencode(params)}",
        headers={"User-Agent": "ddubi-data/1.0"},
    )
    for attempt in range(1, retries + 1):
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                return response.read()
        except OSError:
            if attempt == retries:
                raise
            time.sleep(min(2**attempt, 10))
    raise AssertionError("unreachable")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--from-date", required=True, type=parse_date)
    parser.add_argument("--to-date", required=True, type=parse_date)
    parser.add_argument(
        "--variable",
        action="append",
        choices=DEFAULT_VARIABLES,
        help="생략하면 교통 예측용 7개 변수 모두 수집",
    )
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--publication-delay-minutes", type=int, default=15)
    parser.add_argument("--delay-seconds", type=float, default=0.1)
    parser.add_argument("--manifest", type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.from_date > args.to_date:
        raise SystemExit("--from-date must not be after --to-date")
    api_key = os.environ.get("KMA_API_KEY")
    if not api_key:
        raise SystemExit("KMA_API_KEY is not set")
    variables = args.variable or list(DEFAULT_VARIABLES)
    manifest_path = args.manifest or args.output_dir / "manifest.jsonl"
    manifest_path.parent.mkdir(parents=True, exist_ok=True)

    for valid_at in iter_valid_hours(args.from_date, args.to_date):
        issued_at = latest_available_issue(valid_at, args.publication_delay_minutes)
        target_dir = args.output_dir / f"valid_date={valid_at:%Y%m%d}"
        target_dir.mkdir(parents=True, exist_ok=True)
        for variable in variables:
            final_path = target_dir / (
                f"valid_at={valid_at:%Y%m%d%H}_issued_at={issued_at:%Y%m%d%H}_{variable}.txt.gz"
            )
            if final_path.exists():
                print(f"{valid_at.isoformat()} {variable} existing", flush=True)
                continue
            body = fetch_grid(api_key, variable, issued_at, valid_at)
            value_count = validate_grid(body, variable, issued_at, valid_at)
            partial_path = final_path.with_suffix(".txt.gz.part")
            with gzip.open(partial_path, "wb", compresslevel=6) as stream:
                stream.write(body)
            partial_path.replace(final_path)
            record = {
                "issued_at": issued_at.isoformat(),
                "valid_at": valid_at.isoformat(),
                "variable": variable,
                "source_url": API_URL,
                "value_count": value_count,
                "raw_size_bytes": len(body),
                "gzip_size_bytes": final_path.stat().st_size,
                "sha256_raw": sha256_bytes(body),
                "path": str(final_path),
                "downloaded_at": datetime.now(timezone.utc).isoformat(),
            }
            with manifest_path.open("a", encoding="utf-8") as stream:
                stream.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n")
            print(
                f"{valid_at.isoformat()} issued={issued_at:%Y-%m-%dT%H:%M%z} "
                f"{variable} values={value_count} bytes={len(body)}",
                flush=True,
            )
            if args.delay_seconds > 0:
                time.sleep(args.delay_seconds)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
