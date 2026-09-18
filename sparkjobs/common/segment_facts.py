"""검증된 실제 구간 통과 기록을 배치와 Replay가 공유하는 초 단위 사실로 정리한다.

입력은 실측 인접 구간의 Parquet이며 도착예정·ML 예측·멀리 떨어진 두 위치를 받지 않는다.
eventId 중복은 한 번 계산하고 내용 충돌은 해당 ID 전체를 격리한다. 등록된 마스터 버전과
edge가 맞지 않는 행·시각 오류·날씨 결측을 reason으로 남긴다. 원본은 호출자가 별도 저장한다.
"""

from pyspark.sql import functions as F, types as T, Window

FACT_SCHEMA = T.StructType(
    [
        T.StructField(n, T.StringType(), True)
        for n in (
            "eventId",
            "segmentId",
            "masterVersion",
            "occurredAt",
            "collectedAt",
            "weatherBucket",
            "sampleKind",
            "source",
        )
    ]
    + [T.StructField("durationSeconds", T.DoubleType(), True)]
)
REGISTRY_SCHEMA = T.StructType(
    [
        T.StructField("segmentId", T.StringType(), False),
        T.StructField("masterVersion", T.StringType(), False),
    ]
)


def validate(facts, registry, as_of):
    """명시한 평가시각까지의 실측을 검사해 유효 행과 격리 행을 반환한다. 날씨를 DRY로 채우지 않는다."""
    # 레지스트리 자체가 잘못되면 잘못된 구간 ID의 unknown 상태도 발행하면 안 된다.
    canonical = r"^(BUS|SUB):[A-Za-z0-9._-]{1,50}(:[A-Za-z0-9._-]{1,50}){2}$"
    invalid_registry = registry.filter(
        F.col("segmentId").isNull()
        | ~F.col("segmentId").rlike(canonical)
        | F.col("masterVersion").isNull()
        | (F.length("masterVersion") == 0)
        | (F.split("segmentId", ":")[2] == F.split("segmentId", ":")[3])
    )
    if invalid_registry.limit(1).count():
        raise ValueError("registry contains invalid canonical edge or master version")
    if (
        registry.groupBy("segmentId")
        .agg(F.countDistinct("masterVersion").alias("n"))
        .filter("n>1")
        .limit(1)
        .count()
    ):
        raise ValueError("registry mixes master versions for one segment")
    keyed = (
        facts.withColumn("rawOccurredAt", F.col("occurredAt"))
        .withColumn("rawCollectedAt", F.col("collectedAt"))
        .withColumn(
            "kstInput",
            F.col("occurredAt").rlike(r"\+09:00$")
            & F.col("collectedAt").rlike(r"\+09:00$"),
        )
        .withColumn("occurredAt", F.to_timestamp("occurredAt"))
        .withColumn("collectedAt", F.to_timestamp("collectedAt"))
    )
    # 같은 원본을 다른 시각에 재수집한 것은 중복이지만 측정값/대상 변경은 충돌이다.
    fingerprint = F.sha2(
        F.to_json(
            F.struct(
                "segmentId",
                "masterVersion",
                "occurredAt",
                "weatherBucket",
                "sampleKind",
                "source",
                "durationSeconds",
            )
        ),
        256,
    )
    keyed = keyed.withColumn("fingerprint", fingerprint)
    counts = keyed.groupBy("eventId").agg(
        F.countDistinct("fingerprint").alias("versions")
    )
    known = (
        registry.select("segmentId", "masterVersion")
        .distinct()
        .withColumn("registered", F.lit(True))
    )
    joined = keyed.join(counts, "eventId", "left").join(
        known, ["segmentId", "masterVersion"], "left"
    )
    reason = (
        F.when(
            F.col("eventId").isNull() | (F.length("eventId") == 0), "MISSING_EVENT_ID"
        )
        .when(F.col("versions") > 1, "CONFLICTING_EVENT_ID")
        .when(F.col("registered").isNull(), "UNKNOWN_EDGE_OR_MASTER_VERSION")
        .when(
            ~F.col("segmentId").rlike(
                r"^(BUS|SUB):[A-Za-z0-9._-]{1,50}(:[A-Za-z0-9._-]{1,50}){2}$"
            )
            | (F.length("masterVersion") == 0),
            "INVALID_CANONICAL_ID",
        )
        .when(F.col("source").isNull() | (F.length("source") == 0), "MISSING_SOURCE")
        .when(
            ~F.col("sampleKind").eqNullSafe("ACTUAL_TRAVERSAL"), "NOT_ACTUAL_TRAVERSAL"
        )
        .when(
            ~F.coalesce(F.col("kstInput"), F.lit(False))
            | F.col("occurredAt").isNull()
            | F.col("collectedAt").isNull()
            | (F.col("occurredAt") > F.col("collectedAt"))
            | (F.col("collectedAt") > F.lit(as_of).cast("timestamp")),
            "INVALID_TIME",
        )
        .when(
            F.col("durationSeconds").isNull()
            | F.isnan("durationSeconds")
            | (F.col("durationSeconds") <= 0)
            | (F.col("durationSeconds") == float("inf")),
            "INVALID_DURATION",
        )
        .when(
            F.col("weatherBucket").isNull()
            | ~F.col("weatherBucket").isin("DRY", "RAIN", "SNOW"),
            "WEATHER_MISSING_OR_INVALID",
        )
    )
    checked = joined.withColumn("rejectionReason", reason)
    rejected = checked.filter(F.col("rejectionReason").isNotNull())
    # 순서가 뒤바뀐 재수집도 가장 이른 수집시각을 보존해 같은 입력의 재생을 고정한다.
    order = Window.partitionBy("eventId").orderBy("collectedAt", "fingerprint")
    valid = (
        checked.filter(F.col("rejectionReason").isNull())
        .withColumn("copy", F.row_number().over(order))
        .filter("copy=1")
        .drop(
            "copy",
            "versions",
            "registered",
            "fingerprint",
            "rejectionReason",
            "kstInput",
            "rawOccurredAt",
            "rawCollectedAt",
        )
    )
    return valid, rejected
