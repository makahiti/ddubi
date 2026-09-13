"""실측/예측 혼동, 중복과 충돌, 날씨 결측, stale, 버전 혼합과 손계산 분위수를 검증한다."""

import hashlib
import json
from pathlib import Path
import pytest
from pyspark.sql import SparkSession, functions as F, types as T
from common.segment_facts import FACT_SCHEMA, REGISTRY_SCHEMA, validate
from common.subway_crosswalk import SubwayCrosswalk
from batch.segment_baseline import aggregate
from streaming.segment_status import (
    BASELINE_SCHEMA,
    BLOCKAGE_SCHEMA,
    STATUS_FIELDS,
    evaluate,
    select_history,
    redis_rows,
    publish_prepared,
)


@pytest.fixture(scope="module")
def spark():
    session = (
        SparkSession.builder.master("local[1]")
        .appName("test-segment-pipeline")
        .config("spark.sql.session.timeZone", "Asia/Seoul")
        .config("spark.sql.shuffle.partitions", "1")
        .config("spark.ui.enabled", "false")
        .getOrCreate()
    )
    yield session
    session.stop()


def frame(spark, rows, schema):
    return (
        spark.range(1)
        .select(
            F.explode(F.from_json(F.lit(json.dumps(rows)), T.ArrayType(schema))).alias(
                "r"
            )
        )
        .select("r.*")
    )


def fact(i, duration, **extra):
    return dict(
        eventId=str(i),
        segmentId="SUB:2:A:B",
        masterVersion="master1",
        occurredAt="2026-09-10T10:09:00+09:00",
        collectedAt="2026-09-10T10:09:10+09:00",
        weatherBucket="DRY",
        sampleKind="ACTUAL_TRAVERSAL",
        source="actual.fixture",
        durationSeconds=float(duration),
        **extra,
    )


def registry(spark):
    return frame(
        spark,
        [
            dict(segmentId=s, masterVersion="master1")
            for s in ["SUB:2:A:B", "SUB:2:B:C"]
        ],
        REGISTRY_SCHEMA,
    )


def test_actual_only_dedup_conflicts_and_hand_calculated_percentiles(spark):
    rows = [fact(i, i * 10) for i in range(1, 11)]
    rows += [
        dict(rows[0]),
        dict(fact("conflict", 20)),
        dict(fact("conflict", 30)),
        dict(fact("eta", 20), sampleKind="ARRIVAL_ESTIMATE"),
        dict(fact("weather", 20), weatherBucket=None),
        dict(fact("version", 20), masterVersion="other"),
        dict(fact("future", 20), collectedAt="2026-09-11T00:00:00+09:00"),
    ]
    valid, rejected = validate(
        frame(spark, rows, FACT_SCHEMA), registry(spark), "2026-09-10T10:10:00+09:00"
    )
    assert valid.count() == 10
    assert rejected.count() == 6
    result = aggregate(valid, "baseline1", 5, 1).first()
    assert (
        result.p50,
        result.p80,
        result.p95,
        result.sampleCount,
        result.dow,
        result.hourSlot,
    ) == (50, 80, 100, 10, 5, 10)
    assert result.eligible
    assert not aggregate(valid, "baseline1", 11, 1).first().eligible


def baseline(spark):
    return frame(
        spark,
        [
            dict(
                segmentId="SUB:2:A:B",
                masterVersion="master1",
                weatherBucket="DRY",
                dataVersion="baseline1",
                dow=5,
                hourSlot=10,
                p50=50.0,
                sampleCount=5,
                eligible=True,
            )
        ],
        BASELINE_SCHEMA,
    )


def test_delay_unknown_stale_and_history_cadence(spark):
    facts = frame(
        spark, [fact(i, d) for i, d in enumerate([90, 100, 110])], FACT_SCHEMA
    )
    current, rejected = evaluate(
        facts,
        registry(spark),
        baseline(spark),
        "2026-09-10T10:10:00+09:00",
        30,
        60,
        2,
        300,
        "fixture-policy",
    )
    results = {r.segmentId: r for r in current.collect()}
    assert (
        results["SUB:2:A:B"].status == "slow"
        and results["SUB:2:A:B"].delaySeconds == 50
    )
    assert results["SUB:2:A:B"].confidence == pytest.approx(0.8)
    assert results["SUB:2:A:B"].validUntil == "2026-09-10T10:14:00+09:00"
    assert (
        results["SUB:2:B:C"].status == "unknown"
        and results["SUB:2:B:C"].delaySeconds is None
    )
    assert select_history(current, current)[1].count() == 2  # 10분 경계 전체 기록
    next_frame, _ = evaluate(
        facts,
        registry(spark),
        baseline(spark),
        "2026-09-10T10:11:00+09:00",
        30,
        60,
        2,
        300,
        "fixture-policy",
    )
    assert select_history(next_frame, current)[1].count() == 0
    stale, _ = evaluate(
        facts,
        registry(spark),
        baseline(spark),
        "2026-09-10T10:15:00+09:00",
        30,
        60,
        2,
        300,
        "fixture-policy",
    )
    assert {r.status for r in stale.collect()} == {"unknown"}
    assert select_history(stale, current)[1].count() == 1
    # 직렬화된 Kafka/Redis 값은 실제 JSON Schema와 일치해야 한다. null 진단은 생략한다.
    import jsonschema

    schema = json.loads(
        (
            Path(__file__).resolve().parents[2]
            / "docs/contracts/events/segment-status.schema.json"
        ).read_text(encoding="utf-8")
    )
    latest, _ = select_history(stale, current)
    for row in latest.select(
        F.to_json(F.struct(*STATUS_FIELDS)).alias("payload")
    ).collect():
        jsonschema.validate(
            json.loads(row.payload), schema, format_checker=jsonschema.FormatChecker()
        )


def test_large_delay_is_not_blocked_without_confirmed_live_evidence(spark):
    facts = frame(spark, [fact(i, 900) for i in range(3)], FACT_SCHEMA)
    block = dict(
        segmentId="SUB:2:B:C",
        masterVersion="master1",
        eventId="00000000-0000-4000-8000-000000000001",
        occurredAt="2026-09-10T10:09:00+09:00",
        validUntil="2026-09-10T10:11:00+09:00",
        source="reviewed.operator",
        confirmed=True,
    )
    current, _ = evaluate(
        facts,
        registry(spark),
        baseline(spark),
        "2026-09-10T10:10:00+09:00",
        30,
        60,
        2,
        300,
        "test",
        frame(spark, [block], BLOCKAGE_SCHEMA),
    )
    rows = {r.segmentId: r for r in current.collect()}
    assert rows["SUB:2:A:B"].status == "delayed"
    assert (
        rows["SUB:2:B:C"].status == "blocked" and rows["SUB:2:B:C"].delaySeconds is None
    )
    assert rows["SUB:2:B:C"].validUntil == block["validUntil"]
    assert rows["SUB:2:B:C"].causes[0].eventId == block["eventId"]
    unconfirmed = frame(spark, [dict(block, confirmed=False)], BLOCKAGE_SCHEMA)
    unknown, _ = evaluate(
        facts,
        registry(spark),
        baseline(spark),
        "2026-09-10T10:10:00+09:00",
        30,
        60,
        2,
        300,
        "test",
        unconfirmed,
    )
    assert {r.segmentId: r.status for r in unknown.collect()}["SUB:2:B:C"] == "unknown"


def test_policy_and_baseline_conflicts_fail_closed(spark):
    facts = frame(spark, [fact(1, 90)], FACT_SCHEMA)
    with pytest.raises(ValueError):
        evaluate(
            facts,
            registry(spark),
            baseline(spark),
            "2026-09-10T10:10:00+09:00",
            60,
            30,
            1,
            300,
            "p",
        )
    with pytest.raises(ValueError, match="not unique"):
        evaluate(
            facts,
            registry(spark),
            baseline(spark).unionByName(baseline(spark)),
            "2026-09-10T10:10:00+09:00",
            30,
            60,
            1,
            300,
            "p",
        )
    # 외부 ID/빈 버전/자기 자신을 향한 edge가 레지스트리에 들어오면 unknown 결과조차 만들지 않는다.
    for bad in (
        dict(segmentId="1002000222", masterVersion="master1"),
        dict(segmentId="SUB:2:A:B", masterVersion=""),
        dict(segmentId="SUB:2:A:A", masterVersion="master1"),
    ):
        with pytest.raises(ValueError, match="invalid canonical"):
            validate(
                facts, frame(spark, [bad], REGISTRY_SCHEMA), "2026-09-10T10:10:00+09:00"
            )


def test_backend_master_crosswalk_keeps_static_ids_and_rejects_wrong_sha():
    path = (
        Path(__file__).resolve().parents[2]
        / "backend/src/main/resources/transit/metropolitan-subway-master.json.gz"
    )
    sha = hashlib.sha256(path.read_bytes()).hexdigest()
    cw = SubwayCrosswalk(path, sha)
    assert "SUB:KRIC.S2802:KRIC.S2802.3209:KRIC.S2802.3210" in cw.edges
    assert cw.resolve("KRIC.S2802", "3209", "3210")["state"] == "UNRESOLVED"
    result = cw.resolve("1002", "1002000222", "1002000223")
    assert result["state"] == "CONFIRMED" and result["segmentIds"] == [
        "SUB:2:0222:0223"
    ]
    with pytest.raises(ValueError, match="SHA"):
        SubwayCrosswalk(path, "0" * 64)


def test_hour_boundary_uses_observation_hour_and_insufficient_baseline_is_unknown(
    spark,
):
    # 10:59의 실측은 11시가 되어도 10시 기준선 50초와 비교한다. 11시 기준선은 200초다.
    facts = frame(
        spark,
        [
            dict(
                fact(i, 100),
                occurredAt="2026-09-10T10:59:00+09:00",
                collectedAt="2026-09-10T10:59:10+09:00",
            )
            for i in range(3)
        ],
        FACT_SCHEMA,
    )
    base = baseline(spark).unionByName(
        baseline(spark)
        .withColumn("hourSlot", F.lit(11))
        .withColumn("p50", F.lit(200.0))
    )
    result, _ = evaluate(
        facts, registry(spark), base, "2026-09-10T11:00:00+09:00", 30, 60, 2, 300, "p"
    )
    assert result.filter("segmentId='SUB:2:A:B'").first().delaySeconds == 50
    insufficient, _ = evaluate(
        facts,
        registry(spark),
        base.withColumn("eligible", F.lit(False)),
        "2026-09-10T11:00:00+09:00",
        30,
        60,
        2,
        300,
        "p",
    )
    assert {r.status for r in insufficient.collect()} == {"unknown"}


def test_extreme_delay_and_negative_baseline_do_not_publish_invalid_numbers(spark):
    facts = frame(spark, [fact(i, 3e9) for i in range(3)], FACT_SCHEMA)
    current, _ = evaluate(
        facts,
        registry(spark),
        baseline(spark),
        "2026-09-10T10:10:00+09:00",
        30,
        60,
        2,
        300,
        "p",
    )
    row = current.filter("segmentId='SUB:2:A:B'").first()
    assert (
        row.status == "unknown"
        and row.delaySeconds is None
        and row.qualityReason == "DELAY_OUT_OF_RANGE"
    )
    damaged, _ = evaluate(
        facts,
        registry(spark),
        baseline(spark).withColumn("p50", F.lit(-1.0)),
        "2026-09-10T10:10:00+09:00",
        30,
        60,
        2,
        300,
        "p",
    )
    assert damaged.filter("segmentId='SUB:2:A:B'").first().baselineSeconds is None


def test_status_replay_preserves_event_id_and_expired_redis_rows_are_omitted(spark):
    from common.redis_sink import TTL_COL

    facts = frame(spark, [fact(i, 100) for i in range(3)], FACT_SCHEMA)
    current, _ = evaluate(
        facts,
        registry(spark),
        baseline(spark),
        "2026-09-10T10:10:00+09:00",
        30,
        60,
        2,
        300,
        "p",
    )
    previous = current.select("segmentId", "status", "computedAt").limit(0)
    a, _ = select_history(current, previous)
    b, _ = select_history(current, previous)
    assert sorted(r.eventId for r in a.collect()) == sorted(
        r.eventId for r in b.collect()
    )
    assert sorted(
        r[TTL_COL] for r in redis_rows(a, "2026-09-10T10:13:00+09:00").collect()
    ) == [
        60,
        120,
    ]
    assert redis_rows(a, "2026-09-10T10:16:00+09:00").count() == 0
    with pytest.raises(ValueError, match="no later"):
        select_history(
            current,
            current.withColumn("computedAt", F.lit("2026-09-10T10:11:00+09:00")),
        )


def test_incomplete_output_rejected_and_kafka_failure_follows_redis_ack(
    spark, monkeypatch
):
    # 불완전 준비는 무발행이다. Kafka ACK 실패 시 Redis는 이미 갱신되지만 previous는 전진하지 않는다.
    from types import SimpleNamespace
    from unittest.mock import MagicMock

    fake = MagicMock()
    marker = fake._jvm.org.apache.hadoop.fs.Path.return_value
    marker.getFileSystem.return_value.exists.return_value = False
    settings = SimpleNamespace(
        kafka_bootstrap_servers="unused",
        redis_host="unused",
        redis_port=6379,
        redis_password=None,
    )
    sink = MagicMock()
    monkeypatch.setattr("common.redis_sink.write_latest_with_row_ttl", sink)
    with pytest.raises(ValueError, match="not complete"):
        publish_prepared(fake, "fixture", settings)
    sink.assert_not_called()
    marker.getFileSystem.return_value.exists.return_value = True
    fake.read.text.return_value.limit.return_value.collect.return_value = [
        SimpleNamespace(
            value=json.dumps(
                {"output": "fixture", "as_of": "2026-09-10T10:10:00+09:00"}
            )
        )
    ]
    writer = fake.read.parquet.return_value.select.return_value.write
    writer.format.return_value = writer
    writer.option.return_value = writer
    writer.save.side_effect = RuntimeError("Kafka ACK failed")
    with pytest.raises(RuntimeError, match="Kafka ACK"):
        publish_prepared(fake, "fixture", settings)
    sink.assert_called_once()


def test_live_clock_replays_completed_batch_and_expires_without_new_observations(
    spark, tmp_path
):
    import os
    from types import SimpleNamespace
    from streaming.segment_status_live import tick

    if os.name == "nt" and not os.environ.get("HADOOP_HOME"):
        pytest.skip("local Windows Hadoop file sink unavailable; exercised on Linux CI")
    root = str(tmp_path / "status")
    source = str(tmp_path / "facts")
    frame(spark, [fact(i, 100) for i in range(3)], FACT_SCHEMA).write.parquet(source)
    args = SimpleNamespace(
        input=source,
        output=root,
        slow_seconds=30,
        delayed_seconds=60,
        min_samples=2,
        max_age_seconds=300,
        policy_version="fixture",
        publish=False,
    )

    def clock(value):
        return spark.range(1).select(F.lit(value).cast("timestamp").alias("timestamp"))

    tick(
        spark,
        clock("2026-09-10T10:10:00+09:00"),
        0,
        args,
        None,
        registry(spark),
        baseline(spark),
    )
    first = spark.read.parquet(root + "/0/latest")
    event_ids = sorted(r.eventId for r in first.collect())
    assert first.filter("segmentId='SUB:2:A:B'").first().status == "slow"
    # 완성 manifest가 있는 재시도는 clock이나 준비 입력으로 이미 확정한 결과를 다시 쓰지 않는다.
    tick(
        spark,
        clock("2026-09-10T10:11:00+09:00"),
        0,
        args,
        None,
        registry(spark),
        baseline(spark),
    )
    assert (
        sorted(r.eventId for r in spark.read.parquet(root + "/0/latest").collect())
        == event_ids
    )
    tick(
        spark,
        clock("2026-09-10T10:15:00+09:00"),
        1,
        args,
        None,
        registry(spark),
        baseline(spark),
    )
    assert {r.status for r in spark.read.parquet(root + "/1/latest").collect()} == {
        "unknown"
    }
