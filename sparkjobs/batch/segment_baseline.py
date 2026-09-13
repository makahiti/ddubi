"""실측 구간 사실 → 요일·시각·날씨별 P50/P80/P95 → 검토 파일/기존 내부 업로드 도구.

FACT_SCHEMA를 만족하는 실측 Parquet와 Backend에서 받은 segmentId/masterVersion 레지스트리를
읽는다. 현재 저빈도 위치·도착예정 원문만으로 이 입력을 만들었다고 가정하지 않는다.
평가일 이전 전체 날짜만 사용하고, 최소 표본/관측 날짜는 실행자가 명시한다. 빈 그룹을
0초로 채우거나 특정 날짜가 되었다는 이유로 완성된 기준선으로 활성화하지 않는다.
"""

import argparse
import json
from datetime import date, timedelta
from pyspark.sql import SparkSession, functions as F
from common.segment_facts import FACT_SCHEMA, REGISTRY_SCHEMA, validate
from common.baseline_upload import BaselineUploadClient
import os


def aggregate(facts, data_version, min_samples, min_dates):
    """event time(KST)의 1=일요일/0~23시 그룹으로 초 분위수를 계산하고 부족 표본을 표시한다."""
    if min_samples < 1 or min_dates < 1 or not data_version:
        raise ValueError("positive sample/date minimum and version required")
    keyed = facts.withColumn("dow", F.dayofweek("occurredAt")).withColumn(
        "hourSlot", F.hour("occurredAt")
    )
    return (
        keyed.groupBy("segmentId", "masterVersion", "dow", "hourSlot", "weatherBucket")
        .agg(
            F.expr(
                "percentile_approx(durationSeconds, array(0.5,0.8,0.95), 10000)"
            ).alias("quantiles"),
            F.count("eventId").alias("sampleCount"),
            F.countDistinct(F.to_date("occurredAt")).alias("observedDates"),
            F.min("occurredAt").alias("observationStart"),
            F.max("occurredAt").alias("observationEnd"),
        )
        .withColumn("p50", F.col("quantiles")[0])
        .withColumn("p80", F.col("quantiles")[1])
        .withColumn("p95", F.col("quantiles")[2])
        .drop("quantiles")
        .withColumn("dataVersion", F.lit(data_version))
        .withColumn(
            "eligible",
            (F.col("sampleCount") >= min_samples)
            & (F.col("observedDates") >= min_dates),
        )
    )


def main(argv=None):
    """검토용 Parquet를 먼저 완성하고 --upload일 때만 기존 API의 전체 성공 후 활성화를 호출한다."""
    parser = argparse.ArgumentParser()
    for name in ("input", "registry", "output", "version", "as-of"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--days", type=int, default=7)
    parser.add_argument("--min-samples", type=int, required=True)
    parser.add_argument("--min-dates", type=int, required=True)
    parser.add_argument("--upload", action="store_true")
    args = parser.parse_args(argv)
    if args.days < 7:
        raise ValueError(
            "initial baseline window must cover at least seven calendar days"
        )
    end = date.fromisoformat(args.as_of)
    start = end - timedelta(days=args.days)
    spark = (
        SparkSession.builder.appName("ddubi-segment-baseline")
        .config("spark.sql.session.timeZone", "Asia/Seoul")
        .getOrCreate()
    )
    try:
        raw = spark.read.schema(FACT_SCHEMA).parquet(args.input)
        registry = spark.read.schema(REGISTRY_SCHEMA).json(args.registry)
        valid, rejected = validate(raw, registry, args.as_of + "T00:00:00+09:00")
        # 오류 원본과 진단을 먼저 보존한다. 실패한 출력 디렉터리는 재실행에 재사용하지 않는다.
        rejected.write.mode("errorifexists").parquet(args.output + "/rejected")
        selected = valid.filter(
            (F.col("occurredAt") >= F.lit(start.isoformat()).cast("timestamp"))
            & (F.col("occurredAt") < F.lit(end.isoformat()).cast("timestamp"))
        )
        baseline = aggregate(
            selected, args.version, args.min_samples, args.min_dates
        ).cache()
        baseline.write.mode("errorifexists").parquet(args.output + "/baseline")
        selected.groupBy(F.to_date("occurredAt").alias("date")).count().write.mode(
            "errorifexists"
        ).json(args.output + "/coverage")
        # 실행 시각과 입력·정책을 결과에 묶는다. 같은 버전의 재현과 표본 부족 원인 추적에 사용한다.
        computed_at = (
            spark.range(1)
            .select(
                F.date_format(F.current_timestamp(), "yyyy-MM-dd'T'HH:mm:ssXXX").alias(
                    "value"
                )
            )
            .first()
            .value
        )
        metadata = dict(vars(args), computedAt=computed_at)
        spark.range(1).select(
            F.lit(json.dumps(metadata, sort_keys=True)).alias("value")
        ).write.mode("errorifexists").text(args.output + "/manifest")
        eligible = baseline.filter("eligible").select(
            "segmentId",
            "dow",
            "hourSlot",
            "weatherBucket",
            "p50",
            "p80",
            "p95",
            "sampleCount",
        )
        if args.upload:
            # toLocalIterator로 청크만 driver에 보낸다. 전체 collect/toPandas를 사용하지 않는다.
            client = BaselineUploadClient(
                os.environ["INTERNAL_API_BASE_URL"], os.environ["INTERNAL_API_KEY"]
            )
            client.upload_and_activate(
                args.version, (r.asDict() for r in eligible.toLocalIterator())
            )
    finally:
        spark.stop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
