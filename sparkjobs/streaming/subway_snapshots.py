"""지하철 최신 이벤트를 Backend가 한 키로 읽는 역·노선 Snapshot으로 묶는다.

개별 최신 키는 시간표 열차를 정확히 알고 있을 때 유용하지만, 역이나 노선의 현재 열차를
한꺼번에 보려면 Redis 전체 검색이 필요해진다. 이 모듈은 같은 최신 이벤트를 작은 JSON
도시락으로 추가 저장한다. Redis sink가 기존 도시락과 열차별로 원자 병합하므로 Spark
micro-batch 경계가 한 수집 사이클을 나눠도 먼저 도착한 열차가 사라지지 않는다.
"""

from __future__ import annotations

from common.redis_sink import (
    COLLECTED_TIME_COL,
    EVENT_TIME_COL,
    KEY_COL,
    MAX_ITEMS_COL,
    TTL_COL,
    VALUE_COL,
)
from pyspark.sql import DataFrame
from pyspark.sql import functions as F

SCHEMA_VERSION = "subway-snapshot-v1"
ARRIVAL_PREFIX = "transit:snapshot:subway-arrivals"
POSITION_PREFIX = "transit:snapshot:subway-positions"
ARRIVAL_TTL_SECONDS = 1080
POSITION_TTL_SECONDS = 10800
FRESHNESS_SECONDS = 120
ARRIVAL_MAX_ITEMS = 128
POSITION_MAX_ITEMS = 512
_TIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"


def _time(value):
    return F.date_format(value, _TIME_FORMAT)


def _valid_until():
    return (F.col("occurredAt").cast("long") + F.lit(FRESHNESS_SECONDS)).cast("timestamp")


def arrival_snapshot_rows(latest: DataFrame) -> DataFrame:
    """호선·역별 도착 열차 배열을 Redis 쓰기 행으로 만든다."""

    rows = (
        latest.filter(F.col("eventType") == "subway.arrival")
        .filter(
            F.col("providerIds.lineId").isNotNull()
            & F.col("providerIds.stationId").isNotNull()
            & F.col("providerIds.trainId").isNotNull()
            & F.col("occurredAt").isNotNull()
            & F.col("collectedAt").isNotNull()
        )
        .withColumn("lineId", F.col("providerIds.lineId"))
        .withColumn("stationId", F.col("providerIds.stationId"))
        .withColumn("itemValidUntil", _valid_until())
        .withColumn(
            "snapshotItem",
            F.struct(
                F.col("observation.arrivalSeconds").alias("arrivalSeconds"),
                F.col("providerIds.trainId").alias("trainId"),
                F.col("observation.arrivalStatusCode").alias("arrivalStatusCode"),
                F.col("observation.arrivalMessage").alias("arrivalMessage"),
                F.col("observation.previousStationMessage").alias("previousStationMessage"),
                F.col("observation.arrivalDirection").alias("direction"),
                F.col("observation.arrivalDestination").alias("destination"),
                F.col("observation.arrivalTrainType").alias("trainType"),
                F.col("observation.sourceGeneratedAt").alias("sourceGeneratedAt"),
                _time(F.col("occurredAt")).alias("occurredAt"),
                _time(F.col("collectedAt")).alias("collectedAt"),
                F.col("eventId").alias("eventId"),
                _time(F.col("itemValidUntil")).alias("validUntil"),
            ),
        )
    )
    grouped = rows.groupBy("lineId", "stationId").agg(
        F.max("occurredAt").alias("snapshotObservedAt"),
        F.max("collectedAt").alias("snapshotCollectedAt"),
        F.max("itemValidUntil").alias("snapshotValidUntil"),
        F.sort_array(F.collect_list("snapshotItem")).alias("items"),
    )
    return grouped.select(
        F.concat_ws(":", F.lit(ARRIVAL_PREFIX), F.col("lineId"), F.col("stationId")).alias(KEY_COL),
        F.to_json(
            F.struct(
                F.lit(SCHEMA_VERSION).alias("schemaVersion"),
                F.lit("SUBWAY_ARRIVALS_BY_STATION").alias("snapshotType"),
                F.struct(F.col("lineId"), F.col("stationId")).alias("scope"),
                _time(F.col("snapshotObservedAt")).alias("observedAt"),
                _time(F.col("snapshotCollectedAt")).alias("collectedAt"),
                _time(F.col("snapshotValidUntil")).alias("validUntil"),
                F.lit(FRESHNESS_SECONDS).alias("freshnessSeconds"),
                F.lit(ARRIVAL_TTL_SECONDS).alias("retentionSeconds"),
                F.size("items").alias("itemCount"),
                F.lit("spark.realtime_latest").alias("source"),
                F.col("items"),
            )
        ).alias(VALUE_COL),
        F.lit(ARRIVAL_TTL_SECONDS).cast("int").alias(TTL_COL),
        F.lit(ARRIVAL_MAX_ITEMS).cast("int").alias(MAX_ITEMS_COL),
        _time(F.col("snapshotObservedAt")).alias(EVENT_TIME_COL),
        _time(F.col("snapshotCollectedAt")).alias(COLLECTED_TIME_COL),
    )


def position_snapshot_rows(latest: DataFrame) -> DataFrame:
    """호선별 운행 열차 위치 배열을 Redis 쓰기 행으로 만든다."""

    rows = (
        latest.filter(F.col("eventType") == "subway.position")
        .filter(
            F.col("providerIds.lineId").isNotNull()
            & F.col("providerIds.trainId").isNotNull()
            & F.col("occurredAt").isNotNull()
            & F.col("collectedAt").isNotNull()
        )
        .withColumn("lineId", F.col("providerIds.lineId"))
        .withColumn("itemValidUntil", _valid_until())
        .withColumn(
            "snapshotItem",
            F.struct(
                F.col("providerIds.trainId").alias("trainId"),
                F.col("providerIds.stationId").alias("stationId"),
                F.col("observation.stationName").alias("stationName"),
                F.col("observation.direction").alias("direction"),
                F.col("observation.destinationStation").alias("destination"),
                F.col("observation.trainStatusCode").alias("trainStatusCode"),
                F.col("observation.sourceGeneratedAt").alias("sourceGeneratedAt"),
                _time(F.col("occurredAt")).alias("occurredAt"),
                _time(F.col("collectedAt")).alias("collectedAt"),
                F.col("eventId").alias("eventId"),
                _time(F.col("itemValidUntil")).alias("validUntil"),
            ),
        )
    )
    grouped = rows.groupBy("lineId").agg(
        F.max("occurredAt").alias("snapshotObservedAt"),
        F.max("collectedAt").alias("snapshotCollectedAt"),
        F.max("itemValidUntil").alias("snapshotValidUntil"),
        F.sort_array(F.collect_list("snapshotItem")).alias("items"),
    )
    return grouped.select(
        F.concat_ws(":", F.lit(POSITION_PREFIX), F.col("lineId")).alias(KEY_COL),
        F.to_json(
            F.struct(
                F.lit(SCHEMA_VERSION).alias("schemaVersion"),
                F.lit("SUBWAY_POSITIONS_BY_LINE").alias("snapshotType"),
                F.struct(F.col("lineId")).alias("scope"),
                _time(F.col("snapshotObservedAt")).alias("observedAt"),
                _time(F.col("snapshotCollectedAt")).alias("collectedAt"),
                _time(F.col("snapshotValidUntil")).alias("validUntil"),
                F.lit(FRESHNESS_SECONDS).alias("freshnessSeconds"),
                F.lit(POSITION_TTL_SECONDS).alias("retentionSeconds"),
                F.size("items").alias("itemCount"),
                F.lit("spark.realtime_latest").alias("source"),
                F.col("items"),
            )
        ).alias(VALUE_COL),
        F.lit(POSITION_TTL_SECONDS).cast("int").alias(TTL_COL),
        F.lit(POSITION_MAX_ITEMS).cast("int").alias(MAX_ITEMS_COL),
        _time(F.col("snapshotObservedAt")).alias(EVENT_TIME_COL),
        _time(F.col("snapshotCollectedAt")).alias(COLLECTED_TIME_COL),
    )


def snapshot_rows(latest: DataFrame) -> DataFrame:
    """도착·위치 Snapshot 행을 같은 Redis sink 스키마로 합친다."""

    return arrival_snapshot_rows(latest).unionByName(position_snapshot_rows(latest))
