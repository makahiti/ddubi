"""Kafka city.realtime -> typed City Silver -> Redis JSON and optional HDFS Parquet."""

from __future__ import annotations

import json
import logging
import os
import sys
from dataclasses import dataclass
from urllib.parse import urlsplit

from common.city_realtime_normalizer import deduplicate, normalize_event
from common.city_realtime_runtime import supervise
from common.kafka_source import read_events_stream
from common.redis_sink import write_latest_with_row_ttl
from common.settings import Settings
from pyspark.sql import DataFrame, SparkSession
from pyspark.sql import functions as F
from pyspark.sql import types as T

JOB = "city_realtime_silver"
TOPIC = "city.raw"
RETENTION_SECONDS = 64800
log = logging.getLogger(JOB)

GEOMETRY = T.StructType(
    [
        T.StructField("type", T.StringType()),
        T.StructField("coordinates", T.ArrayType(T.ArrayType(T.DoubleType()))),
    ]
)


def _schema(strings=(), longs=(), doubles=(), integers=(), booleans=(), arrays=(), geometry=False):
    fields = [T.StructField(name, T.StringType()) for name in strings]
    fields += [T.StructField(name, T.LongType()) for name in longs]
    fields += [T.StructField(name, T.DoubleType()) for name in doubles]
    fields += [T.StructField(name, T.IntegerType()) for name in integers]
    fields += [T.StructField(name, T.BooleanType()) for name in booleans]
    fields += [T.StructField(name, T.ArrayType(T.StringType())) for name in arrays]
    if geometry:
        fields.append(T.StructField("geometry", GEOMETRY))
    return T.StructType(fields)


COMMON_STRINGS = (
    "silverVersion",
    "eventId",
    "contentHash",
    "providerAreaId",
    "areaName",
    "observedAt",
    "timestampBasis",
    "validUntil",
    "collectedAt",
    "processingStatus",
    "rawPayloadJson",
)
AREA_SCHEMA = _schema(
    strings=COMMON_STRINGS
    + ("populationObservedAt", "roadTrafficObservedAt", "congestionLevelRaw", "congestionLevel", "congestionMessage"),
    longs=("dataAgeSeconds", "populationMin", "populationMax", "populationMid"),
    doubles=("avgRoadSpeedKph",),
    integers=("congestionScore",),
    booleans=("isStale",),
    arrays=("missingFields", "qualityFlags"),
)
ROAD_SCHEMA = _schema(
    strings=COMMON_STRINGS
    + (
        "linkNamespace",
        "linkId",
        "roadName",
        "startNodeId",
        "startNodeName",
        "endNodeId",
        "endNodeName",
        "trafficStatusRaw",
        "trafficStatus",
        "coordinateCrs",
        "coordinateOrder",
        "coordinateStatus",
        "geometryStatus",
    ),
    longs=("dataAgeSeconds",),
    doubles=(
        "distanceMeters",
        "speedKph",
        "startLongitude",
        "startLatitude",
        "endLongitude",
        "endLatitude",
        "representativeLongitude",
        "representativeLatitude",
    ),
    booleans=("isStale",),
    arrays=("missingFields", "qualityFlags"),
    geometry=True,
)
FORECAST_SCHEMA = _schema(
    strings=(
        "silverVersion",
        "eventId",
        "contentHash",
        "providerAreaId",
        "areaName",
        "forecastCreatedAt",
        "forecastAt",
        "timestampBasis",
        "collectedAt",
        "congestionLevelRaw",
        "congestionLevel",
        "processingStatus",
        "rawPayloadJson",
    ),
    longs=("populationMin", "populationMax", "populationMid"),
    integers=("congestionScore",),
    arrays=("missingFields", "qualityFlags"),
)
REJECTED_SCHEMA = _schema(
    strings=(
        "silverVersion",
        "eventId",
        "recordType",
        "providerAreaId",
        "collectedAt",
        "processingStatus",
        "rawPayloadJson",
    ),
    arrays=("rejectionReasons",),
)
QUALITY_SCHEMA = _schema(
    strings=("silverVersion", "processedAt"),
    longs=(
        "batchId",
        "inputCount",
        "validCount",
        "partialCount",
        "rejectedCount",
        "duplicateCount",
        "areaCount",
        "roadCount",
        "forecastCount",
    ),
)

TIME_COLUMNS = {
    "area": ("observedAt", "populationObservedAt", "roadTrafficObservedAt", "validUntil", "collectedAt"),
    "roads": ("observedAt", "validUntil", "collectedAt"),
    "forecasts": ("forecastCreatedAt", "forecastAt", "collectedAt"),
    "rejected": ("collectedAt",),
}


@dataclass
class CityFrames:
    area: DataFrame
    roads: DataFrame
    forecasts: DataFrame
    rejected: DataFrame
    quality: DataFrame


def output_uri(env) -> str | None:
    value = env.get("CITY_SILVER_OUTPUT_URI", "").strip().rstrip("/")
    if not value:
        return None
    parsed = urlsplit(value)
    if parsed.scheme not in ("hdfs", "file") or not parsed.path or (parsed.scheme == "hdfs" and not parsed.netloc):
        log.error("Invalid CITY_SILVER_OUTPUT_URI; HDFS disabled while Redis remains enabled")
        return None
    return value


def _cast_times(df: DataFrame, names: tuple[str, ...]) -> DataFrame:
    for name in names:
        df = df.withColumn(name, F.to_timestamp(name))
    return df


def normalize_batch(batch: DataFrame, batch_id: int, freshness_seconds: int = 900) -> CityFrames:
    values = batch.filter(F.col("eventType") == "city.realtime").select("rawEventJson").collect()
    areas, roads, forecasts, rejected = [], [], [], []
    for item in values:
        try:
            event = json.loads(item.rawEventJson)
            normalized = normalize_event(event, freshness_seconds)
            areas.extend(normalized.area)
            roads.extend(normalized.roads)
            forecasts.extend(normalized.forecasts)
            rejected.extend(normalized.rejected)
        except Exception as exc:
            log.warning("city event rejected after normalization error: %s", type(exc).__name__, exc_info=True)
            rejected.append(
                {
                    "silverVersion": "city-realtime-v1",
                    "eventId": None,
                    "recordType": "ENVELOPE",
                    "providerAreaId": None,
                    "collectedAt": None,
                    "processingStatus": "REJECTED",
                    "rawPayloadJson": str(item.rawEventJson),
                    "rejectionReasons": ["NORMALIZATION_ERROR:" + type(exc).__name__],
                }
            )

    areas, area_dupes = deduplicate(areas, ("providerAreaId", "observedAt"))
    roads, road_dupes = deduplicate(roads, ("linkNamespace", "linkId", "observedAt"))
    forecasts, forecast_dupes = deduplicate(forecasts, ("providerAreaId", "forecastCreatedAt", "forecastAt"))
    spark = batch.sparkSession

    def frame(rows, schema, kind):
        return _cast_times(spark.createDataFrame(rows, schema), TIME_COLUMNS[kind])

    all_rows = areas + roads + forecasts
    valid = sum(row.get("processingStatus") == "VALID" for row in all_rows)
    partial = sum(row.get("processingStatus") == "PARTIAL" for row in all_rows)
    quality = [
        {
            "silverVersion": "city-realtime-v1",
            "batchId": int(batch_id),
            "processedAt": None,
            "inputCount": len(values),
            "validCount": valid,
            "partialCount": partial,
            "rejectedCount": len(rejected),
            "duplicateCount": area_dupes + road_dupes + forecast_dupes,
            "areaCount": len(areas),
            "roadCount": len(roads),
            "forecastCount": len(forecasts),
        }
    ]
    quality_df = spark.createDataFrame(quality, QUALITY_SCHEMA).withColumn("processedAt", F.current_timestamp())
    return CityFrames(
        frame(areas, AREA_SCHEMA, "area"),
        frame(roads, ROAD_SCHEMA, "roads"),
        frame(forecasts, FORECAST_SCHEMA, "forecasts"),
        frame(rejected, REJECTED_SCHEMA, "rejected"),
        quality_df,
    )


def redis_rows(frames: CityFrames) -> DataFrame:
    def rows(df, key, order):
        eligible = df.filter(
            F.col("processingStatus").isin("VALID", "PARTIAL")
            & F.col(order).isNotNull()
            & F.col("collectedAt").isNotNull()
            & ~F.array_contains(F.col("qualityFlags"), "PROVIDER_TIME_IN_FUTURE")
        )
        fields = [F.col(name) for name in df.columns] + [F.col(order).alias("_latestOrderAt")]
        return eligible.select(
            key.alias("redisKey"),
            F.to_json(F.struct(*fields), {"ignoreNullFields": "false"}).alias("redisValue"),
            F.lit(RETENTION_SECONDS).cast("int").alias("ttlSeconds"),
            F.date_format(order, "yyyy-MM-dd'T'HH:mm:ss.SSSXXX").alias("occurredAtOrder"),
            F.date_format("collectedAt", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX").alias("collectedAtOrder"),
        )

    area = rows(frames.area, F.concat(F.lit("city:latest:area:"), F.col("providerAreaId")), "observedAt")
    roads = rows(frames.roads, F.concat_ws(":", F.lit("city:latest:road"), "linkNamespace", "linkId"), "observedAt")
    forecasts = rows(
        frames.forecasts,
        F.concat_ws(
            ":", F.lit("city:forecast:population"), "providerAreaId", F.date_format("forecastAt", "yyyyMMddHHmmss")
        ),
        "forecastCreatedAt",
    )
    return area.unionByName(roads).unionByName(forecasts)


def write_silver(frames: CityFrames, batch_id: int, target: str, namespace: str = "live") -> None:
    batch_root = f"{target}/{namespace.strip('/')}/batches/batch={batch_id}"
    outputs = {
        "area-snapshot": frames.area,
        "road-link-snapshot": frames.roads,
        "population-forecast": frames.forecasts,
        "rejected": frames.rejected,
        "quality": frames.quality,
    }
    for name, df in outputs.items():
        if not df.isEmpty():
            enriched = df
            if "collectedAt" in df.columns:
                enriched = enriched.withColumn("eventDate", F.to_date("collectedAt")).withColumn(
                    "eventHour", F.hour("collectedAt")
                )
            partitions = [name for name in ("processingStatus", "eventDate", "eventHour") if name in enriched.columns]
            writer = enriched.write.mode("overwrite")
            if partitions:
                writer = writer.partitionBy(*partitions)
            writer.parquet(f"{batch_root}/{name}")
    frames.area.sparkSession.createDataFrame([(int(batch_id),)], ["batchId"]).write.mode("overwrite").json(
        f"{batch_root}/complete"
    )


def prepare(spark: SparkSession, settings: Settings):
    """스트림을 준비하고 (필수 Redis 시작 함수, 선택 Silver 시작 함수)를 돌려준다.

    main() 과 합친 실행기(light_streaming.py)가 같이 쓴다. 쿼리 이름·체크포인트 경로는 그대로라
    어느 쪽으로 실행해도 읽던 자리부터 이어서 읽는다.
    """
    target = output_uri(os.environ)
    freshness = int(os.environ.get("CITY_SILVER_FRESHNESS_SECONDS", "900"))
    events = read_events_stream(spark, settings, TOPIC)

    def redis_batch(frame, batch_id):
        frames = normalize_batch(frame, batch_id, freshness)
        rows = redis_rows(frames).cache()
        try:
            count = rows.count()
            if count:
                write_latest_with_row_ttl(rows, settings.redis_host, settings.redis_port, settings.redis_password)
            log.info("batch=%d cityRedisKeys=%d", batch_id, count)
        finally:
            rows.unpersist()

    def start_redis():
        return (
            events.writeStream.foreachBatch(redis_batch)
            .option("checkpointLocation", settings.checkpoint_for(JOB + "_redis"))
            .trigger(processingTime="30 seconds")
            .queryName(JOB + "_redis")
            .start()
        )

    start_hdfs = None
    if target:

        def silver_batch(frame, batch_id):
            frames = normalize_batch(frame, batch_id, freshness)
            if frames.quality.first().inputCount:
                write_silver(frames, batch_id, target)

        def start_silver():
            return (
                events.writeStream.foreachBatch(silver_batch)
                .option("checkpointLocation", settings.checkpoint_for(JOB + "_hdfs"))
                .trigger(processingTime="10 minutes")
                .queryName(JOB + "_hdfs")
                .start()
            )

        start_hdfs = start_silver
    else:
        log.warning("CITY_SILVER_OUTPUT_URI is empty; HDFS disabled while Redis remains enabled")
    return start_redis, start_hdfs


def main() -> int:
    logging.basicConfig(level=logging.INFO, stream=sys.stdout, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    settings = Settings.from_env()
    spark = (
        SparkSession.builder.appName("ddubi-" + JOB).config("spark.sql.session.timeZone", "Asia/Seoul").getOrCreate()
    )
    spark.sparkContext.setLogLevel("WARN")
    start_redis, start_hdfs = prepare(spark, settings)
    try:
        supervise(start_redis, start_hdfs)
    finally:
        spark.stop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
