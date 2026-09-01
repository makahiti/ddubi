"""날씨 기준선 배치: 아카이브(HDFS Parquet) → 격자·요일·시간대별 평시 기상값 → HDFS Parquet

기준선(baseline)은 "평소 이 시간엔 어땠나" 를 뜻한다. 실시간 값과 비교해 이상 여부를 판단하는 잣대다.
구간(segment) 소요시간 기준선도 같은 틀로 만든다 (입력 토픽·집계 로직만 다름).

사용: sh submit.sh batch/weather_baseline.py [--days 28] [--as-of YYYY-MM-DD]
입력: BASELINE_INPUT_URI/city.raw  (archiver 가 쓴 Parquet, date=/hour= 파티션)
출력: BASELINE_OUTPUT_URI/weather/  (nx, ny, dow, hour 별 1행. 매 실행마다 전체 덮어쓰기)
"""

from __future__ import annotations

import argparse
import logging
import os
import sys
from datetime import date

from pyspark.sql import DataFrame, SparkSession
from pyspark.sql import functions as F

from common.archive_source import lookback_window, read_archive_events
from streaming.weather_latest import transform as weather_transform

JOB_NAME = "weather_baseline"
TOPIC = "city.raw"
log = logging.getLogger(JOB_NAME)


def aggregate(weather: DataFrame, as_of: date, window_days: int) -> DataFrame:
    """weather_latest.transform 결과 → (nx, ny, dow, hour) 별 기준선.

    dow: 1=일 … 7=토 (Spark dayofweek), hour: 관측 시각(occurredAt, KST) 의 시.
    """
    keyed = weather.withColumn("dow", F.dayofweek("occurredAt")).withColumn("hour", F.hour("occurredAt"))
    obs = keyed.select(
        "nx", "ny", "dow", "hour", "districts",
        F.col("observations")["T1H"].alias("t1h"),
        F.col("observations")["REH"].alias("reh"),
        F.col("observations")["WSD"].alias("wsd"),
        F.col("observations")["RN1"].alias("rn1"),
        F.col("observations")["PTY"].alias("pty"),
    )
    return obs.groupBy("nx", "ny", "dow", "hour").agg(
        F.count(F.lit(1)).alias("samples"),
        F.first("districts").alias("districts"),  # 이 격자를 공유하는 구 목록
        F.round(F.avg("t1h"), 1).alias("tempAvg"),
        F.round(F.min("t1h"), 1).alias("tempMin"),
        F.round(F.max("t1h"), 1).alias("tempMax"),
        F.round(F.avg("reh"), 1).alias("humidityAvg"),
        F.round(F.avg("wsd"), 1).alias("windAvg"),
        F.round(F.avg("rn1"), 2).alias("rain1hAvg"),
        F.round(F.avg(F.when(F.col("pty") > 0, 1.0).otherwise(0.0)), 3).alias("precipRatio"),  # 강수 있었던 비율
    ).withColumn("asOf", F.lit(as_of.isoformat())).withColumn("windowDays", F.lit(window_days))


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--days", type=int, default=int(os.environ.get("BASELINE_WINDOW_DAYS", "28")))
    parser.add_argument("--as-of", type=date.fromisoformat, default=None, help="기준 날짜(포함 안 함). 기본 오늘")
    parser.add_argument("--include-today", action="store_true", help="오늘 파티션까지 포함 (검증용)")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s", stream=sys.stdout)
    args = parse_args(argv)
    input_uri = os.environ["BASELINE_INPUT_URI"]
    output_uri = os.environ["BASELINE_OUTPUT_URI"].rstrip("/")

    spark = SparkSession.builder.appName(f"ddubi-{JOB_NAME}").getOrCreate()
    spark.sparkContext.setLogLevel("WARN")
    as_of = args.as_of or date.fromisoformat(spark.sql("SELECT current_date()").first()[0].isoformat())
    start, end = lookback_window(as_of, args.days)
    if args.include_today:
        end = as_of
    log.info("input=%s/%s window=%s..%s output=%s/weather", input_uri, TOPIC, start, end, output_uri)

    events = read_archive_events(spark, input_uri, TOPIC, start, end)
    baseline = aggregate(weather_transform(events), as_of, args.days).cache()
    rows = baseline.count()
    grids = baseline.select("nx", "ny").distinct().count()
    baseline.coalesce(1).write.mode("overwrite").parquet(f"{output_uri}/weather")  # 작은 결과라 파일 1개
    log.info("wrote rows=%d grids=%d -> %s/weather", rows, grids, output_uri)
    spark.stop()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
