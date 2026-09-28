"""Kafka TOPIS notices -> GMS/Kakao normalized DataFrame -> Redis JSON and optional HDFS Parquet."""

import json
import logging
import os
import sys
from urllib.parse import urlsplit

from common.kafka_source import read_events_stream
from common.redis_sink import write_latest_with_row_ttl
from common.settings import Settings
from common.topis_enrichment import GmsClient, KakaoGeocoder, normalize_notice
from common.topis_redis import build_reconciliation_plans, reconcile_topis_latest
from common.topis_runtime import supervise
from pyspark.sql import SparkSession
from pyspark.sql import functions as F
from pyspark.sql import types as T

JOB = "topis_enriched"
TOPIC = "city.raw"
log = logging.getLogger(JOB)
POINT = T.StructType(
    [
        T.StructField("order", T.IntegerType()),
        T.StructField("placeText", T.StringType()),
        T.StructField("role", T.StringType()),
        T.StructField("geocodeQuery", T.StringType()),
        T.StructField("matchedPlaceName", T.StringType()),
        T.StructField("kakaoPlaceId", T.StringType()),
        T.StructField("latitude", T.DoubleType()),
        T.StructField("longitude", T.DoubleType()),
        T.StructField("geocodeStatus", T.StringType()),
        T.StructField("geocodeCandidateCount", T.IntegerType()),
        T.StructField("usableForBusMapping", T.BooleanType()),
        T.StructField("geocodeMatchBasis", T.StringType()),
        T.StructField("radiusMeters", T.IntegerType()),
        T.StructField("radiusBasis", T.StringType()),
    ]
)
LEG = T.StructType(
    [
        T.StructField("legOrder", T.IntegerType()),
        T.StructField("fromOrder", T.IntegerType()),
        T.StructField("toOrder", T.IntegerType()),
        T.StructField("fromPlaceText", T.StringType()),
        T.StructField("toPlaceText", T.StringType()),
        T.StructField("fromLatitude", T.DoubleType()),
        T.StructField("fromLongitude", T.DoubleType()),
        T.StructField("toLatitude", T.DoubleType()),
        T.StructField("toLongitude", T.DoubleType()),
        T.StructField("mappingStatus", T.StringType()),
    ]
)
SCHEMA = T.StructType(
    [
        T.StructField(name, T.StringType())
        for name in (
            "silverVersion",
            "noticeId",
            "sourceEventId",
            "source",
            "title",
            "contentText",
            "categoryName",
            "createdAtText",
            "updatedAtText",
            "collectedAt",
            "noticeContentHash",
            "extractionModel",
            "promptVersion",
            "eventId",
            "eventType",
            "impactType",
            "eventStatus",
            "startAt",
            "endAt",
            "validUntil",
            "yearBasis",
            "evidenceText",
            "processingStatus",
        )
    ]
    + [
        T.StructField("yearInferred", T.BooleanType()),
        T.StructField("durationMinutes", T.IntegerType()),
        T.StructField("routePointCount", T.IntegerType()),
        T.StructField("mappedRoutePointCount", T.IntegerType()),
        T.StructField("routeMappingReady", T.BooleanType()),
    ]
    + [
        T.StructField(name, T.ArrayType(T.StringType()))
        for name in (
            "roadNames",
            "affectedBusRoutes",
            "affectedBusStops",
            "rejectionReasons",
            "qualityFlags",
            "missingFields",
        )
    ]
    + [T.StructField("routePoints", T.ArrayType(POINT)), T.StructField("routeLegs", T.ArrayType(LEG))]
)


def silver_uri(env):
    value = env.get("TOPIS_SILVER_OUTPUT_URI", "").strip().rstrip("/")
    if not value:
        return None
    parsed = urlsplit(value)
    if parsed.scheme not in ("hdfs", "file") or not parsed.path or (parsed.scheme == "hdfs" and not parsed.netloc):
        log.error("Invalid TOPIS_SILVER_OUTPUT_URI; HDFS disabled while Redis remains enabled")
        return None
    return value


def clients():
    return (
        GmsClient(
            os.environ.get("GMS_API_KEY"),
            os.environ.get("GMS_BASE_URL", "https://gms.ssafy.io/gmsapi/"),
            os.environ.get("GMS_MODEL", "gpt-4o-mini"),
            int(os.environ.get("TOPIS_AI_TIMEOUT_SECONDS", "30")),
        ),
        KakaoGeocoder(os.environ.get("KAKAO_REST_API_KEY")),
    )


def enrich_batch(batch, gms, geocoder):
    raw = [
        row.rawEventJson for row in batch.filter(F.col("eventType") == "topis.notice").select("rawEventJson").collect()
    ]
    unique, rows = {}, []
    for value in raw:
        try:
            event = json.loads(value)
            payload_raw = (event.get("payload") or {}).get("raw") or {}
            key = (
                (event.get("payload") or {}).get("providerNoticeId"),
                json.dumps(payload_raw, sort_keys=True, ensure_ascii=False),
            )
            unique[key] = event
        except (ValueError, TypeError):
            continue
    for event in unique.values():
        rows.extend(normalize_notice(event, gms, geocoder))
    return batch.sparkSession.createDataFrame(rows, SCHEMA) if rows else batch.sparkSession.createDataFrame([], SCHEMA)


def redis_rows(df):
    valid_until = F.to_timestamp("validUntil")
    eligible = df.filter(
        F.col("processingStatus").isin("VALID", "PARTIAL")
        & (F.col("eventStatus") != "ENDED")
        & F.col("eventId").isNotNull()
        & (valid_until > F.current_timestamp())
    )
    collected = F.date_format(F.to_timestamp("collectedAt"), "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    json_fields = [F.col(x) for x in df.columns] + [F.col("collectedAt").alias("_latestOrderAt")]
    return eligible.select(
        F.concat(F.lit("traffic:latest:topis-event:"), "eventId").alias("redisKey"),
        F.to_json(F.struct(*json_fields), {"ignoreNullFields": "false"}).alias("redisValue"),
        F.least(F.lit(2592000), F.greatest(F.lit(60), valid_until.cast("long") - F.current_timestamp().cast("long")))
        .cast("int")
        .alias("ttlSeconds"),
        collected.alias("occurredAtOrder"),
        collected.alias("collectedAtOrder"),
    )


def prepare(spark, settings):
    """스트림을 준비하고 (필수 Redis 시작 함수, 선택 Silver 시작 함수)를 돌려준다.

    main() 과 합친 실행기(light_streaming.py)가 같이 쓴다. 쿼리 이름·체크포인트 경로는 그대로라
    어느 쪽으로 실행해도 읽던 자리부터 이어서 읽는다.
    """
    target = silver_uri(os.environ)
    gms, geocoder = clients()
    events = read_events_stream(spark, settings, TOPIC)

    def redis_batch(frame, batch_id):
        normalized = enrich_batch(frame, gms, geocoder).cache()
        try:
            plans = build_reconciliation_plans(
                normalized.select(
                    "noticeId", "eventId", "eventStatus", "validUntil", "processingStatus", "collectedAt"
                ).collect()
            )
            rows = redis_rows(normalized).cache()
            try:
                count = rows.count()
                if count:
                    write_latest_with_row_ttl(rows, settings.redis_host, settings.redis_port, settings.redis_password)
                deleted = reconcile_topis_latest(
                    plans, settings.redis_host, settings.redis_port, settings.redis_password
                )
                log.info(
                    "batch=%d topisRows=%d redisKeys=%d redisDeleted=%d", batch_id, normalized.count(), count, deleted
                )
            finally:
                rows.unpersist()
        finally:
            normalized.unpersist()

    def start_redis():
        return (
            events.writeStream.foreachBatch(redis_batch)
            .option("checkpointLocation", settings.checkpoint_for(JOB))
            .trigger(processingTime="30 seconds")
            .queryName(JOB)
            .start()
        )

    start_silver = None
    if target:

        def silver_batch(frame, batch_id):
            normalized = enrich_batch(frame, gms, geocoder).withColumn("eventDate", F.to_date("collectedAt"))
            normalized.write.mode("overwrite").partitionBy("processingStatus", "eventDate").parquet(
                target + "/batches/batch=" + str(batch_id) + "/data"
            )

        def start_silver_query():
            return (
                events.writeStream.foreachBatch(silver_batch)
                .option("checkpointLocation", settings.checkpoint_for(JOB + "_silver"))
                .trigger(processingTime="10 minutes")
                .queryName(JOB + "_silver")
                .start()
            )

        start_silver = start_silver_query
    else:
        log.warning("TOPIS_SILVER_OUTPUT_URI is empty; HDFS disabled while Redis remains enabled")
    return start_redis, start_silver


def main():
    logging.basicConfig(level=logging.INFO, stream=sys.stdout, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    settings = Settings.from_env()
    spark = (
        SparkSession.builder.appName("ddubi-" + JOB).config("spark.sql.session.timeZone", "Asia/Seoul").getOrCreate()
    )
    start_redis, start_silver = prepare(spark, settings)
    try:
        supervise(start_redis, start_silver)
    finally:
        spark.stop()


if __name__ == "__main__":
    main()
