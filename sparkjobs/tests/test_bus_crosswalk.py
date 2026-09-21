"""Collector의 실제 봉투가 같은 버전 대응표를 거쳐 Redis 행까지 연결되는지 검사한다."""

import gzip
import hashlib
import json
import sys
from pathlib import Path

import pytest
from common.bus_crosswalk import load_crosswalk, load_crosswalk_edges
from common.bus_segment_mapping import (
    build_bus_segment_edges,
    build_bus_segment_mapping,
    build_bus_segment_registry,
)
from common.redis_sink import KEY_COL, VALUE_COL
from streaming.realtime_latest import latest_per_provider, to_redis_rows, transform
from test_realtime_latest import _event, _kafka_df
from test_realtime_latest import (
    spark as spark,  # pytest가 이름으로 찾는 공유 fixture를 명시적으로 재노출한다.
)


def fixture(tmp_path):
    rows = []
    for ns in ["SEOUL_BUS", "GBIS"]:
        prefix = "" if ns == "SEOUL_BUS" else ns + "."
        rows.append(
            dict(
                namespace=ns,
                routeId="1",
                fromStopId="10",
                toStopId="11",
                fromOrder=1,
                toOrder=2,
                direction="UP",
                segmentId="BUS:" + ":".join(prefix + v for v in ["1", "10", "11"]),
            )
        )
    data = dict(
        schemaVersion=1,
        idPolicy="canonical-adjacent-v2",
        masterVersion="TEST",
        masterSha256="a" * 64,
        edgeCount=2,
        edges=rows,
    )
    raw = gzip.compress(json.dumps(data).encode(), mtime=0)
    path = tmp_path / "cw.gz"
    path.write_bytes(raw)
    return path, hashlib.sha256(raw).hexdigest()


def test_actual_collector_envelope_matches_spark_and_preserves_arrival_semantics(spark, tmp_path):
    sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "collector/src"))
    from bus_crosswalk import BusCrosswalk
    from events import build_event
    from sources.base import now_kst
    from sources.kafka_adapter import make_source, to_record

    path, sha = fixture(tmp_path)
    cw = BusCrosswalk(path, sha)
    event = build_event(
        make_source("bus_arrival"),
        to_record("bus_arrival", {"busRouteId": "1", "stId": "11", "exps1": "120", "traTime1": "2"}, crosswalk=cw),
        now_kst(),
    )
    rows = to_redis_rows(
        latest_per_provider(transform(_kafka_df(spark, [event]), load_crosswalk(spark, path, sha)))
    ).collect()
    assert len(rows) == 1
    value = json.loads(rows[0][VALUE_COL])
    assert value["segmentIds"] == event["segmentIds"] == ["BUS:1:10:11"]
    assert value["canonicalMapping"]["scope"] == "ARRIVAL_TARGET_INCOMING_EDGE"
    assert value["observation"]["firstArrivalSeconds"] == 120
    assert value["observation"]["firstTravelTimeMinutes"] == 2
    assert rows[0][KEY_COL] == "transit:latest:bus-arrival:1:11"


def test_region_namespaces_do_not_overwrite_each_other_and_mismatch_drops_claim(spark, tmp_path):
    path, sha = fixture(tmp_path)
    base = dict(providerRouteId="1", providerStopId="11", raw={})
    events = [
        _event("s", "bus.arrival", "2026-09-10T00:00:00+09:00", base),
        _event("g", "bus.arrival", "2026-09-10T00:00:00+09:00", {**base, "providerNamespace": "GBIS"}),
        _event(
            "bad", "bus.arrival", "2026-09-10T00:00:01+09:00", {**base, "canonicalMapping": {"masterVersion": "OLD"}}
        ),
    ]
    transformed = transform(_kafka_df(spark, events), load_crosswalk(spark, path, sha))
    bad = transformed.filter("eventId='bad'").collect()[0]
    assert bad.segmentIds == [] and bad.canonicalMapping.reason == "CROSSWALK_VERSION_MISMATCH"
    rows = to_redis_rows(latest_per_provider(transformed.filter("eventId!='bad'"))).collect()
    assert {r[KEY_COL] for r in rows} == {"transit:latest:bus-arrival:1:11", "transit:latest:bus-arrival:GBIS:1:11"}


def test_seoul_position_pair_survives_spark_revalidation(spark, tmp_path):
    """Collector가 만든 실제 pair가 같은 SHA 대응표로 재검증되고 Redis에 유지된다."""
    sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "collector/src"))
    from bus_crosswalk import BusCrosswalk
    from events import build_event
    from sources.base import now_kst
    from sources.kafka_adapter import make_source, to_record

    path, sha = fixture(tmp_path)
    cw = BusCrosswalk(path, sha)
    raw = dict(routeId="1", vehId="V", sectOrd="1", lastStnId="10", stopFlag="0")
    event = build_event(make_source("bus_location"), to_record("bus_location", raw, "1", cw), now_kst())
    index = load_crosswalk(spark, path, sha)
    events = _kafka_df(spark, [event])
    result = to_redis_rows(latest_per_provider(transform(events, index))).collect()
    assert len(result) == 1
    value = json.loads(result[0][VALUE_COL])
    assert value["segmentIds"] == ["BUS:1:10:11"]
    assert value["canonicalMapping"]["scope"] == "EXPLICIT_ADJACENT_STOP_PAIR"
    assert result[0][KEY_COL] == "transit:latest:bus-position:1:V"

    from common.bus_stream_sink import prepare_live, segment_redis_rows

    segment = segment_redis_rows(prepare_live(events, transform(events, index))).collect()
    assert len(segment) == 1
    assert segment[0].redisKey == "ml:latest:bus-segment:BUS:1:10:11"
    segment_value = json.loads(segment[0].redisValue)
    assert segment_value["segmentId"] == "BUS:1:10:11"
    assert segment_value["timestampBasis"] == "received"
    assert segment_value["observedAt"] is None
    assert segment_value["occurredAt"] == segment_value["_latestOrderAt"]


def test_missing_crosswalk_or_unknown_namespace_never_accepts_claimed_bus_ids(spark):
    event = _event(
        "x",
        "bus.arrival",
        "2026-09-10T00:00:00+09:00",
        dict(providerRouteId="1", providerStopId="11"),
        segment_ids=["BUS:fake:A:B"],
    )
    row = transform(_kafka_df(spark, [event])).collect()[0]
    assert row.segmentIds == [] and row.canonicalMapping.reason == "CROSSWALK_NOT_CONFIGURED"
    event["payload"].pop("providerNamespace")
    assert transform(_kafka_df(spark, [event])).collect()[0].providerKey is None


def test_corrupt_crosswalk_fails_before_streaming(spark, tmp_path):
    path, sha = fixture(tmp_path)
    with pytest.raises(ValueError, match="SHA"):
        load_crosswalk(spark, path, "0" * 64)


@pytest.fixture(autouse=True)
def release_static_indexes(spark):
    # 테스트별 정적 캐시를 해제해 반복 실행 자체가 메모리 누적으로 이어지지 않게 한다.
    yield
    spark.catalog.clearCache()


def test_full_serving_artifact_reuses_materialized_index_without_json_literal(spark):
    # 실배포 105,070 edge → 210,140 조회 행으로 검증한다. 원본 ID 보존뿐 아니라
    # 반복 배치의 실행 계획이 JSON 리터럴을 재파싱하지 않고 캐시를 읽는지도 검사한다.
    path = Path(__file__).resolve().parents[2] / "backend/transit-master/bus/metropolitan-bus-v6.crosswalk.json.gz"
    raw = path.read_bytes()
    data = json.loads(gzip.decompress(raw))
    index, version, sha = load_crosswalk(spark, path, hashlib.sha256(raw).hexdigest())
    from pyspark.sql import functions as F

    assert index.is_cached and index.storageLevel.useMemory and index.storageLevel.useDisk
    cached = spark.sparkContext._jsc.sc().getRDDStorageInfo()
    assert any(rdd.numCachedPartitions() == rdd.numPartitions() and rdd.numPartitions() > 0 for rdd in cached)
    count = index.select(F.explode("canonicalCandidates").alias("segmentId")).distinct().count()
    assert count == len({e["segmentId"] for e in data["edges"]})
    verified_edges = load_crosswalk_edges(spark, path, sha)
    mapping = build_bus_segment_mapping(build_bus_segment_edges(verified_edges))
    assert mapping.filter("mappingStatus != 'RESOLVED' OR segmentId IS NULL").count() == 0
    assert mapping.select("segmentId").distinct().count() == count
    assert version == data["masterVersion"] and len(sha) == 64
    edge = data["edges"][0]
    condition = (
        (F.col("lookupType") == "bus.position")
        & (F.col("namespace") == edge["namespace"])
        & (F.col("routeId") == edge["routeId"])
        & (F.col("fromStopId") == edge["fromStopId"])
        & (F.col("toStopId") == edge["toStopId"])
    )
    for _ in range(3):
        batch = index.filter(condition)
        assert batch.collect()[0].canonicalCandidates == [edge["segmentId"]]
        plan = batch._jdf.queryExecution().executedPlan().toString()
        assert "InMemoryTableScan" in plan
        assert "from_json" not in plan.lower()


def test_verified_crosswalk_becomes_scalar_segment_mapping(spark, tmp_path):
    path, sha = fixture(tmp_path)
    verified_edges = load_crosswalk_edges(spark, path, sha)
    edges = build_bus_segment_edges(verified_edges)
    mapping = build_bus_segment_mapping(edges)
    edge_rows = edges.collect()
    assert len(edge_rows) == 2
    assert all(row.providerFromStopOrder == 1 for row in edge_rows)
    assert all(row.providerToStopOrder == 2 for row in edge_rows)
    assert all(row.direction == "UP" for row in edge_rows)
    rows = {row.providerNamespace: row.asDict(recursive=True) for row in mapping.collect()}

    assert set(rows) == {"SEOUL_BUS", "GBIS"}
    assert rows["SEOUL_BUS"]["segmentId"] == "BUS:1:10:11"
    assert rows["GBIS"]["segmentId"] == "BUS:GBIS.1:GBIS.10:GBIS.11"
    assert all(row["mappingStatus"] == "RESOLVED" for row in rows.values())
    assert all(row["masterVersion"] == "TEST" for row in rows.values())
    assert all(row["crosswalkSha256"] == sha for row in rows.values())

    registry = build_bus_segment_registry(mapping).collect()
    assert {row.segmentId for row in registry} == {
        "BUS:1:10:11",
        "BUS:GBIS.1:GBIS.10:GBIS.11",
    }


def test_segment_mapping_requires_verified_crosswalk():
    with pytest.raises(ValueError, match="verified bus crosswalk edges required"):
        build_bus_segment_edges(None)


def test_crosswalk_edge_loader_rejects_different_bus_master_sha(spark, tmp_path):
    path, sha = fixture(tmp_path)
    with pytest.raises(ValueError, match="bus master SHA mismatch"):
        load_crosswalk_edges(spark, path, sha, "b" * 64)
