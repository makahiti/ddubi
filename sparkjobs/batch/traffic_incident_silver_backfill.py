"""Archived city.raw traffic incidents -> versioned Silver Parquet; Redis is not changed.

선택 기간을 Spark DataFrame으로 분산 읽고 검증된 link master를 broadcast한 뒤
executor에서 정규화한다. 전체 원본이나 결과를 driver의 Python list로 수집하지 않는다.
"""

import argparse
import json
import os
from datetime import date

from common.archive_source import read_archive_events
from common.traffic_incident_enrichment import VerifiedLinkMaster, normalize_incident
from common.traffic_incident_policy import policy
from pyspark import StorageLevel
from pyspark.sql import SparkSession
from pyspark.sql import functions as F
from streaming.traffic_incident_silver import SCHEMA


def _normalize_partition(rows, master, stale_seconds, direct_tolerance_meters, nearby_max_meters):
    """파티션의 행을 하나씩 정규화해 driver 누적 없이 executor 결과로 반환한다."""
    for item in rows:
        try:
            event = json.loads(item.rawEventJson)
        except (AttributeError, TypeError, ValueError, json.JSONDecodeError):
            continue
        yield normalize_incident(
            event,
            master,
            stale_seconds,
            direct_tolerance_meters,
            nearby_max_meters,
        )


def _incident_events(spark, input_root, start, end):
    events = read_archive_events(spark, input_root, "city.raw", start, end)
    return (
        events.filter(F.col("eventType") == "traffic.incident")
        .withColumn("incidentId", F.get_json_object("rawEventJson", "$.payload.providerIncidentId"))
        .withColumn("contentHash", F.get_json_object("rawEventJson", "$.payload.contentHash"))
        .dropDuplicates(["incidentId", "contentHash"])
        .select("rawEventJson")
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--start", type=date.fromisoformat, required=True)
    parser.add_argument("--end", type=date.fromisoformat, required=True)
    args = parser.parse_args()
    if args.start > args.end:
        parser.error("start must not be after end")
    if "traffic-incident-backfill/" not in args.output:
        parser.error("output must be a new traffic-incident-backfill/<version> root")

    # 설정 오류가 부분 _run/output 디렉터리를 남기기 전에 먼저 실패시킨다.
    incident_policy = policy(os.environ)
    master = VerifiedLinkMaster.load(
        os.environ.get("TRAFFIC_LINK_MASTER_PATH", "").strip(),
        os.environ.get("TRAFFIC_LINK_MASTER_SHA256", "").strip(),
    )
    spark = (
        SparkSession.builder.appName("traffic_incident_silver_backfill")
        .config("spark.sql.session.timeZone", "Asia/Seoul")
        .getOrCreate()
    )
    master_broadcast = None
    try:
        master_broadcast = spark.sparkContext.broadcast(master)
        root = args.output.rstrip("/")
        spark.createDataFrame(
            [(args.input, args.start.isoformat(), args.end.isoformat())],
            ["input", "start", "end"],
        ).write.mode("errorifexists").json(root + "/_run")

        incidents = _incident_events(spark, args.input, args.start, args.end)
        normalized = (
            spark.createDataFrame(
                incidents.rdd.mapPartitions(
                    lambda rows: _normalize_partition(
                        rows,
                        master_broadcast.value,
                        incident_policy.stale_seconds,
                        incident_policy.direct_tolerance_meters,
                        incident_policy.nearby_max_meters,
                    )
                ),
                SCHEMA,
            )
            .withColumn("eventDate", F.to_date("collectedAt"))
            .persist(StorageLevel.DISK_ONLY)
        )
        try:
            output_count = normalized.count()
            if output_count == 0:
                raise RuntimeError("No traffic incidents found in selected archive range")
            normalized.write.mode("errorifexists").partitionBy(
                "processingStatus", "locationMatchType", "eventDate"
            ).parquet(root + "/data")
        finally:
            normalized.unpersist()

        result = spark.read.parquet(root + "/data")
        result.groupBy("processingStatus", "locationMatchType").count().write.mode("errorifexists").json(
            root + "/manifest"
        )
        spark.createDataFrame([("COMPLETE", output_count)], ["status", "outputRows"]).write.mode("errorifexists").json(
            root + "/complete"
        )
    finally:
        if master_broadcast is not None:
            master_broadcast.unpersist(blocking=False)
        spark.stop()


if __name__ == "__main__":
    main()
