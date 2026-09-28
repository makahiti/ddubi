"""엄격한 단일 도로 후보를 별도 PROVISIONAL Redis 대응표로 발행한다."""

import argparse
import json
import logging
import os
from pathlib import Path

from common.provisional_road_mapping import DEFAULT_TTL_SECONDS, load_rows, publish

LOG = logging.getLogger(__name__)


def main(argv=None):
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidates", required=True, help="road-occurrence-review의 occurrence-candidates.jsonl")
    parser.add_argument("--candidates-sha", required=True)
    parser.add_argument("--master-version", required=True)
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
                    "asOf": args.as_of,
                },
                args.redis_ttl_seconds,
            )
        finally:
            client.close()
    LOG.info(
        "provisional road mapping result=%s",
        json.dumps({key: value for key, value in artifact.items() if key != "mappings"}, ensure_ascii=False),
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
