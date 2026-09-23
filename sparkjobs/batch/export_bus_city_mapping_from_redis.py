"""Redis 최신 도시 도로값으로 버스 구간→도시 영역 대응표를 발행한다(Spark 불필요).

`export_bus_city_mapping.py` 와 결과 형식·Redis 키·활성 포인터가 같다. 다른 것은 근거 자료를
HDFS Parquet 이 아니라 Redis `city:latest:road:*` 에서 읽는 점뿐이다. 운영에서
`CITY_SILVER_OUTPUT_URI` 가 비어 Parquet 이 없을 때 쓴다.
"""

import argparse
import json
import logging
import os
from pathlib import Path

from common.city_area_mapping import (
    DEFAULT_AMBIGUITY_METERS,
    DEFAULT_MAX_DISTANCE_METERS,
    DEFAULT_TTL_SECONDS,
    build_rows,
    file_sha256,
    load_area_points,
    load_master,
    publish,
    stop_index,
)

LOG = logging.getLogger(__name__)


def main(argv=None):
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--crosswalk", required=True, help="버스 crosswalk json(.gz)")
    parser.add_argument("--bus-master", required=True, help="정류장 좌표가 있는 버스 마스터 json(.gz)")
    parser.add_argument("--output", help="검토할 결과 JSON")
    parser.add_argument("--skip-redis", action="store_true")
    parser.add_argument("--max-distance-meters", type=float, default=DEFAULT_MAX_DISTANCE_METERS)
    parser.add_argument("--ambiguity-meters", type=float, default=DEFAULT_AMBIGUITY_METERS)
    parser.add_argument(
        "--redis-ttl-seconds",
        type=int,
        default=int(os.environ.get("BUS_CITY_MAPPING_TTL_SECONDS", DEFAULT_TTL_SECONDS)),
    )
    args = parser.parse_args(argv)
    if not args.output and args.skip_redis:
        parser.error("--output과 Redis 중 하나는 활성화해야 합니다")

    crosswalk = load_master(args.crosswalk)
    master = load_master(args.bus_master)
    master_version = crosswalk["masterVersion"]
    if master.get("version") != master_version:
        raise ValueError("crosswalk 와 bus master 의 masterVersion 이 다릅니다")

    import redis

    client = redis.Redis(
        host=os.environ.get("REDIS_HOST", "redis"),
        port=int(os.environ.get("REDIS_PORT", "6379")),
        password=os.environ.get("REDIS_PASSWORD") or None,
        decode_responses=True,
    )
    try:
        points, area_sha = load_area_points(client)
        if not points:
            raise ValueError("Redis 에 쓸 수 있는 city:latest:road 좌표가 없습니다")
        rows, counts = build_rows(
            crosswalk["edges"],
            stop_index(master),
            points,
            master_version,
            area_sha,
            file_sha256(args.crosswalk),
            args.max_distance_meters,
            args.ambiguity_meters,
        )
        artifact = {
            "schemaVersion": 1,
            "candidateSource": "redis-city-latest-road",
            "masterVersion": master_version,
            "crosswalkSha256": file_sha256(args.crosswalk),
            "busMasterSha256": file_sha256(args.bus_master),
            "areaReferenceSha256": area_sha,
            "areaPointCount": len(points),
            "rowCount": len(rows),
            "statusCounts": counts,
            "maxDistanceMeters": args.max_distance_meters,
            "ambiguityMeters": args.ambiguity_meters,
            "mappings": list(rows.values()),
        }
        if args.output:
            Path(args.output).write_text(json.dumps(artifact, ensure_ascii=False, indent=2), encoding="utf-8")
        if not args.skip_redis:
            artifact["redis"] = publish(
                client,
                rows,
                {
                    "masterVersion": master_version,
                    "areaReferenceSha256": area_sha,
                    "crosswalkSha256": artifact["crosswalkSha256"],
                    "busMasterSha256": artifact["busMasterSha256"],
                    "candidateSource": "redis-city-latest-road",
                },
                args.redis_ttl_seconds,
            )
    finally:
        client.close()
    LOG.info(
        "bus-city mapping result=%s",
        json.dumps({key: value for key, value in artifact.items() if key != "mappings"}, ensure_ascii=False),
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
