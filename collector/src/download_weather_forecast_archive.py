"""기상청 단기예보의 과거 발표본을 JSONL archive로 수집한다.

예측 대상 시각이 아니라 발표 시각별 파일을 남기므로 학습 조인에서
``issuedAt <= predictionAt`` 조건을 지킬 수 있다. 기존 파일은 건너뛴다.
"""

from __future__ import annotations

import argparse
import json
import os
import time
from datetime import date, datetime, timedelta
from pathlib import Path

import httpx

from config import Settings
from sources import weather_forecast
from sources.base import KST


def parse_date(value: str) -> date:
    return datetime.strptime(value, "%Y-%m-%d").date()


def iter_issues(start: date, end: date, hours: list[int]):
    current = start
    while current <= end:
        for hour in sorted(set(hours)):
            yield datetime(current.year, current.month, current.day, hour, tzinfo=KST)
        current += timedelta(days=1)


def serialise(record) -> dict[str, object]:
    return {
        "key": record.key,
        "eventType": record.event_type,
        "occurredAt": record.occurred_at.isoformat(),
        "payload": record.payload,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--from-date", required=True, type=parse_date)
    parser.add_argument("--to-date", required=True, type=parse_date)
    parser.add_argument(
        "--issue-hour",
        action="append",
        type=int,
        choices=weather_forecast.ISSUE_HOURS,
        help="KST 발표시각; 생략하면 하루 8회 모두 수집",
    )
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--delay-seconds", type=float, default=0.1)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.from_date > args.to_date:
        raise SystemExit("--from-date must not be after --to-date")
    keys = {
        name: value
        for name in ("KMA_API_KEY", "DATA_GO_KR_API_KEY")
        if (value := os.environ.get(name))
    }
    if not keys:
        raise SystemExit("KMA_API_KEY or DATA_GO_KR_API_KEY is not set")
    settings = Settings(
        source="weather_forecast",
        kafka_bootstrap_servers="archive-only",
        api_keys=keys,
        extra={
            "WEATHER_GRID_POINTS": os.environ.get(
                "WEATHER_GRID_POINTS", weather_forecast.DEFAULT_GRID_POINTS
            )
        },
    )
    hours = args.issue_hour or list(weather_forecast.ISSUE_HOURS)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    with httpx.Client(timeout=60) as client:
        for issued_at in iter_issues(args.from_date, args.to_date, hours):
            final_path = args.output_dir / f"issued_at={issued_at:%Y%m%d%H}.jsonl"
            if final_path.exists():
                print(f"{issued_at.isoformat()} existing", flush=True)
                continue
            partial_path = final_path.with_suffix(".jsonl.part")
            records = weather_forecast.fetch_for_issue(settings, client, issued_at)
            with partial_path.open("w", encoding="utf-8") as stream:
                for record in records:
                    stream.write(
                        json.dumps(serialise(record), ensure_ascii=False, separators=(",", ":"))
                        + "\n"
                    )
            partial_path.replace(final_path)
            print(f"{issued_at.isoformat()} records={len(records)}", flush=True)
            if args.delay_seconds > 0:
                time.sleep(args.delay_seconds)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
