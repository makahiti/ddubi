"""HDFS city.raw 아카이브를 City Silver 전용 버전 경로로 수동 백필한다.

운영 스트리밍 checkpoint와 live 출력은 사용하지 않으며 Redis에도 쓰지 않는다.
"""

from __future__ import annotations

import argparse
import logging
import sys
from datetime import date, timedelta

from common.archive_source import read_archive_events
from pyspark.sql import SparkSession
from streaming.city_realtime_silver import normalize_batch, write_silver

LOG = logging.getLogger(__name__)


def arguments():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, help="예: hdfs://hdfs-namenode:9000/ddubi/archive")
    parser.add_argument("--output-root", required=True, help="예: hdfs://.../ddubi/silver/city/realtime-v1")
    parser.add_argument("--version", required=True, help="검토 가능한 백필 실행 버전(예: 20260917-v1)")
    parser.add_argument("--start", required=True, type=date.fromisoformat)
    parser.add_argument("--end", required=True, type=date.fromisoformat)
    parser.add_argument("--freshness-seconds", type=int, default=900)
    return parser.parse_args()


def main() -> int:
    args = arguments()
    if args.start > args.end:
        raise ValueError("--start는 --end 이후일 수 없습니다")
    if "/live" in args.output_root.rstrip("/").lower():
        raise ValueError("백필 출력은 live 경로와 분리해야 합니다")
    spark = (
        SparkSession.builder.appName("ddubi-city-realtime-silver-backfill")
        .config("spark.sql.session.timeZone", "Asia/Seoul")
        .getOrCreate()
    )
    try:
        target = args.output_root.rstrip("/")
        namespace = f"backfill/version={args.version}"
        current = args.start
        while current <= args.end:
            # CITYDATA 한 건에는 도로 링크가 많이 들어 있어 전체 기간을 driver에 한 번에
            # 모으지 않는다. 날짜별 독립 batch 경로는 재실행 때 해당 날짜만 안전하게 덮어쓴다.
            events = read_archive_events(spark, args.input.rstrip("/"), "city.raw", current, current)
            frames = normalize_batch(events, int(current.strftime("%Y%m%d")), args.freshness_seconds)
            if frames.quality.first().inputCount:
                write_silver(frames, int(current.strftime("%Y%m%d")), target, namespace)
            current += timedelta(days=1)
        LOG.info("city realtime backfill complete: %s/%s", target, namespace)
    finally:
        spark.stop()
    return 0


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, stream=sys.stdout, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    sys.exit(main())
