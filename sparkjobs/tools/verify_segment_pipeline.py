"""명시적 fixture로 Kafka 실측 계약→Parquet→기준선/상태→Redis/Kafka 경계를 검증한다.

운영 실측의 정확도 시험이 아니다. 실제 관측인 것처럼 원천을 조작하지 않고 source를 fixture로
표시하며, 이 도구에서만 작은 표본 정책을 주입한다. 출력 JSON은 Backend 경계 검증에 사용한다.
"""

import json
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

# 실행 파일 위치와 무관하게 두 프로젝트 모듈을 먼저 찾는다. 기존 검색 순서를 유지한다.
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "collector/src"))
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "sparkjobs"))
import redis
from batch.segment_baseline import aggregate
from common.redis_sink import write_latest_with_row_ttl
from common.segment_facts import REGISTRY_SCHEMA, validate
from publisher import Publisher
from pyspark.sql import SparkSession
from pyspark.sql import functions as F
from streaming.segment_observations import parse
from streaming.segment_status import STATUS_FIELDS, evaluate, redis_rows, select_history

ROOT = Path(__file__).resolve().parents[2]


def main():
    """고정 검증 endpoint와 새 출력 디렉터리만 사용하며 각 저장 경계의 실제 ACK를 확인한다."""
    import argparse

    p = argparse.ArgumentParser()
    p.add_argument("--output", required=True)
    a = p.parse_args()
    out = Path(a.output)
    out.mkdir(parents=True, exist_ok=False)
    broker = "ddubi-hardening-kafka:9092"
    producer = Publisher(broker, "segment-fixture")
    if not producer.wait_ready(30):
        raise RuntimeError("broker unavailable")
    now = datetime.now(timezone(timedelta(hours=9))).replace(second=0, microsecond=0)
    # 시 정각에도 현재값과 기준선 그룹이 일치하도록 관측을 평가시각에 놓는다.
    segment = "BUS:100100001:100000004:101000001"
    version = "fixture-registry-v1"
    rows = []
    for day, durations in [(7, [40, 50, 60]), (0, [90, 100, 110])]:
        at = (now - timedelta(days=day, seconds=2)).isoformat()
        for i, duration in enumerate(durations):
            exited = datetime.fromisoformat(at)
            rows.append(
                dict(
                    eventId=out.name + f":segment-fixture-{day}-{i}",
                    segmentId=segment,
                    masterVersion=version,
                    mappingVersion="fixture-mapping-v1",
                    vehicleId=f"fixture-vehicle-{day}-{i}",
                    occurredAt=exited.isoformat(),
                    observedAt=(exited + timedelta(seconds=1)).isoformat(),
                    enteredAt=(exited - timedelta(seconds=duration)).isoformat(),
                    exitedAt=exited.isoformat(),
                    collectedAt=(exited + timedelta(seconds=2)).isoformat(),
                    validUntil=(exited + timedelta(minutes=5)).isoformat(),
                    weatherBucket="DRY",
                    sampleKind="ACTUAL_TRAVERSAL",
                    quality="VERIFIED",
                    source="actual.fixture",
                    durationSeconds=float(duration),
                )
            )
    for row in rows:
        producer.publish("segment.observation", row["eventId"], row)
    if producer.flush(30):
        raise RuntimeError("delivery incomplete")
    spark = (
        SparkSession.builder.appName("ddubi-segment-boundary-fixture")
        .config("spark.sql.session.timeZone", "Asia/Seoul")
        .config("spark.sql.shuffle.partitions", "2")
        .getOrCreate()
    )
    spark.sparkContext.setLogLevel("ERROR")
    try:
        raw = (
            spark.readStream.format("kafka")
            .option("kafka.bootstrap.servers", broker)
            .option("subscribe", "segment.observation")
            .option("startingOffsets", "earliest")
            .option("failOnDataLoss", "true")
            .load()
        )
        q = (
            parse(raw)
            .filter(F.col("eventId").startswith(out.name + ":"))
            .writeStream.format("parquet")
            .option("path", str(out / "facts"))
            .option("checkpointLocation", str(out / "checkpoint"))
            .trigger(availableNow=True)
            .start()
        )
        q.awaitTermination(120)
        if q.isActive:
            q.stop()
            raise RuntimeError("ingest timeout")
        facts = spark.read.parquet(str(out / "facts"))
        assert facts.count() == 6
        assert facts.filter(F.col("rawValue").isNull()).count() == 0
        registry = spark.createDataFrame([(segment, version)], REGISTRY_SCHEMA)
        valid, rejected = validate(facts, registry, now.isoformat())
        assert rejected.count() == 0
        history = valid.filter(F.col("occurredAt") < F.lit(now.date().isoformat()).cast("timestamp"))
        baseline = aggregate(history, "audit-20260913", 3, 1).cache()
        baseline_rows = [
            r.asDict()
            for r in baseline.select(
                "segmentId",
                "dow",
                "hourSlot",
                "weatherBucket",
                "p50",
                "p80",
                "p95",
                "sampleCount",
            ).collect()
        ]
        assert baseline_rows[0]["p50"] == 50
        current, rejected = evaluate(facts, registry, baseline, now.isoformat(), 30, 60, 2, 300, "fixture-policy")
        full, changes = select_history(current, current.limit(0))
        payloads = [
            json.loads(r.payload) for r in full.select(F.to_json(F.struct(*STATUS_FIELDS)).alias("payload")).collect()
        ]
        assert len(payloads) == 1 and payloads[0]["status"] == "slow" and payloads[0]["delaySeconds"] == 50
        write_latest_with_row_ttl(redis_rows(full), "ddubi-flow-redis", 6379, None)
        changes.select(
            F.col("segmentId").alias("key"),
            F.to_json(F.struct(*STATUS_FIELDS)).alias("value"),
        ).write.format("kafka").option("kafka.bootstrap.servers", broker).option("topic", "segment.status").save()
        stored = json.loads(
            redis.Redis(host="ddubi-flow-redis", decode_responses=True).get("segment:status:" + segment)
        )
        assert stored == payloads[0]
        report = dict(
            scope="fixture through actual Kafka Parquet Spark Redis",
            productionValidated=False,
            asOf=now.isoformat(),
            rawRows=facts.count(),
            baselineVersion="audit-20260913",
            baselineRows=baseline_rows,
            states=payloads,
        )
        (out / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        print(json.dumps(report), flush=True)
        baseline.unpersist()
    finally:
        spark.stop()


if __name__ == "__main__":
    main()
