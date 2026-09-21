"""공식 버스 대응표를 Feature 조립용 segmentId Parquet으로 내보낸다.

원본 gzip 대응표는 수정하지 않는다. SHA, 버전, ID 형식을 검증한 뒤 제공자 노선과
인접 정류장 쌍을 대응표에 이미 들어 있는 canonical segmentId에 연결한다.
"""

import argparse

from common.bus_crosswalk import load_crosswalk_edges
from common.bus_segment_mapping import (
    build_bus_segment_edges,
    build_bus_segment_mapping,
    build_bus_segment_registry,
)
from pyspark.sql import SparkSession
from pyspark.sql import functions as F


def prepare(verified_edges):
    """같은 검증 결과에서 원본 edge, 조인 대응표, 고유 구간 목록을 만든다."""
    edges = build_bus_segment_edges(verified_edges)
    mapping = build_bus_segment_mapping(edges)
    registry = build_bus_segment_registry(mapping)
    return edges, mapping, registry


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--crosswalk", required=True, help="검증된 bus crosswalk gzip 경로")
    parser.add_argument("--crosswalk-sha", required=True, help="배포 manifest의 SHA-256")
    parser.add_argument("--output", required=True, help="새 출력 디렉터리(HDFS 또는 file URI)")
    args = parser.parse_args(argv)

    spark = SparkSession.builder.appName("ddubi-bus-segment-mapping").getOrCreate()
    root = args.output.rstrip("/")
    verified_edges = None
    try:
        verified_edges = load_crosswalk_edges(spark, args.crosswalk, args.crosswalk_sha)
        edges, mapping, registry = prepare(verified_edges)
        edges = edges.cache()
        mapping = mapping.cache()
        registry = registry.cache()
        try:
            # 출력 루트를 먼저 예약해 같은 버전의 실수 덮어쓰기를 막는다.
            spark.createDataFrame(
                [(verified_edges[1], verified_edges[2], "bus-segment-mapping-v1")],
                "masterVersion string, crosswalkSha256 string, mappingVersion string",
            ).write.mode("errorifexists").json(root + "/_run")

            edges.write.mode("errorifexists").partitionBy("providerNamespace").parquet(root + "/edges")
            mapping.write.mode("errorifexists").partitionBy("providerNamespace").parquet(root + "/mappings")
            registry.write.mode("errorifexists").parquet(root + "/segments")

            quality = mapping.groupBy("mappingStatus").count()
            quality.write.mode("errorifexists").json(root + "/quality/mapping-status")

            mapping_count = mapping.count()
            segment_count = registry.count()
            unresolved_count = mapping.filter(F.col("mappingStatus") != "RESOLVED").count()
            spark.createDataFrame(
                [
                    (
                        verified_edges[1],
                        verified_edges[2],
                        edges.count(),
                        mapping_count,
                        segment_count,
                        unresolved_count,
                    )
                ],
                "masterVersion string, crosswalkSha256 string, edgeRows long, mappingRows long, "
                "uniqueSegments long, unresolvedRows long",
            ).write.mode("errorifexists").json(root + "/complete")
        finally:
            registry.unpersist()
            mapping.unpersist()
            edges.unpersist()
    finally:
        spark.stop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
