"""Backend가 배포한 버스·지하철 마스터를 구간 사실 검증용 레지스트리로 변환한다.

마스터 파일을 다시 채번하지 않고 계약화된 인접 edge만 추출한다. 기존 bus_crosswalk와
subway_crosswalk의 SHA·버전·순서 검증을 거치며 같은 ID의 버전을 섞지 않는다.
출력 JSON은 segment_baseline/segment_status가 공용으로 읽는다. 서비스 DB 쓰기는 없다.
"""

import argparse
import json
from pyspark.sql import SparkSession, functions as F, types as T
from common.bus_crosswalk import load_crosswalk
from common.subway_crosswalk import SubwayCrosswalk
from common.segment_facts import REGISTRY_SCHEMA


def main(argv=None):
    """명시적으로 받은 배포 파일의 edge·버전을 새 출력 경로에 고정한다. 실패 시 빈 파일을 활성화하지 않는다."""
    p = argparse.ArgumentParser()
    p.add_argument("--bus")
    p.add_argument("--bus-sha")
    p.add_argument("--subway")
    p.add_argument("--subway-sha")
    p.add_argument("--output", required=True)
    args = p.parse_args(argv)
    if not args.bus and not args.subway:
        raise ValueError("at least one authoritative master required")
    spark = SparkSession.builder.appName("ddubi-segment-registry").getOrCreate()
    try:
        frames = []
        if args.bus:
            index, version, sha = load_crosswalk(spark, args.bus, args.bus_sha)
            frames.append(
                index.select(F.explode("canonicalCandidates").alias("segmentId"))
                .withColumn("masterVersion", F.lit(version))
                .distinct()
            )
        if args.subway:
            crosswalk = SubwayCrosswalk(args.subway, args.subway_sha)
            content = json.dumps(
                [
                    dict(segmentId=s, masterVersion=crosswalk.version)
                    for s in sorted(crosswalk.edges)
                ]
            )
            frames.append(
                spark.range(1)
                .select(
                    F.explode(
                        F.from_json(F.lit(content), T.ArrayType(REGISTRY_SCHEMA))
                    ).alias("r")
                )
                .select("r.*")
            )
        result = frames[0]
        for other in frames[1:]:
            result = result.unionByName(other)
        result.distinct().write.mode("errorifexists").json(args.output)
    finally:
        spark.stop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
