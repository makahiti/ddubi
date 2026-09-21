"""archiver 가 HDFS 에 쌓은 Parquet(아카이브)을 스트리밍과 같은 '봉투 DataFrame' 으로 읽는다.

아카이브 컬럼(archiver/src/buffer.py SCHEMA): kafka_key, value(원본 JSON), event_id, ..., kafka_partition, kafka_offset, kafka_timestamp
+ 경로 파티션 date=YYYY-MM-DD / hour=HH.
Kafka 원본 모양(key, value, partition, offset, timestamp)으로 되돌린 뒤 kafka_source.parse_envelope 를 그대로 태우므로,
스트리밍 잡의 transform 함수를 배치에서 재사용할 수 있다.
"""

from __future__ import annotations

from datetime import date, timedelta

from pyspark.sql import DataFrame, SparkSession
from pyspark.sql import functions as F

from common.kafka_source import parse_envelope


def read_archive_events(
    spark: SparkSession, archive_uri: str, topic: str, start: date | None = None, end: date | None = None
) -> DataFrame:
    """archive_uri/topic 아래 Parquet 을 읽어 봉투 DataFrame 으로 만든다. start~end(포함) 날짜로 파티션을 걸러 읽는다."""
    df = spark.read.parquet(f"{archive_uri.rstrip('/')}/{topic}")
    if start is not None:
        df = df.filter(F.col("date") >= start.isoformat())
    if end is not None:
        df = df.filter(F.col("date") <= end.isoformat())
    raw = df.select(
        F.col("kafka_key").cast("binary").alias("key"),
        F.col("value").cast("binary").alias("value"),
        F.col("kafka_partition").alias("partition"),
        F.col("kafka_offset").alias("offset"),
        F.col("kafka_timestamp").alias("timestamp"),
    )
    # archiver 는 재시작 구간을 중복 아카이브할 수 있으므로 eventId 로 한 번 걸러준다
    return parse_envelope(raw).dropDuplicates(["eventId"])


def lookback_window(today: date, days: int) -> tuple[date, date]:
    """오늘을 제외한 최근 days 일: [today - days, today - 1]"""
    return today - timedelta(days=days), today - timedelta(days=1)
