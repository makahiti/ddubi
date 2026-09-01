"""city.raw 의 weather.observation → Redis weather:latest:{nx}:{ny}

Kafka → Spark → Redis 실시간 경로의 첫 잡. 마이크로배치마다 격자(nx, ny)별로 가장 최신 관측
(occurredAt 이 가장 늦은 것, 같으면 collectedAt) 하나만 남겨 Redis 에 JSON 문자열로 쓴다.
키·TTL 은 docs/contracts/redis-keys.yaml (weatherLatest) 를 따른다.
"""

from __future__ import annotations

import logging
import sys

from pyspark.sql import DataFrame, SparkSession, Window
from pyspark.sql import functions as F
from pyspark.sql.types import ArrayType, DoubleType, IntegerType, MapType, StringType, StructField, StructType

from common.kafka_source import read_events_stream
from common.redis_sink import KEY_COL, VALUE_COL, write_with_ttl
from common.settings import Settings

JOB_NAME = "weather_latest"
TOPIC = "city.raw"
EVENT_TYPE = "weather.observation"
REDIS_KEY_PREFIX = "weather:latest"
TTL_SECONDS = 7200  # redis-keys.yaml weatherLatest.ttlSeconds
TRIGGER = "10 seconds"

# collector/src/sources/weather.py 가 만드는 payload.
# districts(배열) 가 현재 형식, district(단수) 는 2026-08-28 이전에 쌓인 옛 형식과의 호환용.
PAYLOAD_SCHEMA = StructType(
    [
        StructField("districts", ArrayType(StringType()), True),
        StructField("district", StringType(), True),
        StructField("nx", IntegerType(), False),
        StructField("ny", IntegerType(), False),
        StructField("baseDate", StringType(), True),
        StructField("baseTime", StringType(), True),
        StructField("observations", MapType(StringType(), DoubleType()), True),
    ]
)

log = logging.getLogger(JOB_NAME)


def transform(events: DataFrame) -> DataFrame:
    """봉투 → 기상 관측 컬럼. 배치·스트리밍 공용 (테스트는 배치로 검증)."""
    weather = events.filter(F.col("eventType") == EVENT_TYPE).withColumn(
        "p", F.from_json(F.col("payload"), PAYLOAD_SCHEMA)
    )
    return weather.select(
        "eventId", "source", "occurredAt", "collectedAt",
        F.coalesce(F.col("p.districts"), F.array(F.col("p.district"))).alias("districts"),
        F.col("p.nx").alias("nx"),
        F.col("p.ny").alias("ny"),
        F.col("p.baseDate").alias("baseDate"),
        F.col("p.baseTime").alias("baseTime"),
        F.col("p.observations").alias("observations"),
    ).filter(F.col("nx").isNotNull() & F.col("ny").isNotNull())


def latest_per_grid(df: DataFrame) -> DataFrame:
    """한 배치 안에서 격자별 최신 1건만."""
    window = Window.partitionBy("nx", "ny").orderBy(F.col("occurredAt").desc(), F.col("collectedAt").desc())
    return df.withColumn("rn", F.row_number().over(window)).filter(F.col("rn") == 1).drop("rn")


def to_redis_rows(df: DataFrame) -> DataFrame:
    """(redisKey, redisValue) 로 변환. 값은 백엔드가 그대로 쓸 수 있는 JSON."""
    return df.select(
        F.concat_ws(":", F.lit(REDIS_KEY_PREFIX), F.col("nx"), F.col("ny")).alias(KEY_COL),
        F.to_json(
            F.struct(
                "districts", "nx", "ny", "baseDate", "baseTime", "observations",
                "occurredAt", "collectedAt", "eventId", "source",
            )
        ).alias(VALUE_COL),
    )


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s", stream=sys.stdout)
    settings = Settings.from_env()
    spark = SparkSession.builder.appName(f"ddubi-{JOB_NAME}").getOrCreate()
    spark.sparkContext.setLogLevel("WARN")

    def process_batch(batch_df: DataFrame, batch_id: int) -> None:
        rows = to_redis_rows(latest_per_grid(batch_df)).cache()
        try:
            count = rows.count()
            if count:
                write_with_ttl(rows, TTL_SECONDS, settings.redis_host, settings.redis_port, settings.redis_password)
            log.info("batch=%d grids=%d", batch_id, count)
        finally:
            rows.unpersist()

    events = read_events_stream(spark, settings, TOPIC)
    query = (
        transform(events)
        .writeStream.foreachBatch(process_batch)
        .option("checkpointLocation", settings.checkpoint_for(JOB_NAME))
        .trigger(processingTime=TRIGGER)
        .queryName(JOB_NAME)
        .start()
    )
    log.info("started topic=%s checkpoint=%s redis=%s:%s", TOPIC, settings.checkpoint_for(JOB_NAME), settings.redis_host, settings.redis_port)
    query.awaitTermination()
    return 0


if __name__ == "__main__":
    sys.exit(main())
