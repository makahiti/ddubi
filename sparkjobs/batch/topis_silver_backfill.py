"""보관된 city.raw TOPIS 공지를 현재 AI/지오코딩 규칙으로 일괄 전처리한다.

선택 기간을 Spark DataFrame으로 분산 처리하고 partition 안에서 정규화한다. 따라서
전체 원본이나 정규화 결과를 driver의 Python list로 수집하지 않는다.
"""

import argparse
import json
import os
from datetime import date

from common.archive_source import read_archive_events
from common.topis_enrichment import GmsClient, KakaoGeocoder, normalize_notice
from pyspark import StorageLevel
from pyspark.sql import SparkSession
from pyspark.sql import functions as F
from streaming.topis_enriched import SCHEMA


def _normalize_partition(rows, gms_config, kakao_api_key):
    """한 Spark partition에서 외부 API client를 한 번만 만들고 결과를 바로 yield한다."""
    gms = GmsClient(*gms_config)
    geocoder = KakaoGeocoder(kakao_api_key)
    for item in rows:
        try:
            event = json.loads(item.rawEventJson)
        except (AttributeError, TypeError, ValueError, json.JSONDecodeError):
            # Kafka 봉투 자체가 깨진 행은 TOPIS schema를 억지로 채우지 않는다.
            # 원본은 archive에 보존되어 있으며 이 backfill의 출력 대상에서 제외한다.
            continue
        yield from normalize_notice(event, gms, geocoder)


def _notice_events(spark, input_root, start, end):
    events = read_archive_events(spark, input_root, "city.raw", start, end)
    # Collector 중복 제거 도입 전에는 같은 공지가 반복되었다. 같은 공지 ID와
    # 내용 hash만 제거하고 내용이 바뀐 개정본은 남긴다.
    return (
        events.filter(F.col("eventType") == "topis.notice")
        .withColumn(
            "providerNoticeId",
            F.coalesce(
                F.get_json_object("rawEventJson", "$.payload.providerNoticeId"),
                F.get_json_object("rawEventJson", "$.payload.raw.noticeId"),
            ),
        )
        .withColumn(
            "noticeContentHash",
            F.coalesce(
                F.get_json_object("rawEventJson", "$.payload.contentHash"),
                F.sha2(F.get_json_object("rawEventJson", "$.payload.raw"), 256),
            ),
        )
        .dropDuplicates(["providerNoticeId", "noticeContentHash"])
        .select("rawEventJson")
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, help="Archive root, for example hdfs://.../ddubi/archive")
    parser.add_argument("--output", required=True, help="New versioned topis-backfill output root")
    parser.add_argument("--start", type=date.fromisoformat, required=True)
    parser.add_argument("--end", type=date.fromisoformat, required=True)
    parser.add_argument(
        "--partitions",
        type=int,
        default=int(os.environ.get("TOPIS_BACKFILL_PARTITIONS", "2")),
        help="동시에 GMS/Kakao를 호출할 최대 Spark partition 수",
    )
    args = parser.parse_args()
    if args.start > args.end:
        parser.error("start must not be after end")
    if args.partitions < 1:
        parser.error("partitions must be at least 1")
    if "topis-backfill/" not in args.output:
        parser.error("output must be a new versioned topis-backfill/<version> root")

    # 필수 설정은 output 디렉터리를 만들기 전에 검증한다.
    gms_config = (
        os.environ.get("GMS_API_KEY"),
        os.environ.get("GMS_BASE_URL", "https://gms.ssafy.io/gmsapi/"),
        os.environ.get("GMS_MODEL", "gpt-4o-mini"),
        int(os.environ.get("TOPIS_AI_TIMEOUT_SECONDS", "30")),
    )
    kakao_api_key = os.environ.get("KAKAO_REST_API_KEY")
    GmsClient(*gms_config)
    KakaoGeocoder(kakao_api_key)

    spark = (
        SparkSession.builder.appName("topis_silver_backfill")
        .config("spark.sql.session.timeZone", "Asia/Seoul")
        .getOrCreate()
    )
    try:
        root = args.output.rstrip("/")
        spark.createDataFrame(
            [(args.input, args.start.isoformat(), args.end.isoformat(), args.partitions)],
            ["input", "start", "end", "partitions"],
        ).write.mode("errorifexists").json(root + "/_run")

        # 기간 전체에서 중복을 제거해야 날짜가 달라도 같은 공지를 AI에 다시 보내지 않는다.
        notices = _notice_events(spark, args.input, args.start, args.end).repartition(args.partitions)
        normalized = (
            spark.createDataFrame(
                notices.rdd.mapPartitions(lambda rows: _normalize_partition(rows, gms_config, kakao_api_key)),
                SCHEMA,
            )
            .withColumn("eventDate", F.to_date("collectedAt"))
            .persist(StorageLevel.DISK_ONLY)
        )
        try:
            output_count = normalized.count()
            if output_count == 0:
                raise RuntimeError("No TOPIS notices found in the selected archive date range")
            normalized.write.mode("errorifexists").partitionBy("processingStatus", "eventDate").parquet(root + "/data")
        finally:
            normalized.unpersist()

        result = spark.read.parquet(root + "/data")
        result.groupBy("processingStatus").count().write.mode("errorifexists").json(root + "/manifest")
        spark.createDataFrame([("COMPLETE", output_count)], ["status", "outputRows"]).write.mode("errorifexists").json(
            root + "/complete"
        )
    finally:
        spark.stop()


if __name__ == "__main__":
    main()
