"""Shared Bus Silver rows -> Parquet history and compatible Redis latest JSON."""
from urllib.parse import urlparse

from pyspark.sql import Window, functions as F
from batch.bus_silver import prepare
from common.bus_normalizer import ALL_FIELDS, POSITION, ARRIVAL
from common.redis_sink import write_latest_with_row_ttl


def output_uri(env):
    uri = env.get("BUS_SILVER_OUTPUT_URI", "").strip().rstrip("/")
    if urlparse(uri).scheme not in ("hdfs", "file"):
        raise RuntimeError("BUS_SILVER_OUTPUT_URI must be an explicit hdfs:// or file:// directory")
    return uri


def prepare_live(events, compatible):
    archive = events.select(F.col("rawEventJson").alias("value"),
        F.col("kafkaPartition").alias("kafka_partition"),
        F.col("kafkaOffset").alias("kafka_offset"),
        F.col("kafkaTimestamp").alias("kafka_timestamp"))
    _, checked = prepare(archive)
    # Mapping validation remains the existing realtime job's responsibility.
    mapping = compatible.select("eventId", "segmentIds", "canonicalMapping").dropDuplicates(["eventId"])
    result = checked.join(mapping, "eventId", "left")
    key = F.concat_ws(":", "providerRouteId", F.when(F.col("eventType") == "bus.position", F.col("providerVehicleId")).otherwise(F.col("providerStopId")))
    key = F.when(F.col("providerNamespace") == "SEOUL_BUS", key).otherwise(F.concat_ws(":", "providerNamespace", key))
    key = F.when((F.col("providerNamespace") == "INCHEON_API") & (F.col("eventType") == "bus.arrival"), F.concat_ws(":", key, "providerVehicleId")).otherwise(key)
    return result.withColumn("providerKey", key).withColumn("providerIds", F.struct(
        F.col("providerNamespace").alias("namespace"), F.col("providerRouteId").alias("routeId"),
        F.col("providerVehicleId").alias("vehicleId"), F.col("providerStopId").alias("stopId")))


def redis_rows(silver):
    accepted = silver.filter("status = 'ACCEPTED' AND repeatIndex = 1 AND providerKey IS NOT NULL")
    order_time = F.coalesce(F.to_timestamp("observedAt"), F.to_timestamp("collectedAt"))
    accepted = accepted.withColumn("orderTime", order_time)
    order = Window.partitionBy("eventType", "providerKey").orderBy(
        F.col("orderTime").desc(), F.to_timestamp("collectedAt").desc(),
        F.col("kafka_partition").desc(), F.col("kafka_offset").desc())
    latest = accepted.withColumn("rank", F.row_number().over(order)).filter("rank = 1")
    occurred = F.date_format(F.to_timestamp("observedAt"), "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    collected = F.date_format(F.to_timestamp("collectedAt"), "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    ordering = F.date_format("orderTime", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    observation = F.struct(*[F.col(name).alias(name) for name in dict(POSITION, **ARRIVAL)])
    prefix = F.when(F.col("eventType") == "bus.position", "transit:latest:bus-position").otherwise("transit:latest:bus-arrival")
    valid_until = F.date_format((F.to_timestamp("observedAt").cast("long") + 120).cast("timestamp"), "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    return latest.select(
        F.concat_ws(":", prefix, "providerKey").alias("redisKey"),
        F.to_json(F.struct(*[F.col(name) for name in ALL_FIELDS],
            "providerKey", "providerIds", "segmentIds", "canonicalMapping",
            (F.size("segmentIds") > 0).alias("canonicalMappingAvailable"),
            observation.alias("observation"), F.struct("latitude", "longitude").alias("location"),
            occurred.alias("occurredAt"), ordering.alias("_latestOrderAt"),
            valid_until.alias("validUntil"), F.lit(120).alias("freshnessSeconds"),
            F.lit(86400).alias("retentionSeconds")), {"ignoreNullFields": "false"}).alias("redisValue"),
        F.lit(86400).alias("ttlSeconds"), ordering.alias("occurredAtOrder"), collected.alias("collectedAtOrder"))


def write_bus_silver(silver, batch_id, root):
    """버스 Silver를 HDFS 이력으로만 쓴다. Redis는 쓰지 않는다.

    같은 checkpoint 배치를 다시 실행하면 그 배치 폴더만 덮어써 멱등적이다. 거절·중복 레코드도
    함께 남기며, 읽을 때 status와 repeatIndex로 거른다. 오류는 상위로 전파해 Silver 스트림이
    실패 배치를 건너뛰지 않게 한다. 반환하는 경로에 호출자가 완료 표식을 남긴다.
    """
    path = root + "/batches/batch=" + str(batch_id)
    silver.write.mode("overwrite").partitionBy("eventType", "providerNamespace", "archiveDate").parquet(path + "/data")
    return path


def write_bus_redis(silver, settings):
    """버스 최신값을 Redis에만 쓴다. HDFS는 쓰지 않는다.

    서비스가 읽는 값이라 쓰기 실패는 상위로 전파해 checkpoint가 실패 배치를 건너뛰지 않게 한다.
    """
    rows = redis_rows(silver).cache()
    try:
        count = rows.count()
        if count:
            write_latest_with_row_ttl(rows, settings.redis_host, settings.redis_port, settings.redis_password)
        return count
    finally:
        rows.unpersist()


def save_batch(silver, batch_id, root, settings):
    """(구) 한 스트림에서 HDFS 이력과 Redis 최신값을 함께 쓰던 경로.

    스트림을 Redis용과 Silver용으로 나눈 뒤 운영 코드는 write_bus_silver와 write_bus_redis를
    따로 부른다. 이 함수는 두 저장을 한 번에 검증하는 기존 테스트가 계속 쓰도록 남겨 둔다.
    """
    path = write_bus_silver(silver, batch_id, root)
    count = write_bus_redis(silver, settings)
    silver.sparkSession.createDataFrame([(batch_id, count)], "batchId long, redisKeys long").write.mode("overwrite").json(path + "/complete")
    return count
