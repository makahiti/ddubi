"""Kafka traffic.incident -> verified link/coordinate Silver -> Redis JSON and optional HDFS Parquet."""

import json
import logging
import os
import sys
from urllib.parse import urlsplit

from common.kafka_source import read_events_stream
from common.redis_sink import write_latest_with_row_ttl
from common.settings import Settings
from common.traffic_incident_ai import enrich_rows, extractor_from_env
from common.traffic_incident_enrichment import VerifiedLinkMaster, normalize_incident
from common.traffic_incident_features import SNAPSHOT_KEY, build_incident_snapshot
from common.traffic_incident_policy import policy
from common.traffic_incident_runtime import supervise
from pyspark.sql import SparkSession
from pyspark.sql import functions as F
from pyspark.sql import types as T

JOB = "traffic_incident_silver"
TOPIC = "city.raw"
log = logging.getLogger(JOB)

STRINGS = (
    "silverVersion",
    "eventId",
    "source",
    "providerIncidentId",
    "contentHash",
    "collectedAt",
    "startedAt",
    "expectedClearAt",
    "validUntil",
    "lifecycleStatus",
    "incidentTypeCode",
    "incidentDetailTypeCode",
    "description",
    "inputLinkNamespace",
    "inputLinkId",
    "matchedLinkId",
    "matchedRoadName",
    "matchedDistrictName",
    "matchedStartNodeId",
    "matchedEndNodeId",
    "locationMatchType",
    "linkMasterStatus",
    "linkMasterVersion",
    "linkMasterEvidenceRef",
    "sourceCrs",
    "coordinateStatus",
    "descriptionRoadName",
    "descriptionDirection",
    "descriptionLaneControl",
    "descriptionRegion",
    "processingStatus",
    "rawPayloadJson",
    "aiExtractionStatus",
    "aiIncidentKind",
    "aiIncidentDetail",
    "aiControlScope",
    "aiDirectionText",
    "aiStatusMention",
    "aiTimeExpressionsJson",
    "aiControlStartAt",
    "aiControlEndAt",
    "aiEvidenceJson",
    "aiExtractedAt",
    "aiModel",
    "aiPromptVersion",
    "aiInputHash",
)
SCHEMA = T.StructType(
    [T.StructField(name, T.StringType()) for name in STRINGS]
    + [
        T.StructField(name, T.DoubleType())
        for name in ("grs80tmX", "grs80tmY", "latitude", "longitude", "linkCoordinateDistanceMeters")
    ]
    + [T.StructField("nearbyCandidateCount", T.IntegerType())]
    + [
        T.StructField("aiClosedLaneCount", T.IntegerType()),
        T.StructField("aiBusDetourMentioned", T.BooleanType()),
        T.StructField("aiLaneNumbers", T.ArrayType(T.IntegerType())),
    ]
    + [
        T.StructField(name, T.ArrayType(T.StringType()))
        for name in ("missingFields", "qualityFlags", "rejectionReasons", "aiQualityFlags")
    ]
)


def output_uri(env):
    value = env.get("TRAFFIC_INCIDENT_SILVER_OUTPUT_URI", "").strip().rstrip("/")
    if not value:
        return None
    parsed = urlsplit(value)
    if parsed.scheme not in ("hdfs", "file") or not parsed.path or (parsed.scheme == "hdfs" and not parsed.netloc):
        log.error("Invalid TRAFFIC_INCIDENT_SILVER_OUTPUT_URI; HDFS disabled while Redis remains enabled")
        return None
    return value


def load_master(env):
    return VerifiedLinkMaster.load(
        env.get("TRAFFIC_LINK_MASTER_PATH", "").strip(), env.get("TRAFFIC_LINK_MASTER_SHA256", "").strip()
    )


def enrich_batch(batch, master, stale_seconds, direct_tolerance, nearby_max, extractor=None):
    values = batch.filter(F.col("eventType") == "traffic.incident").select("rawEventJson").collect()
    unique, rows = {}, []
    for item in values:
        try:
            event = json.loads(item.rawEventJson)
            payload = event.get("payload") or {}
            key = (payload.get("providerIncidentId"), payload.get("contentHash") or item.rawEventJson)
            unique[key] = event
        except (TypeError, ValueError):
            continue
    for event in unique.values():
        rows.append(normalize_incident(event, master, stale_seconds, direct_tolerance, nearby_max))
    rows = enrich_rows(rows, extractor)
    return batch.sparkSession.createDataFrame(rows, SCHEMA) if rows else batch.sparkSession.createDataFrame([], SCHEMA)


def redis_rows(df):
    valid_until = F.to_timestamp("validUntil")
    eligible = df.filter(
        F.col("processingStatus").isin("VALID", "PARTIAL")
        & F.col("providerIncidentId").isNotNull()
        & (valid_until > F.current_timestamp())
    )
    collected = F.date_format(F.to_timestamp("collectedAt"), "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    # 공통 Redis sink가 지연 도착한 과거 관측으로 최신값을 되돌리지 않도록
    # 실제 사건 발생시각이 아니라 이 활성 스냅샷을 관측한 시각을 정렬 기준으로 함께 기록한다.
    json_fields = [F.col(name) for name in df.columns] + [F.col("collectedAt").alias("_latestOrderAt")]
    return eligible.select(
        F.concat(F.lit("traffic:latest:incident-silver:"), "providerIncidentId").alias("redisKey"),
        F.to_json(F.struct(*json_fields), {"ignoreNullFields": "false"}).alias("redisValue"),
        F.greatest(F.lit(60), valid_until.cast("long") - F.current_timestamp().cast("long"))
        .cast("int")
        .alias("ttlSeconds"),
        collected.alias("occurredAtOrder"),
        collected.alias("collectedAtOrder"),
    )


def main():
    logging.basicConfig(level=logging.INFO, stream=sys.stdout, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    incident_policy = policy(os.environ)
    settings, target = Settings.from_env(), output_uri(os.environ)
    master = load_master(os.environ)
    provisional_link_version = None
    if str(os.environ.get("ROAD_PROVISIONAL_MAPPING_ENABLED", "false")).lower() in ("1", "true", "yes"):
        provisional_link_version = os.environ.get(
            "ROAD_PROVISIONAL_TRAFFIC_LINK_MASTER_VERSION", "provisional-accinfo-input-v1"
        ).strip()
        if not provisional_link_version:
            raise ValueError("ROAD_PROVISIONAL_TRAFFIC_LINK_MASTER_VERSION is required in provisional mode")
    extractor = extractor_from_env(os.environ)
    if not master.available:
        log.warning("Verified traffic link master is unavailable; no DIRECT/NEARBY mapping will be produced")
    spark = (
        SparkSession.builder.appName("ddubi-" + JOB).config("spark.sql.session.timeZone", "Asia/Seoul").getOrCreate()
    )
    events = read_events_stream(spark, settings, TOPIC)

    def normalize(frame):
        return enrich_batch(
            frame,
            master,
            incident_policy.stale_seconds,
            incident_policy.direct_tolerance_meters,
            incident_policy.nearby_max_meters,
            extractor,
        )

    def redis_batch(frame, batch_id):
        normalized = normalize(frame).cache()
        try:
            rows = redis_rows(normalized).cache()
            try:
                count = rows.count()
                if count:
                    write_latest_with_row_ttl(rows, settings.redis_host, settings.redis_port, settings.redis_password)
                log.info("batch=%d incidentRows=%d redisKeys=%d", batch_id, normalized.count(), count)
            finally:
                rows.unpersist()
        finally:
            normalized.unpersist()
        snapshot_events = (
            frame.filter(F.col("eventType") == "traffic.incident.snapshot").select("rawEventJson").collect()
        )
        if snapshot_events:
            from datetime import datetime, timezone

            import redis

            client = redis.Redis(
                host=settings.redis_host,
                port=settings.redis_port,
                password=settings.redis_password or None,
                decode_responses=True,
            )
            try:
                for item in snapshot_events:
                    snapshot = build_incident_snapshot(
                        json.loads(item.rawEventJson),
                        master,
                        incident_policy.stale_seconds,
                        extractor=extractor,
                        direct_tolerance=incident_policy.direct_tolerance_meters,
                        nearby_max=incident_policy.nearby_max_meters,
                        provisional_link_version=provisional_link_version,
                    )
                    if snapshot is None:
                        continue
                    ttl = int(
                        (datetime.fromisoformat(snapshot["validUntil"]) - datetime.now(timezone.utc)).total_seconds()
                    )
                    if ttl <= 0:
                        continue
                    # A delayed Kafka batch must not replace a newer complete poll.
                    client.eval(
                        "local old=redis.call('GET',KEYS[1]); if old and cjson.decode(old).collectedAt >= ARGV[1] then return 0 end; "
                        "redis.call('SET',KEYS[1],ARGV[2],'EX',ARGV[3]); return 1",
                        1,
                        SNAPSHOT_KEY,
                        snapshot["collectedAt"],
                        json.dumps(snapshot, ensure_ascii=False),
                        ttl,
                    )
            finally:
                client.close()

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
            normalized = normalize(frame).withColumn("eventDate", F.to_date("collectedAt"))
            batch_path = target + "/batches/batch=" + str(batch_id)
            normalized.write.mode("overwrite").partitionBy(
                "processingStatus", "locationMatchType", "eventDate"
            ).parquet(batch_path + "/data")
            spark.createDataFrame([(int(batch_id),)], ["batchId"]).write.mode("overwrite").json(
                batch_path + "/complete"
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
        log.warning("TRAFFIC_INCIDENT_SILVER_OUTPUT_URI is empty; HDFS disabled while Redis remains enabled")
    try:
        supervise(start_redis, start_silver)
    finally:
        spark.stop()


if __name__ == "__main__":
    main()
