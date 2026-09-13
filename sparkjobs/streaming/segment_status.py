"""실측의 직전 10분 중앙값과 고정 기준선을 비교하는 재현 가능한 구간 상태 연산이다.

평가시각과 정책을 호출자가 명시한다. 원천 위치/도착예정을 소요시간으로 추정하지 않는다.
정상/경미/지연 임계값은 운영 확정값으로 하드코딩하지 않고 인자로 받는다. 현재 검증된
운행중단 증거가 별도로 확인된 경우만 blocked이며 큰 지연으로 추정하지 않는다. 미관측 구간은 unknown이다.
결과 작성과 발행을 분리해 Redis/Kafka 일부 실패 후 같은 eventId로 재시도할 수 있게 한다.
"""

import argparse
import json
import logging
from datetime import datetime, timedelta
from pyspark.sql import SparkSession, functions as F, Window, types as T
from common.segment_facts import FACT_SCHEMA, REGISTRY_SCHEMA, validate

# 소유 계약: docs/contracts/redis-keys.yaml 및 topics.yaml. 만료는 300초 이하로 제한한다.
STATUS_REDIS_PREFIX = "segment:status:"
STATUS_TOPIC = "segment.status"
STATUS_TTL_SECONDS = 300

BASELINE_SCHEMA = T.StructType(
    [
        T.StructField(n, T.StringType(), True)
        for n in ("segmentId", "masterVersion", "weatherBucket", "dataVersion")
    ]
    + [
        T.StructField("dow", T.IntegerType(), True),
        T.StructField("hourSlot", T.IntegerType(), True),
        T.StructField("p50", T.DoubleType(), True),
        T.StructField("sampleCount", T.LongType(), True),
        T.StructField("eligible", T.BooleanType(), True),
    ]
)
BLOCKAGE_SCHEMA = T.StructType(
    [
        T.StructField(n, T.StringType(), True)
        for n in (
            "segmentId",
            "masterVersion",
            "eventId",
            "occurredAt",
            "validUntil",
            "source",
        )
    ]
    + [T.StructField("confirmed", T.BooleanType(), True)]
)
STATUS_FIELDS = [
    "eventId",
    "segmentId",
    "masterVersion",
    "status",
    "delaySeconds",
    "confidence",
    "observedSeconds",
    "observedAt",
    "observationCount",
    "baselineSeconds",
    "baselineVersion",
    "previousStatus",
    "qualityReason",
    "causes",
    "policyVersion",
    "computedAt",
    "validUntil",
    "source",
]


def evaluate(
    facts,
    registry,
    baseline,
    as_of,
    slow_seconds,
    delayed_seconds,
    min_samples,
    max_age_seconds,
    policy_version,
    blockages=None,
):
    """KST 평가시각 직전 600초의 실측과 같은 관측 시간대·날씨의 P50을 비교한다.

    입력 facts는 FACT_SCHEMA, registry는 Backend 정적 ID/마스터 버전 목록이며 baseline은
    batch.segment_baseline의 eligible 표시를 포함한 고정 버전이다. 초 단위 중앙값 차이를
    정책 인자의 경계와 비교한다. 최소 표본/관측 나이 게이트를 통과하지 못하면 unknown이다.
    출력은 구간별 상태 DataFrame과 격리 DataFrame이다. 관측/기준선 진단 컬럼은 감사용으로
    남기며, 외부 발행은 STATUS_FIELDS만 선택한다. I/O와 previous 상태의 소유권은 호출자에 있다.
    """
    now = datetime.fromisoformat(as_of)
    if (
        now.utcoffset() != timedelta(hours=9)
        or now.second
        or now.microsecond
        or not 0 < slow_seconds < delayed_seconds
        or min_samples < 1
        or not 0 < max_age_seconds <= 600
        or not policy_version
    ):
        raise ValueError("explicit KST time and validated status policy required")
    as_of = now.isoformat(timespec="seconds")
    # 1. ID·중복·event time을 검증한 뒤 최근 관측을 동일한 요일/시간대/날씨로 묶는다.
    valid, rejected = validate(facts, registry, as_of)
    window = valid.filter(
        (
            F.col("occurredAt")
            > F.lit((now - timedelta(seconds=600)).isoformat()).cast("timestamp")
        )
        & (F.col("occurredAt") <= F.lit(as_of).cast("timestamp"))
    )
    # 정각 직전 관측을 다음 시간대의 기준선과 비교하지 않는다. 관측 날짜/시각 그룹도 함께 고정한다.
    window = window.withColumn("dow", F.dayofweek("occurredAt")).withColumn(
        "hourSlot", F.hour("occurredAt")
    )
    groups = window.groupBy(
        "segmentId", "masterVersion", "weatherBucket", "dow", "hourSlot"
    ).agg(
        F.expr("percentile_approx(durationSeconds, 0.5, 10000)").alias(
            "observedSeconds"
        ),
        F.count("eventId").alias("observationCount"),
        F.max("occurredAt").alias("observedAt"),
    )
    # 날씨가 바뀐 경우 가장 최근 날씨 그룹만 사용한다. 같은 최신시각의 충돌은 임의 선택하지 않는다.
    order = Window.partitionBy("segmentId", "masterVersion").orderBy(
        F.col("observedAt").desc()
    )
    latest = (
        groups.withColumn("rank", F.dense_rank().over(order))
        .filter("rank=1")
        .drop("rank")
    )
    ambiguity = (
        latest.groupBy("segmentId", "masterVersion")
        .count()
        .withColumnRenamed("count", "weatherCandidates")
    )
    unique = (
        latest.join(ambiguity, ["segmentId", "masterVersion"])
        .filter("weatherCandidates=1")
        .drop("weatherCandidates")
    )
    # 2. 하나의 기준선 버전과 유일한 키만 허용하고 검수된 운행 중단 근거를 결합한다.
    selected = baseline
    if selected.select("dataVersion").distinct().limit(2).count() > 1:
        raise ValueError("baseline dataVersion is not unique")
    if (
        selected.groupBy(
            "segmentId", "masterVersion", "weatherBucket", "dow", "hourSlot"
        )
        .count()
        .filter("count>1")
        .limit(1)
        .count()
    ):
        raise ValueError("baseline version/key not unique")
    selected = selected.select(
        "segmentId",
        "masterVersion",
        "weatherBucket",
        "dow",
        "hourSlot",
        F.col("p50").alias("baselineSeconds"),
        F.col("sampleCount").alias("baselineSampleCount"),
        F.col("dataVersion").alias("baselineVersion"),
        F.col("eligible").alias("baselineEligible"),
    )
    # 외부 기준선 파일이 손상되어도 NaN/음수/빈 버전을 유효한 JSON 계약 값으로 발행하지 않는다.
    selected = selected.withColumn(
        "baselineSeconds",
        F.when(
            (F.col("baselineSeconds") > 0)
            & (F.col("baselineSeconds") < float("inf"))
            & ~F.isnan("baselineSeconds"),
            F.col("baselineSeconds"),
        ),
    ).withColumn(
        "baselineVersion",
        F.when(F.length("baselineVersion") > 0, F.col("baselineVersion")),
    )
    joined = (
        registry.distinct()
        .join(unique, ["segmentId", "masterVersion"], "left")
        .join(
            selected,
            ["segmentId", "masterVersion", "weatherBucket", "dow", "hourSlot"],
            "left",
        )
    )
    if blockages is not None:
        # 공지 제목/지연 크기로 통행불가를 추정하지 않는다. 별도 검수한 구간·시각·근거만 허용한다.
        verified = blockages.join(
            registry.distinct(), ["segmentId", "masterVersion"], "inner"
        ).filter(
            (F.col("confirmed") == True)
            & F.col("source").isNotNull()
            & (F.length("source") > 0)
            & F.col("eventId").rlike(
                r"^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$"
            )
            & (F.col("occurredAt").cast("timestamp") <= F.lit(as_of).cast("timestamp"))
            & (F.col("validUntil").cast("timestamp") > F.lit(as_of).cast("timestamp"))
        )
        total_blocks, active_blocks = blockages.count(), verified.count()
        logging.getLogger(__name__).info(
            "blockage input=%d activeVerified=%d excluded=%d; original retained in input manifest",
            total_blocks,
            active_blocks,
            total_blocks - active_blocks,
        )
        blocks = verified.groupBy("segmentId", "masterVersion").agg(
            F.max(F.col("validUntil").cast("timestamp")).alias("blockedUntil"),
            F.sort_array(
                F.collect_set(
                    F.struct(
                        "eventId",
                        F.lit("confirmed.blockage").alias("eventType"),
                        "source",
                    )
                )
            ).alias("blockCauses"),
        )
        joined = joined.join(blocks, ["segmentId", "masterVersion"], "left")
    else:
        joined = joined.withColumn(
            "blockedUntil", F.lit(None).cast("timestamp")
        ).withColumn(
            "blockCauses",
            F.array().cast(
                "array<struct<eventId:string,eventType:string,source:string>>"
            ),
        )
    # 3. 최신성·표본 게이트 이후 지연을 계산한다. 증거 부족을 정상 상태로 바꾸지 않는다.
    blocked = F.col("blockedUntil").isNotNull()
    age = F.lit(now.timestamp()) - F.col("observedAt").cast("long")
    available = (
        F.col("observedSeconds").isNotNull()
        & (age < max_age_seconds)
        & (F.col("observationCount") >= min_samples)
        & F.col("baselineSeconds").isNotNull()
        & ~F.isnan("baselineSeconds")
        & (F.col("baselineSeconds") > 0)
        & (F.col("baselineSeconds") < float("inf"))
        & (F.col("baselineSampleCount") >= min_samples)
        & F.col("baselineVersion").isNotNull()
        & (F.col("baselineEligible") == True)
    )
    # 정수 변환 전에 범위를 확인한다. 극단적인 입력이 overflow되어 정상/음수 지연으로 바뀌면 안 된다.
    raw_delay = F.greatest(
        F.lit(0.0), F.col("observedSeconds") - F.col("baselineSeconds")
    )
    available = available & (raw_delay <= 2147483647)
    delay = F.ceil(F.when(raw_delay <= 2147483647, raw_delay)).cast("int")
    result = (
        joined.withColumn(
            "status",
            F.when(blocked, "blocked")
            .when(~F.coalesce(available, F.lit(False)), "unknown")
            .when(delay >= delayed_seconds, "delayed")
            .when(delay >= slow_seconds, "slow")
            .otherwise("normal"),
        )
        .withColumn("delaySeconds", F.when(available & ~blocked, delay))
        .withColumn(
            "confidence",
            F.when(blocked, 1.0)
            .when(available, F.greatest(F.lit(0.0), 1.0 - age / max_age_seconds))
            .otherwise(F.lit(0.0)),
        )
        .withColumn(
            "causes",
            F.coalesce(
                F.col("blockCauses"),
                F.array().cast(
                    "array<struct<eventId:string,eventType:string,source:string>>"
                ),
            ),
        )
        .withColumn("computedAt", F.lit(as_of))
        .withColumn(
            "validUntil",
            F.date_format(
                F.when(
                    blocked,
                    F.least(
                        F.col("blockedUntil"),
                        F.lit(
                            (now + timedelta(seconds=STATUS_TTL_SECONDS)).isoformat()
                        ).cast("timestamp"),
                    ),
                )
                .when(
                    available,
                    F.least(
                        F.lit(
                            (now + timedelta(seconds=STATUS_TTL_SECONDS)).isoformat()
                        ).cast("timestamp"),
                        (F.col("observedAt").cast("long") + max_age_seconds).cast(
                            "timestamp"
                        ),
                    ),
                )
                .otherwise(
                    F.lit(
                        (now + timedelta(seconds=STATUS_TTL_SECONDS)).isoformat()
                    ).cast("timestamp")
                ),
                "yyyy-MM-dd'T'HH:mm:ssXXX",
            ),
        )
        .withColumn("source", F.lit("spark.segment-status"))
        .withColumn("policyVersion", F.lit(policy_version))
        .withColumn(
            "qualityReason",
            F.when(blocked, "CONFIRMED_BLOCKAGE")
            .when(F.col("observedAt").isNull(), "NO_UNIQUE_RECENT_OBSERVATION")
            .when(age >= max_age_seconds, "STALE_OBSERVATION")
            .when(raw_delay > 2147483647, "DELAY_OUT_OF_RANGE")
            .when(
                ~F.coalesce(available, F.lit(False)), "BASELINE_OR_SAMPLE_INSUFFICIENT"
            )
            .otherwise("NONE"),
        )
    )
    # 동일 평가시각·정책·마스터·결과는 같은 ID가 된다. 원본 사실은 입력 Parquet/eventId로 추적한다.
    result = result.withColumn(
        "eventId",
        F.sha2(F.to_json(F.struct(*[F.col(c) for c in sorted(result.columns)])), 256),
    )
    return result, rejected


def select_history(current, previous):
    """상태 변경은 즉시, 모든 구간은 10분 경계에서 기록한다. 이전값은 직전 성공 발행 결과만 전달한다."""
    before = previous.select(
        "segmentId",
        F.col("status").alias("previousStatus"),
        F.col("computedAt").alias("previousComputedAt"),
    )
    if before.groupBy("segmentId").count().filter("count>1").limit(1).count():
        raise ValueError("previous state is not unique per segment")
    joined = current.join(before, "segmentId", "left")
    if (
        joined.filter(
            F.col("previousStatus").isNotNull()
            & (
                F.col("previousComputedAt").cast("timestamp").isNull()
                | (
                    F.col("previousComputedAt").cast("timestamp")
                    > F.col("computedAt").cast("timestamp")
                )
            )
        )
        .limit(1)
        .count()
    ):
        raise ValueError(
            "previous state must have a valid timestamp no later than current"
        )
    joined = joined.withColumn(
        "eventId",
        F.sha2(
            F.to_json(
                F.struct(*[F.col(c) for c in sorted(joined.columns) if c != "eventId"])
            ),
            256,
        ),
    )
    # ISO 문자열을 기본 unix_timestamp 형식으로 읽으면 null이 되므로 먼저 timestamp로 변환한다.
    # 경계분을 놓쳤더라도 직전 성공 결과와 10분 구획이 달라지면 전체를 한 번 보낸다.
    current_bucket = F.floor(F.col("computedAt").cast("timestamp").cast("long") / 600)
    previous_bucket = F.floor(
        F.col("previousComputedAt").cast("timestamp").cast("long") / 600
    )
    boundary = (F.col("computedAt").cast("timestamp").cast("long") % 600 == 0) | (
        current_bucket != previous_bucket
    )
    return joined, joined.filter(
        F.col("previousStatus").isNull()
        | (F.col("status") != F.col("previousStatus"))
        | boundary
    )


def publish_prepared(spark, output, settings):
    """검토 완료 결과만 Redis 전체 최신값과 Kafka 변경/10분 스냅샷으로 발행한다.

    두 저장소는 분산 트랜잭션이 아니다. 둘 다 성공한 뒤에만 호출자가 previous를 전진시킨다.
    실패 후 같은 출력 경로를 재발행하면 중복 Kafka eventId는 같으며 오래된 Redis는 거절한다.
    """
    from common.redis_sink import write_latest_with_row_ttl

    # manifest는 모든 출력 저장 후 마지막으로 작성된다. 중간 실패 디렉터리는 발행 대상이 아니다.
    # 작은 실행 메타데이터 한 건만 driver에서 읽으며 관측/구간 전체를 collect하지 않는다.
    for name in ("latest", "history", "rejected", "manifest"):
        marker = spark._jvm.org.apache.hadoop.fs.Path(output + "/" + name + "/_SUCCESS")
        if not marker.getFileSystem(spark._jsc.hadoopConfiguration()).exists(marker):
            raise ValueError("preparation not complete: " + name)
    manifests = spark.read.text(output + "/manifest").limit(2).collect()
    if len(manifests) != 1:
        raise ValueError("exactly one completed preparation manifest required")
    manifest = json.loads(manifests[0].value)
    if (
        manifest.get("output") != output
        or manifest.get("publish_output")
        or not manifest.get("as_of")
    ):
        raise ValueError("preparation manifest does not match output")
    full = spark.read.parquet(output + "/latest")
    history = spark.read.parquet(output + "/history")
    # 전체 관측 감사 컬럼을 사용자 계약에 노출하지 않는다. 선택한 필드만 JSON에 담는다.
    fields = STATUS_FIELDS
    # Backend는 Kafka 알림 직후 Redis를 읽으므로 최신값 ACK를 먼저 보장한다.
    # 유효기간이 지난 검토 결과를 재발행해도 Redis 수명을 새로 연장하지 않는다.
    rows = redis_rows(full)
    write_latest_with_row_ttl(
        rows, settings.redis_host, settings.redis_port, settings.redis_password
    )
    history.select(
        F.col("segmentId").alias("key"), F.to_json(F.struct(*fields)).alias("value")
    ).write.format("kafka").option(
        "kafka.bootstrap.servers", settings.kafka_bootstrap_servers
    ).option(
        "topic", STATUS_TOPIC
    ).option(
        "kafka.acks", "all"
    ).save()


def redis_rows(full, now=None):
    """완성된 상태를 TTL 행으로 바꾼다. 테스트/Replay 시각을 주입하며 만료 결과는 다시 살리지 않는다."""
    from common.redis_sink import (
        KEY_COL,
        VALUE_COL,
        TTL_COL,
        EVENT_TIME_COL,
        COLLECTED_TIME_COL,
    )

    clock = F.lit(now).cast("timestamp") if now is not None else F.current_timestamp()
    remaining = F.col("validUntil").cast("timestamp").cast("long") - clock.cast("long")
    return full.filter(remaining > 0).select(
        F.concat(F.lit(STATUS_REDIS_PREFIX), F.col("segmentId")).alias(KEY_COL),
        F.to_json(F.struct(*STATUS_FIELDS)).alias(VALUE_COL),
        F.least(F.lit(STATUS_TTL_SECONDS), remaining).alias(TTL_COL),
        F.col("computedAt").alias(EVENT_TIME_COL),
        F.col("computedAt").alias(COLLECTED_TIME_COL),
    )


def main(argv=None):
    """고정시각 Replay로 결과 파일을 만든다. --publish-output은 준비된 동일 결과만 별도 발행한다."""
    p = argparse.ArgumentParser()
    p.add_argument("--publish-output")
    p.add_argument("--blockages")
    for name in (
        "input",
        "registry",
        "baseline",
        "previous",
        "output",
        "as-of",
        "policy-version",
    ):
        p.add_argument("--" + name)
    for name in ("slow-seconds", "delayed-seconds", "min-samples", "max-age-seconds"):
        p.add_argument("--" + name, type=int)
    args = p.parse_args(argv)
    spark = (
        SparkSession.builder.appName("ddubi-segment-status")
        .config("spark.sql.session.timeZone", "Asia/Seoul")
        .getOrCreate()
    )
    try:
        if args.publish_output:
            from common.settings import Settings

            publish_prepared(spark, args.publish_output, Settings.from_env())
            return 0
        if any(
            getattr(args, n) is None
            for n in (
                "input",
                "registry",
                "baseline",
                "output",
                "as_of",
                "policy_version",
                "slow_seconds",
                "delayed_seconds",
                "min_samples",
                "max_age_seconds",
            )
        ):
            raise ValueError("all evaluation inputs and policy parameters required")
        facts = spark.read.schema(FACT_SCHEMA).parquet(args.input)
        registry = spark.read.schema(REGISTRY_SCHEMA).json(args.registry)
        baseline = spark.read.schema(BASELINE_SCHEMA).parquet(args.baseline)
        blockages = (
            spark.read.schema(BLOCKAGE_SCHEMA).json(args.blockages)
            if args.blockages
            else None
        )
        current, rejected = evaluate(
            facts,
            registry,
            baseline,
            args.as_of,
            args.slow_seconds,
            args.delayed_seconds,
            args.min_samples,
            args.max_age_seconds,
            args.policy_version,
            blockages,
        )
        previous = (
            spark.read.parquet(args.previous)
            if args.previous
            else current.select("segmentId", "status", "computedAt").limit(0)
        )
        latest, history = select_history(current, previous)
        rejected.write.mode("errorifexists").parquet(args.output + "/rejected")
        latest.write.mode("errorifexists").parquet(args.output + "/latest")
        history.write.mode("errorifexists").parquet(args.output + "/history")
        # 실행 인자에는 인증키가 없으며 입력 경로/정책/평가시각을 남겨 원본 사실을 다시 선택할 수 있다.
        spark.range(1).select(
            F.lit(json.dumps(vars(args), sort_keys=True)).alias("value")
        ).write.mode("errorifexists").text(args.output + "/manifest")
    finally:
        spark.stop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
