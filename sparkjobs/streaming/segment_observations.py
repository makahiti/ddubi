"""검증 대상 실측 계약을 Kafka에서 원문·offset과 함께 Parquet으로 보존한다.

별도 segment.observation 토픽은 실제 통과시각 원천을 확보한 생산자만 사용한다.
기존 도착 ETA나 8시간 위치 스냅샷을 여기로 변환하지 않는다. 잘못된 JSON도 rawValue와
함께 보존하고 segment_facts.validate가 status/baseline 계산에서 reason으로 격리한다.
Spark file sink/checkpoint가 동일 offset 재실행의 파일 commit을 관리한다.
"""

import os
from pyspark.sql import SparkSession, functions as F
from common.settings import Settings
from common.segment_facts import FACT_SCHEMA


def parse(raw):
    """평문 실측 JSON의 필드를 명시 스키마로 읽되 Kafka 원본과 수신 시각은 삭제하지 않는다."""
    return raw.select(
        F.from_json(F.col("value").cast("string"), FACT_SCHEMA).alias("fact"),
        F.col("value").cast("string").alias("rawValue"),
        F.col("key").cast("string").alias("kafkaKey"),
        "partition",
        "offset",
        "timestamp",
    ).select("fact.*", "rawValue", "kafkaKey", "partition", "offset", "timestamp")


def main():
    settings = Settings.from_env()
    output = os.environ["SEGMENT_FACTS_PATH"]
    spark = (
        SparkSession.builder.appName("ddubi-segment-observations")
        .config("spark.sql.session.timeZone", "Asia/Seoul")
        .getOrCreate()
    )
    try:
        raw = (
            spark.readStream.format("kafka")
            .option("kafka.bootstrap.servers", settings.kafka_bootstrap_servers)
            .option("subscribe", "segment.observation")
            .option("startingOffsets", settings.starting_offsets)
            .option("failOnDataLoss", "true")
            .option("maxOffsetsPerTrigger", settings.max_offsets_per_trigger)
            .load()
        )
        query = (
            parse(raw)
            .writeStream.format("parquet")
            .option("path", output)
            .option(
                "checkpointLocation", settings.checkpoint_for("segment-observations")
            )
            .start()
        )
        query.awaitTermination()
    finally:
        spark.stop()


if __name__ == "__main__":
    main()
