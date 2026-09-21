"""city.raw 날씨 → Redis 최신값과 HDFS Weather Silver.

같은 표준 DataFrame에서 Redis는 격자(nx, ny)별 최신 관측을 10초 Micro Batch로 갱신하고,
HDFS는 새 표준 행을 기본 10분 단위로 묶어 Parquet Silver에 append한다. Redis 쓰기 직전에는
현재값과 시각을 원자 비교해 늦은 과거 관측이 최신값을 되돌리지 않게 한다. HDFS 스트림은
별도 checkpoint와 2일 watermark로 동일 내용 반복을 제거한다. 과거 Archive는 별도
batch/weather_silver_backfill.py로 복구하며 이 스트림에서 자동으로 읽지는 않는다.
"""

from __future__ import annotations

import logging
import os
import sys

from common.kafka_source import read_events_stream
from common.redis_sink import (
    COLLECTED_TIME_COL,
    EVENT_TIME_COL,
    KEY_COL,
    TTL_COL,
    VALUE_COL,
    write_latest_with_row_ttl,
)
from common.settings import Settings
from common.weather_normalizer import FEATURE_COLUMNS, FEATURE_VERSION, normalize_weather, to_weather_silver_stream
from common.weather_runtime import silver_configuration, supervise
from pyspark.sql import DataFrame, SparkSession, Window
from pyspark.sql import functions as F

# 실험(부하 시험)용 인스턴스는 환경변수로 토픽·잡 이름·키 접두사를 바꿔 띄운다.
# 기본값은 운영 잡(city.raw -> weather:latest) 과 같다.
JOB_NAME = os.environ.get("SPARK_JOB_NAME", "weather_latest")  # 체크포인트 경로·쿼리 이름
TOPIC = os.environ.get("SPARK_JOB_TOPIC", "city.raw")  # 읽을 토픽 (실험: city.replay)
EVENT_TYPE = "weather.observation"
REDIS_KEY_PREFIX = os.environ.get("REDIS_KEY_PREFIX", "weather:latest")  # 실험: weather:replay (운영 키 보호)
TTL_SECONDS = 7200  # redis-keys.yaml weatherLatest.ttlSeconds
TRIGGER = "10 seconds"
SILVER_TRIGGER = os.environ.get("WEATHER_SILVER_TRIGGER", "10 minutes")
SILVER_WATERMARK = os.environ.get("WEATHER_SILVER_WATERMARK", "2 days")

log = logging.getLogger(JOB_NAME)


def transform(events: DataFrame) -> DataFrame:
    """봉투 → HDFS Silver와 같은 이름·단위의 표준 날씨 컬럼."""
    return normalize_weather(events)


def latest_per_grid(df: DataFrame) -> DataFrame:
    """한 배치 안에서 격자별 최신 1건을 고르고 시각 불명 관측은 서빙에서 제외한다."""
    window = Window.partitionBy("nx", "ny").orderBy(F.col("observedAt").desc(), F.col("collectedAt").desc())
    return (
        df.filter(F.col("observedAt").isNotNull() & F.col("collectedAt").isNotNull())
        .withColumn("rn", F.row_number().over(window))
        .filter(F.col("rn") == 1)
        .drop("rn")
    )


def to_redis_rows(df: DataFrame) -> DataFrame:
    """표준 컬럼을 최신값 JSON으로 만든다. observations는 기존 소비자 호환용으로 유지한다."""
    return df.select(
        F.concat_ws(":", F.lit(REDIS_KEY_PREFIX), F.col("nx"), F.col("ny")).alias(KEY_COL),
        F.to_json(
            F.struct(
                "districts",
                "nx",
                "ny",
                "baseDate",
                "baseTime",
                "observations",
                F.col("observedAt").alias("occurredAt"),
                "collectedAt",
                "validUntil",
                F.lit(FEATURE_VERSION).alias("featureVersion"),
                F.struct(*(F.col(name) for name in FEATURE_COLUMNS)).alias("features"),
                "missingFields",
                "eventId",
                "source",
            )
        ).alias(VALUE_COL),
        F.lit(TTL_SECONDS).cast("int").alias(TTL_COL),
        # Redis의 배치 간 최신성 비교가 문자열 정렬과 일치하도록 KST·밀리초 형식을 고정한다.
        F.date_format("observedAt", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX").alias(EVENT_TIME_COL),
        F.date_format("collectedAt", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX").alias(COLLECTED_TIME_COL),
    )


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s", stream=sys.stdout)
    silver_target = silver_configuration(os.environ)
    settings = Settings.from_env()
    spark = (
        SparkSession.builder.appName(f"ddubi-{JOB_NAME}")
        .config("spark.sql.session.timeZone", "Asia/Seoul")
        .getOrCreate()
    )
    spark.sparkContext.setLogLevel("WARN")

    def process_batch(batch_df: DataFrame, batch_id: int) -> None:
        rows = to_redis_rows(latest_per_grid(batch_df)).cache()
        try:
            count = rows.count()
            if count:
                write_latest_with_row_ttl(
                    rows,
                    settings.redis_host,
                    settings.redis_port,
                    settings.redis_password,
                )
            log.info("batch=%d grids=%d", batch_id, count)
        finally:
            rows.unpersist()

    events = read_events_stream(spark, settings, TOPIC)
    normalized = transform(events)

    def start_redis():
        query = (
            normalized.writeStream.foreachBatch(process_batch)
            .option("checkpointLocation", settings.checkpoint_for(JOB_NAME))
            .trigger(processingTime=TRIGGER)
            .queryName(JOB_NAME)
            .start()
        )
        log.info(
            "started topic=%s checkpoint=%s redis=%s:%s",
            TOPIC,
            settings.checkpoint_for(JOB_NAME),
            settings.redis_host,
            settings.redis_port,
        )
        return query

    start_silver_query = None
    if silver_target:
        silver_job_name = f"{JOB_NAME}_silver"

        def start_silver():
            query = (
                to_weather_silver_stream(normalized, SILVER_WATERMARK)
                .writeStream.format("parquet")
                .outputMode("append")
                .partitionBy("silverVersion", "eventDate", "eventHour")
                .option("checkpointLocation", settings.checkpoint_for(silver_job_name))
                .trigger(processingTime=SILVER_TRIGGER)
                .queryName(silver_job_name)
                .start(silver_target)
            )
            log.info(
                "silver stream started output=%s trigger=%s checkpoint=%s watermark=%s",
                silver_target,
                SILVER_TRIGGER,
                settings.checkpoint_for(silver_job_name),
                SILVER_WATERMARK,
            )
            return query

        start_silver_query = start_silver
    else:
        log.warning("SILVER_OUTPUT_URI is empty; HDFS Weather Silver streaming is disabled")

    log.info(
        "starting topic=%s checkpoint=%s redis=%s:%s silver=%s startingOffsets=%s (only for new checkpoints; archive is not consumed)",
        TOPIC,
        settings.checkpoint_for(JOB_NAME),
        settings.redis_host,
        settings.redis_port,
        silver_target,
        settings.starting_offsets,
    )
    try:
        supervise(start_redis, start_silver_query)
    finally:
        spark.stop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
