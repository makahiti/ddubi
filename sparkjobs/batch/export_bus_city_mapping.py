"""버스 segmentId→서울 실시간 도시데이터 영역 대응표를 HDFS와 Redis에 배포한다."""

import argparse
import logging
import os

from common.bus_city_mapping import (
    MAPPING_VERSION,
    build_edge_city_mapping,
    build_segment_city_mapping,
    latest_city_road_points,
)
from common.bus_city_redis import DEFAULT_TTL_SECONDS, publish_segment_city_mapping
from common.bus_crosswalk import load_crosswalk_edges
from common.bus_segment_mapping import build_bus_segment_edges
from common.bus_weather_mapping import load_bus_stops
from pyspark.sql import SparkSession
from pyspark.sql import functions as F

LOG = logging.getLogger(__name__)


def reference_sha256(points):
    """선택된 최신 영역 도로 행 전체의 내용 지문을 Spark에서 결정적으로 계산한다."""
    digest = (
        points.select(
            F.sha2(
                F.concat_ws(
                    "|",
                    "providerAreaId",
                    F.coalesce("areaName", F.lit("")),
                    "linkNamespace",
                    "linkId",
                    F.format_string("%.8f", "areaRoadLongitude"),
                    F.format_string("%.8f", "areaRoadLatitude"),
                ),
                256,
            ).alias("rowDigest")
        )
        .agg(F.sha2(F.concat_ws("", F.sort_array(F.collect_list("rowDigest"))), 256).alias("sha"))
        .first()
        .sha
    )
    if not digest:
        raise ValueError("city road reference has no usable rows")
    return digest


def _write_hdfs(root, edge_mapping, segment_mapping, verified, bus_master_sha, area_sha):
    spark = edge_mapping.sparkSession
    spark.createDataFrame(
        [
            (
                MAPPING_VERSION,
                verified[1],
                verified[2],
                bus_master_sha,
                area_sha,
            )
        ],
        "mappingVersion string, masterVersion string, crosswalkSha256 string, "
        "busMasterSha256 string, areaReferenceSha256 string",
    ).write.mode("errorifexists").json(root + "/_run")
    edge_mapping.write.mode("errorifexists").partitionBy("providerNamespace").parquet(root + "/edges")
    segment_mapping.write.mode("errorifexists").parquet(root + "/mappings")
    edge_mapping.groupBy("mappingStatus", "mappingReason").count().write.mode("errorifexists").json(
        root + "/quality/edges"
    )
    segment_mapping.groupBy("mappingStatus", "mappingReason").count().write.mode("errorifexists").json(
        root + "/quality/segments"
    )
    spark.createDataFrame(
        [
            (
                edge_mapping.count(),
                segment_mapping.count(),
                segment_mapping.filter("mappingStatus = 'RESOLVED'").count(),
                segment_mapping.filter("mappingStatus != 'RESOLVED'").count(),
            )
        ],
        "edgeRows long, segmentRows long, resolvedRows long, nonResolvedRows long",
    ).write.mode("errorifexists").json(root + "/complete")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--crosswalk", required=True)
    parser.add_argument("--crosswalk-sha", required=True)
    parser.add_argument("--bus-master", required=True)
    parser.add_argument("--bus-master-sha", required=True)
    parser.add_argument(
        "--city-road-input", required=True, help="City Silver road-link-snapshot Parquet 경로 또는 glob"
    )
    parser.add_argument("--output", help="새 출력 디렉터리. 비우면 Redis만 배포")
    parser.add_argument("--skip-redis", action="store_true")
    parser.add_argument(
        "--max-distance-meters", type=float, default=float(os.environ.get("CITY_MAPPING_MAX_DISTANCE_METERS", "300"))
    )
    parser.add_argument(
        "--ambiguity-meters", type=float, default=float(os.environ.get("CITY_MAPPING_AMBIGUITY_METERS", "50"))
    )
    parser.add_argument(
        "--redis-ttl-seconds",
        type=int,
        default=int(os.environ.get("BUS_CITY_MAPPING_TTL_SECONDS", DEFAULT_TTL_SECONDS)),
    )
    args = parser.parse_args(argv)
    root = args.output.rstrip("/") if args.output else None
    if not root and args.skip_redis:
        parser.error("--output과 Redis 중 하나는 활성화해야 합니다")

    spark = SparkSession.builder.appName("ddubi-bus-city-area-mapping").getOrCreate()
    try:
        verified = load_crosswalk_edges(spark, args.crosswalk, args.crosswalk_sha, args.bus_master_sha)
        edges = build_bus_segment_edges(verified)
        stops = load_bus_stops(spark, args.bus_master, args.bus_master_sha, verified[1])
        city_roads = spark.read.parquet(args.city_road_input)
        points = latest_city_road_points(city_roads).cache()
        try:
            area_sha = reference_sha256(points)
        finally:
            points.unpersist()
        edge_mapping = build_edge_city_mapping(
            edges, stops, city_roads, args.max_distance_meters, args.ambiguity_meters
        ).cache()
        segment_mapping = build_segment_city_mapping(edge_mapping).cache()
        try:
            if root:
                try:
                    _write_hdfs(root, edge_mapping, segment_mapping, verified, args.bus_master_sha, area_sha)
                except Exception:
                    LOG.exception("HDFS bus-city mapping export failed; Redis will continue")
                    if args.skip_redis:
                        raise
            if not args.skip_redis:
                result = publish_segment_city_mapping(
                    segment_mapping,
                    host=os.environ.get("REDIS_HOST", "redis"),
                    port=int(os.environ.get("REDIS_PORT", "6379")),
                    password=os.environ.get("REDIS_PASSWORD"),
                    master_version=verified[1],
                    area_reference_sha=area_sha,
                    crosswalk_sha=verified[2],
                    bus_master_sha=args.bus_master_sha,
                    ttl_seconds=args.redis_ttl_seconds,
                )
                LOG.info("Redis bus-city mapping activated: %s", result)
        finally:
            segment_mapping.unpersist()
            edge_mapping.unpersist()
    finally:
        spark.stop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
