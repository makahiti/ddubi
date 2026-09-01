"""Kafka 토픽을 계약 이벤트 봉투(docs/contracts/events)로 읽는다.

payload 는 소스마다 모양이 달라서 문자열(JSON 텍스트)로 남기고, 각 잡이 자기 스키마로 다시 파싱한다.
"""

from __future__ import annotations

from pyspark.sql import DataFrame, SparkSession
from pyspark.sql import functions as F
from pyspark.sql.types import ArrayType, StringType, StructField, StructType

from common.settings import Settings

# transport-event / city-event 공통 필드. region 은 transport 에만 있고, location·segmentIds 는 둘 다 선택이라 nullable.
ENVELOPE_SCHEMA = StructType(
    [
        StructField("eventId", StringType(), False),
        StructField("eventType", StringType(), False),
        StructField("source", StringType(), False),
        StructField("region", StringType(), True),
        StructField("occurredAt", StringType(), False),
        StructField("collectedAt", StringType(), False),
        StructField("location", StructType([
            StructField("latitude", StringType(), True),
            StructField("longitude", StringType(), True),
        ]), True),
        StructField("segmentIds", ArrayType(StringType()), True),  # 전처리 후 채워지는 뚜비 내부 구간 ID
        StructField("payload", StringType(), False),  # JSON 객체 → 원문 문자열
    ]
)


def parse_envelope(raw: DataFrame) -> DataFrame:
    """Kafka 원본(key, value, partition, offset, timestamp) → 봉투 컬럼. 배치·스트리밍 공용."""
    parsed = raw.select(
        F.col("key").cast("string").alias("kafkaKey"),
        F.col("partition").alias("kafkaPartition"),
        F.col("offset").alias("kafkaOffset"),
        F.col("timestamp").alias("kafkaTimestamp"),
        F.from_json(F.col("value").cast("string"), ENVELOPE_SCHEMA).alias("e"),
    )
    return parsed.select(
        "kafkaKey", "kafkaPartition", "kafkaOffset", "kafkaTimestamp",
        F.col("e.eventId").alias("eventId"),
        F.col("e.eventType").alias("eventType"),
        F.col("e.source").alias("source"),
        F.col("e.region").alias("region"),
        F.col("e.occurredAt").cast("timestamp").alias("occurredAt"),
        F.col("e.collectedAt").cast("timestamp").alias("collectedAt"),
        F.col("e.location.latitude").cast("double").alias("latitude"),
        F.col("e.location.longitude").cast("double").alias("longitude"),
        F.col("e.segmentIds").alias("segmentIds"),
        F.col("e.payload").alias("payload"),
    ).filter(F.col("eventId").isNotNull())  # 봉투 파싱에 실패한 메시지는 버린다


def read_events_stream(spark: SparkSession, settings: Settings, topic: str) -> DataFrame:
    raw = (
        spark.readStream.format("kafka")
        .option("kafka.bootstrap.servers", settings.kafka_bootstrap_servers)
        .option("subscribe", topic)
        .option("startingOffsets", settings.starting_offsets)
        .option("maxOffsetsPerTrigger", settings.max_offsets_per_trigger)
        # 보존 만료·토픽 재생성으로 오프셋이 사라져도 잡을 죽이지 않고 다음 데이터부터 이어간다
        .option("failOnDataLoss", "false")
        .load()
    )
    return parse_envelope(raw)
