"""공식 노선 링크 감사 후보(mapping-candidates.jsonl)를 PROVISIONAL Redis 대응표로 발행한다.

export_bus_road_provisional_mapping 과 같은 Redis 키·활성 포인터를 쓴다. 입력 형식만 다르다 —
GTFS 형상 후보가 아니라 backend/tools/road-impact/audit.py 의 공식 BIS·TOPIS 링크 후보를 받는다.
"""

import argparse
import json
import logging
import os
from pathlib import Path

from common.official_road_mapping import load_rows
from common.provisional_road_mapping import DEFAULT_TTL_SECONDS, publish

LOG = logging.getLogger(__name__)


def main(argv=None):
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidates", required=True, help="road-impact audit.py 의 mapping-candidates.jsonl")
    parser.add_argument("--candidates-sha", required=True)
    parser.add_argument("--master-version", required=True, help="저장 경로 edge 의 mappingVersion 과 같아야 한다")
    parser.add_argument("--bus-crosswalk-sha", required=True)
    parser.add_argument(
        "--traffic-link-master-version",
        default=os.environ.get("ROAD_PROVISIONAL_TRAFFIC_LINK_MASTER_VERSION", "provisional-accinfo-input-v1"),
    )
    parser.add_argument("--as-of", required=True)
    parser.add_argument("--valid-until", required=True)
    parser.add_argument("--output", help="검토할 PROVISIONAL JSON artifact")
    parser.add_argument("--skip-redis", action="store_true")
    parser.add_argument(
        "--redis-ttl-seconds",
        type=int,
        default=int(os.environ.get("BUS_ROAD_PROVISIONAL_MAPPING_TTL_SECONDS", DEFAULT_TTL_SECONDS)),
    )
    args = parser.parse_args(argv)
    if not args.output and args.skip_redis:
        parser.error("--output과 Redis 중 하나는 활성화해야 합니다")

    rows, rejected = load_rows(
        args.candidates,
        args.candidates_sha,
        args.master_version,
        args.traffic_link_master_version,
        args.as_of,
        args.valid_until,
    )
    artifact = {
        "schemaVersion": 1,
        "mappingStatus": "PROVISIONAL",
        "candidateSource": "road-impact-audit-official-route-links",
        "masterVersion": args.master_version,
        "crosswalkSha256": args.bus_crosswalk_sha.lower(),
        "trafficLinkMasterVersion": args.traffic_link_master_version,
        "candidateSourceSha256": args.candidates_sha.lower(),
        "asOf": args.as_of,
        "validUntil": args.valid_until,
        "rowCount": len(rows),
        "rejected": rejected,
        "mappings": list(rows.values()),
    }
    if args.output:
        Path(args.output).write_text(json.dumps(artifact, ensure_ascii=False, indent=2), encoding="utf-8")
    if not args.skip_redis:
        import redis

        client = redis.Redis(
            host=os.environ.get("REDIS_HOST", "redis"),
            port=int(os.environ.get("REDIS_PORT", "6379")),
            password=os.environ.get("REDIS_PASSWORD") or None,
            decode_responses=True,
        )
        try:
            artifact["redis"] = publish(
                client,
                rows,
                {
                    "masterVersion": args.master_version,
                    "crosswalkSha256": args.bus_crosswalk_sha.lower(),
                    "trafficLinkMasterVersion": args.traffic_link_master_version,
                    "candidateSourceSha256": args.candidates_sha.lower(),
                    "candidateSource": "road-impact-audit-official-route-links",
                    "asOf": args.as_of,
                },
                args.redis_ttl_seconds,
            )
        finally:
            client.close()
    LOG.info(
        "official provisional road mapping result=%s",
        json.dumps({key: value for key, value in artifact.items() if key != "mappings"}, ensure_ascii=False),
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
