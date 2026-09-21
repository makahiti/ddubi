"""버스 segmentId와 현재 수집 중인 기상청 날씨 격자의 대응표를 Parquet으로 만든다."""

import argparse
import hashlib
import logging
import os

from common.bus_crosswalk import load_crosswalk_edges
from common.bus_segment_mapping import build_bus_segment_edges
from common.bus_weather_mapping import (
    MAPPING_VERSION,
    build_edge_weather_mapping,
    build_segment_weather_mapping,
    load_bus_stops,
)
from common.bus_weather_redis import (
    DEFAULT_TTL_SECONDS,
    publish_segment_weather_mapping,
)
from common.kma_grid import DEFAULT_WEATHER_GRID_POINTS, parse_weather_grid_points
from pyspark.sql import SparkSession
from pyspark.sql import types as T

LOG = logging.getLogger(__name__)


def _write_hdfs_outputs(
    spark,
    root,
    edge_mapping,
    segment_mapping,
    verified_edges,
    bus_master_sha,
    grid_config_sha,
    collected_grid_count,
):
    spark.createDataFrame(
        [
            (
                MAPPING_VERSION,
                verified_edges[1],
                verified_edges[2],
                bus_master_sha,
                grid_config_sha,
                collected_grid_count,
            )
        ],
        "mappingVersion string, masterVersion string, crosswalkSha256 string, "
        "busMasterSha256 string, collectedGridConfigSha256 string, collectedGridCount int",
    ).write.mode("errorifexists").json(root + "/_run")

    edge_mapping.write.mode("errorifexists").partitionBy("providerNamespace").parquet(root + "/edges")
    segment_mapping.write.mode("errorifexists").parquet(root + "/mappings")
    edge_mapping.groupBy("mappingStatus", "gridRelation").count().write.mode("errorifexists").json(
        root + "/quality/edges"
    )
    segment_mapping.groupBy("mappingStatus", "gridRelation").count().write.mode("errorifexists").json(
        root + "/quality/segments"
    )

    edge_count = edge_mapping.count()
    segment_count = segment_mapping.count()
    resolved_count = segment_mapping.filter("mappingStatus = 'RESOLVED'").count()
    outside_count = segment_mapping.filter("mappingStatus = 'OUT_OF_COVERAGE'").count()
    unresolved_count = segment_mapping.filter("mappingStatus IN ('UNRESOLVED', 'AMBIGUOUS')").count()
    spark.createDataFrame(
        [
            (
                verified_edges[1],
                edge_count,
                segment_count,
                resolved_count,
                outside_count,
                unresolved_count,
            )
        ],
        "masterVersion string, edgeRows long, segmentRows long, resolvedRows long, "
        "outOfCoverageRows long, unresolvedOrAmbiguousRows long",
    ).write.mode("errorifexists").json(root + "/complete")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--crosswalk", required=True)
    parser.add_argument("--crosswalk-sha", required=True)
    parser.add_argument("--bus-master", required=True)
    parser.add_argument("--bus-master-sha", required=True)
    parser.add_argument("--output", help="새 출력 디렉터리(HDFS 또는 file URI). 비우면 Redis만 배포")
    parser.add_argument("--skip-redis", action="store_true", help="Redis 배포를 생략")
    parser.add_argument(
        "--redis-ttl-seconds",
        type=int,
        default=int(os.environ.get("BUS_WEATHER_MAPPING_TTL_SECONDS", DEFAULT_TTL_SECONDS)),
    )
    parser.add_argument(
        "--weather-grid-points",
        default=os.environ.get("WEATHER_GRID_POINTS") or DEFAULT_WEATHER_GRID_POINTS,
        help="Collector와 같은 지역명:nx:ny 목록",
    )
    args = parser.parse_args(argv)

    collected_pairs = parse_weather_grid_points(args.weather_grid_points)
    grid_config_sha = hashlib.sha256(",".join(f"{nx}:{ny}" for nx, ny in collected_pairs).encode("utf-8")).hexdigest()
    root = args.output.rstrip("/") if args.output else None
    if not root and args.skip_redis:
        parser.error("--output과 Redis 중 하나는 활성화해야 합니다")
    spark = SparkSession.builder.appName("ddubi-bus-weather-grid-mapping").getOrCreate()
    try:
        verified_edges = load_crosswalk_edges(spark, args.crosswalk, args.crosswalk_sha, args.bus_master_sha)
        edges = build_bus_segment_edges(verified_edges)
        stops = load_bus_stops(spark, args.bus_master, args.bus_master_sha, verified_edges[1])
        collected = spark.createDataFrame(
            collected_pairs,
            T.StructType(
                [
                    T.StructField("collectedNx", T.IntegerType(), False),
                    T.StructField("collectedNy", T.IntegerType(), False),
                ]
            ),
        )
        edge_mapping = build_edge_weather_mapping(edges, stops, collected).cache()
        segment_mapping = build_segment_weather_mapping(edge_mapping).cache()
        try:
            if root:
                try:
                    _write_hdfs_outputs(
                        spark,
                        root,
                        edge_mapping,
                        segment_mapping,
                        verified_edges,
                        args.bus_master_sha,
                        grid_config_sha,
                        len(collected_pairs),
                    )
                except Exception:
                    # 이력 저장 장애가 서비스용 Redis 대응표 배포를 막지 않게 한다.
                    LOG.exception("HDFS bus-weather mapping export failed; Redis will continue")
                    if args.skip_redis:
                        raise

            if not args.skip_redis:
                result = publish_segment_weather_mapping(
                    segment_mapping,
                    host=os.environ.get("REDIS_HOST", "redis"),
                    port=int(os.environ.get("REDIS_PORT", "6379")),
                    password=os.environ.get("REDIS_PASSWORD"),
                    master_version=verified_edges[1],
                    grid_config_sha=grid_config_sha,
                    crosswalk_sha=verified_edges[2],
                    bus_master_sha=args.bus_master_sha,
                    ttl_seconds=args.redis_ttl_seconds,
                )
                LOG.info("Redis bus-weather mapping activated: %s", result)
        finally:
            segment_mapping.unpersist()
            edge_mapping.unpersist()
    finally:
        spark.stop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
