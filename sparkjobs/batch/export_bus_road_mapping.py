"""승인된 artifact로 segmentId→TOPIS LINK_ID 대응표를 HDFS와 Redis에 배포한다."""

import argparse
import logging
import os

from common.bus_crosswalk import load_crosswalk_edges
from common.bus_road_mapping import (
    build_edge_road_mapping,
    build_segment_road_mapping,
    load_verified_road_paths,
)
from common.bus_road_redis import DEFAULT_TTL_SECONDS, publish_segment_road_mapping
from common.bus_segment_mapping import build_bus_segment_edges
from pyspark.sql import SparkSession

LOG = logging.getLogger(__name__)


def _write_hdfs(spark, root, edge_mapping, segment_mapping, metadata):
    spark.createDataFrame(
        [
            (
                metadata["mappingVersion"],
                metadata["masterVersion"],
                metadata["busCrosswalkSha256"],
                metadata["roadCrosswalkVersion"],
                metadata["roadCrosswalkSha256"],
                metadata["trafficLinkMasterVersion"],
                metadata["trafficLinkMasterSha256"],
                metadata["asOf"],
            )
        ],
        "mappingVersion string, masterVersion string, busCrosswalkSha256 string, "
        "roadCrosswalkVersion string, roadCrosswalkSha256 string, "
        "trafficLinkMasterVersion string, trafficLinkMasterSha256 string, asOf string",
    ).write.mode("errorifexists").json(root + "/_run")
    edge_mapping.write.mode("errorifexists").partitionBy("providerNamespace").parquet(root + "/edges")
    segment_mapping.write.mode("errorifexists").parquet(root + "/mappings")
    edge_mapping.groupBy("mappingStatus", "mappingReason").count().write.mode("errorifexists").json(
        root + "/quality/edges"
    )
    segment_mapping.groupBy("mappingStatus", "mappingReason").count().write.mode("errorifexists").json(
        root + "/quality/segments"
    )

    edge_count = edge_mapping.count()
    segment_count = segment_mapping.count()
    resolved_count = segment_mapping.filter("mappingStatus = 'RESOLVED'").count()
    unresolved_count = segment_count - resolved_count
    spark.createDataFrame(
        [
            (
                metadata["masterVersion"],
                edge_count,
                segment_count,
                resolved_count,
                unresolved_count,
            )
        ],
        "masterVersion string, edgeRows long, segmentRows long, resolvedRows long, unresolvedRows long",
    ).write.mode("errorifexists").json(root + "/complete")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--crosswalk", required=True)
    parser.add_argument("--crosswalk-sha", required=True)
    parser.add_argument("--road-crosswalk", required=True)
    parser.add_argument("--road-crosswalk-sha", required=True)
    parser.add_argument("--traffic-link-master", required=True)
    parser.add_argument("--traffic-link-master-sha", required=True)
    parser.add_argument("--as-of", required=True, help="시간대가 포함된 기준시각")
    parser.add_argument("--output", help="새 HDFS 또는 file 출력 경로. 비우면 Redis만 배포")
    parser.add_argument("--skip-redis", action="store_true")
    parser.add_argument(
        "--redis-ttl-seconds",
        type=int,
        default=int(os.environ.get("BUS_ROAD_MAPPING_TTL_SECONDS", DEFAULT_TTL_SECONDS)),
    )
    args = parser.parse_args(argv)
    root = args.output.rstrip("/") if args.output else None
    if not root and args.skip_redis:
        parser.error("--output과 Redis 중 하나는 활성화해야 합니다")

    spark = (
        SparkSession.builder.appName("ddubi-bus-road-link-mapping")
        .config("spark.sql.session.timeZone", "Asia/Seoul")
        .getOrCreate()
    )
    try:
        verified_edges = load_crosswalk_edges(spark, args.crosswalk, args.crosswalk_sha)
        edges = build_bus_segment_edges(verified_edges)
        paths, metadata = load_verified_road_paths(
            spark,
            bus_crosswalk_path=args.crosswalk,
            bus_crosswalk_sha=args.crosswalk_sha,
            road_crosswalk_path=args.road_crosswalk,
            road_crosswalk_sha=args.road_crosswalk_sha,
            traffic_link_master_path=args.traffic_link_master,
            traffic_link_master_sha=args.traffic_link_master_sha,
            as_of=args.as_of,
        )
        if verified_edges[1] != metadata["masterVersion"]:
            raise ValueError("bus and road master versions differ")

        edge_mapping = build_edge_road_mapping(edges, paths, metadata).cache()
        segment_mapping = build_segment_road_mapping(edge_mapping).cache()
        try:
            if segment_mapping.filter("mappingStatus = 'RESOLVED'").limit(1).count() == 0:
                raise ValueError("no approved segment-to-TOPIS mapping is resolved")
            if root:
                try:
                    _write_hdfs(spark, root, edge_mapping, segment_mapping, metadata)
                except Exception:
                    LOG.exception("HDFS bus-road mapping export failed; Redis will continue")
                    if args.skip_redis:
                        raise
            if not args.skip_redis:
                result = publish_segment_road_mapping(
                    segment_mapping,
                    host=os.environ.get("REDIS_HOST", "redis"),
                    port=int(os.environ.get("REDIS_PORT", "6379")),
                    password=os.environ.get("REDIS_PASSWORD"),
                    master_version=metadata["masterVersion"],
                    road_crosswalk_version=metadata["roadCrosswalkVersion"],
                    road_crosswalk_sha=metadata["roadCrosswalkSha256"],
                    traffic_link_master_version=metadata["trafficLinkMasterVersion"],
                    traffic_link_master_sha=metadata["trafficLinkMasterSha256"],
                    as_of=metadata["asOf"],
                    ttl_seconds=args.redis_ttl_seconds,
                )
                LOG.info("Redis bus-road mapping activated: %s", result)
        finally:
            segment_mapping.unpersist()
            edge_mapping.unpersist()
    finally:
        spark.stop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
