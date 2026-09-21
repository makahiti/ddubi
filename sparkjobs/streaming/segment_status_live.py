"""고정 레지스트리·기준선으로 실측 상태를 매분 재평가한다. 입력이 없어도 stale을 처리한다.

rate 스트림은 수집 주기가 아니라 계산 clock이다. checkpoint의 batch timestamp가 재실행
평가시각을 고정한다. 각 batch 준비 파일을 먼저 완성하고 Redis ACK→Kafka ACK 후 반환한다.
재시도 때 완성 manifest가 있으면 같은 결과를 발행한다. 불완전 출력만 해당 batch 경로에
다시 저장한다. 이전 결과는 직전 성공 batch만 읽으며, 서비스 DB에 직접 쓰지 않는다.
명시한 --publish가 없으면 준비 파일만 만들고 운영 저장소에는 쓰지 않는다.
"""

import argparse
import json
from datetime import datetime, timedelta, timezone

from common.segment_facts import FACT_SCHEMA, REGISTRY_SCHEMA
from common.settings import Settings
from pyspark.sql import SparkSession
from pyspark.sql import functions as F
from streaming.segment_status import (
    BASELINE_SCHEMA,
    BLOCKAGE_SCHEMA,
    evaluate,
    publish_prepared,
    select_history,
)


def minute(value):
    """Spark timestamp의 UNIX 초를 KST 분 경계로 고정한다. 서버 로컬 시간대에 의존하지 않는다."""
    return datetime.fromtimestamp(value, tz=timezone(timedelta(hours=9))).replace(second=0, microsecond=0).isoformat()


def exists(spark, path):
    target = spark._jvm.org.apache.hadoop.fs.Path(path)
    return target.getFileSystem(spark._jsc.hadoopConfiguration()).exists(target)


def tick(spark, batch, batch_id, args, settings, registry, baseline):
    """clock 한 행만 driver로 읽는다. 전체 관측/마스터 collect는 하지 않고 분산 계산한다."""
    clock = batch.select(F.max(F.col("timestamp").cast("long")).alias("epoch")).first()
    if clock.epoch is None:
        return
    output = args.output.rstrip("/") + "/" + str(batch_id)
    if not exists(spark, output + "/manifest/_SUCCESS"):
        as_of = minute(clock.epoch)
        facts = spark.read.schema(FACT_SCHEMA).parquet(args.input)
        # event time filter는 Parquet pushdown이 가능한 원본 KST 문자열에 적용한다.
        # 현재 10분 창과 늦은/잘못된 시각 자료를 평가한다. 전체 원문은 ingest 출력에 남아 있다.
        lower = (datetime.fromisoformat(as_of) - timedelta(seconds=600)).isoformat()
        facts = facts.filter((F.col("occurredAt") > lower) | F.col("occurredAt").isNull())
        # 검증된 canonical 구간 통제 입력만 받는다. 매 tick 새 snapshot을 읽어 종료/만료를
        # 반영하고 evaluate가 masterVersion·confirmed·유효시간을 다시 검사한다.
        # 설정한 파일의 I/O 실패는 batch를 실패시켜 통제를 없는 것으로 덮어쓰지 않는다.
        blockages = read_blockages(spark, getattr(args, "blockages", None))
        current, rejected = evaluate(
            facts,
            registry,
            baseline,
            as_of,
            args.slow_seconds,
            args.delayed_seconds,
            args.min_samples,
            args.max_age_seconds,
            args.policy_version,
            blockages,
        )
        previous_path = args.output.rstrip("/") + "/" + str(batch_id - 1) + "/latest"
        previous = (
            spark.read.parquet(previous_path)
            if exists(spark, previous_path + "/_SUCCESS")
            else current.select("segmentId", "status", "computedAt").limit(0)
        )
        latest, history = select_history(current, previous)
        for name, data in (
            ("latest", latest),
            ("history", history),
            ("rejected", rejected),
        ):
            data.write.mode("overwrite").parquet(output + "/" + name)
        metadata = dict(vars(args), output=output, as_of=as_of, batchId=batch_id)
        spark.range(1).select(F.lit(json.dumps(metadata, sort_keys=True)).alias("value")).write.mode("overwrite").text(
            output + "/manifest"
        )
    if args.publish:
        publish_prepared(spark, output, settings)


def read_blockages(spark, path):
    """batch 모드와 같은 명시적 스키마의 JSON snapshot을 읽는다. 미설정만 None이다.

    입력 작성자는 검증된 도로/운행중단 대응을 소유한다. 원시 사고를 confirmed로
    승격하지 않으며, 파일이 없거나 깨졌으면 기존 성공 batch를 유지한 채 재시도한다.
    """
    if not path:
        return None
    return spark.read.schema(BLOCKAGE_SCHEMA).option("mode", "FAILFAST").json(path)


def main():
    parser = argparse.ArgumentParser()
    for name in ("input", "registry", "baseline", "output", "policy-version"):
        parser.add_argument("--" + name, required=True)
    for name in ("slow-seconds", "delayed-seconds", "min-samples", "max-age-seconds"):
        parser.add_argument("--" + name, type=int, required=True)
    parser.add_argument("--publish", action="store_true")
    parser.add_argument("--blockages", help="검증된 canonical 통제 JSON snapshot; 원시 사건 입력 금지")
    args = parser.parse_args()
    if not 0 < args.slow_seconds < args.delayed_seconds or args.min_samples < 1 or not 0 < args.max_age_seconds <= 600:
        raise ValueError("invalid status policy")
    settings = Settings.from_env()
    spark = (
        SparkSession.builder.appName("ddubi-segment-status-live")
        .config("spark.sql.session.timeZone", "Asia/Seoul")
        .getOrCreate()
    )
    registry = spark.read.schema(REGISTRY_SCHEMA).json(args.registry).cache()
    baseline = spark.read.schema(BASELINE_SCHEMA).parquet(args.baseline).cache()
    try:
        registry.count()
        baseline.count()
        query = (
            spark.readStream.format("rate")
            .option("rowsPerSecond", 1)
            .option("numPartitions", 1)
            .load()
            .writeStream.foreachBatch(
                lambda batch, batch_id: tick(spark, batch, batch_id, args, settings, registry, baseline)
            )
            .option("checkpointLocation", settings.checkpoint_for("segment-status-live"))
            .trigger(processingTime="60 seconds")
            .start()
        )
        query.awaitTermination()
    finally:
        registry.unpersist()
        baseline.unpersist()
        spark.stop()


if __name__ == "__main__":
    main()
