"""기상청 초단기실황을 Silver와 Redis가 함께 쓰는 표준 컬럼으로 변환한다.

Collector payload의 T1H/RN1 같은 제공자 코드는 Archive 추적을 위해 남기고,
계산에는 temperatureC/precipitationMm 같은 단위가 드러나는 컬럼을 사용한다.
결측과 실제 0을 구분하며 기상청 결측 표식(-998 등)과 범위 밖 값은 null로 만든다.
"""

from __future__ import annotations

from pyspark.sql import DataFrame
from pyspark.sql import functions as F
from pyspark.sql.types import ArrayType, DoubleType, IntegerType, MapType, StringType, StructField, StructType

EVENT_TYPE = "weather.observation"
SILVER_VERSION = "weather-silver-v1"
FEATURE_VERSION = "weather-feature-v1"
FRESHNESS_SECONDS = 7200
SILVER_WATERMARK = "2 days"

PAYLOAD_SCHEMA = StructType(
    [
        StructField("districts", ArrayType(StringType()), True),
        StructField("district", StringType(), True),  # 2026-08-28 이전 호환
        StructField("nx", IntegerType(), False),
        StructField("ny", IntegerType(), False),
        StructField("baseDate", StringType(), True),
        StructField("baseTime", StringType(), True),
        StructField("observations", MapType(StringType(), DoubleType()), True),
    ]
)

FEATURE_COLUMNS = (
    "temperatureC",
    "humidityPercent",
    "precipitationMm",
    "precipitationTypeCode",
    "windSpeedMps",
    "windDirectionDegree",
    "windEastWestMps",
    "windNorthSouthMps",
)


def _number(observations, code: str, minimum: float, maximum: float):
    value = observations[code].cast("double")
    return F.when(value.between(minimum, maximum), value)


def normalize_weather(events: DataFrame) -> DataFrame:
    """Kafka/Archive 공통 봉투 DataFrame을 표준 날씨 DataFrame으로 바꾼다."""

    parsed = (
        events.filter(F.col("eventType") == EVENT_TYPE)
        .withColumn("weatherPayload", F.from_json(F.col("payload"), PAYLOAD_SCHEMA))
        .filter(F.col("weatherPayload.nx").isNotNull() & F.col("weatherPayload.ny").isNotNull())
    )
    observations = F.col("weatherPayload.observations")
    normalized = parsed.select(
        "eventId",
        "source",
        F.col("weatherPayload.nx").alias("nx"),
        F.col("weatherPayload.ny").alias("ny"),
        F.array_distinct(
            F.filter(
                F.coalesce(F.col("weatherPayload.districts"), F.array(F.col("weatherPayload.district"))),
                lambda district: district.isNotNull() & (F.trim(district) != ""),
            )
        ).alias("districts"),
        F.col("weatherPayload.baseDate").alias("baseDate"),
        F.col("weatherPayload.baseTime").alias("baseTime"),
        observations.alias("observations"),
        F.col("occurredAt").alias("observedAt"),
        F.col("collectedAt").alias("collectedAt"),
        _number(observations, "T1H", -80.0, 60.0).alias("temperatureC"),
        _number(observations, "REH", 0.0, 100.0).alias("humidityPercent"),
        _number(observations, "RN1", 0.0, 500.0).alias("precipitationMm"),
        F.when(observations["PTY"].isin(0.0, 1.0, 2.0, 3.0, 5.0, 6.0, 7.0), observations["PTY"].cast("int")).alias(
            "precipitationTypeCode"
        ),
        _number(observations, "WSD", 0.0, 100.0).alias("windSpeedMps"),
        _number(observations, "VEC", 0.0, 360.0).alias("windDirectionDegree"),
        _number(observations, "UUU", -100.0, 100.0).alias("windEastWestMps"),
        _number(observations, "VVV", -100.0, 100.0).alias("windNorthSouthMps"),
    ).filter(
        F.col("eventId").isNotNull()
        & F.col("source").isNotNull()
        & F.col("observedAt").isNotNull()
        & F.col("collectedAt").isNotNull()
        & (F.col("collectedAt") >= F.col("observedAt"))
    )

    missing = F.array(*(F.when(F.col(name).isNull(), F.lit(name)) for name in FEATURE_COLUMNS))
    return (
        normalized.withColumn("missingFields", F.filter(missing, lambda name: name.isNotNull()))
        .withColumn("featureVersion", F.lit(FEATURE_VERSION))
        .withColumn("validUntil", F.col("observedAt") + F.expr(f"INTERVAL {FRESHNESS_SECONDS} SECONDS"))
    )


def _silver_rows(normalized: DataFrame) -> DataFrame:
    """HDFS Streaming sink가 저장할 Silver 컬럼과 내용 해시를 만든다."""

    content = F.struct(*(F.col(name) for name in FEATURE_COLUMNS))
    return normalized.withColumn("weatherContentHash", F.sha2(F.to_json(content), 256)).select(
        "eventId",
        "source",
        "nx",
        "ny",
        "observedAt",
        "collectedAt",
        *FEATURE_COLUMNS,
        "missingFields",
        "weatherContentHash",
        F.lit(SILVER_VERSION).alias("silverVersion"),
        F.to_date("observedAt").alias("eventDate"),
        F.hour("observedAt").alias("eventHour"),
    )


def to_weather_silver_stream(normalized: DataFrame, watermark: str = SILVER_WATERMARK) -> DataFrame:
    """실시간 표준 행을 exact-content 기준으로 중복 제거해 HDFS Parquet sink에 전달한다.

    현재 시점보다 오래된 Kafka 재생은 watermark에서 제외될 수 있다. 과거 HDFS Archive의
    별도 backfill은 to_weather_silver_batch와 batch/weather_silver_backfill.py를 사용한다.
    """

    return (
        _silver_rows(normalized)
        .withWatermark("observedAt", watermark)
        .dropDuplicates(["source", "nx", "ny", "observedAt", "weatherContentHash"])
    )


def to_weather_silver_batch(normalized: DataFrame) -> DataFrame:
    """Archive backfill shares columns/content keys, without streaming watermark.

    Retain each correction; select earliest collection deterministically for repeats.
    Write to a separate versioned backfill root, never into a live file sink directory.
    """
    from pyspark.sql import Window

    keys = ["source", "nx", "ny", "observedAt", "weatherContentHash"]
    order = Window.partitionBy(*keys).orderBy("collectedAt", "eventId")
    return (
        _silver_rows(normalized)
        .withColumn("repeatIndex", F.row_number().over(order))
        .filter("repeatIndex = 1")
        .drop("repeatIndex")
    )
