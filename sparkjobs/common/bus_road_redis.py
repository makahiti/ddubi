"""segmentId→TOPIS 링크 대응표를 Redis versioned Hash로 안전하게 배포한다."""

import datetime as dt
import json
import re
import uuid

from pyspark.sql import functions as F

from common.bus_road_mapping import MAPPING_VERSION

DEFAULT_TTL_SECONDS = 2_592_000
_SAFE = re.compile(r"^[A-Za-z0-9._-]+$")
_ACTIVATE = r"""
local actual = redis.call('HLEN', KEYS[1])
if actual ~= tonumber(ARGV[1]) then
  return redis.error_reply('staging row count mismatch: expected=' .. ARGV[1] .. ', actual=' .. actual)
end
redis.call('RENAME', KEYS[1], KEYS[2])
redis.call('EXPIRE', KEYS[2], tonumber(ARGV[2]))
redis.call('SET', KEYS[3], KEYS[2], 'EX', tonumber(ARGV[2]))
redis.call('SET', KEYS[4], ARGV[3], 'EX', tonumber(ARGV[2]))
return actual
"""


def _part(name, value):
    value = str(value or "")
    if not value or not _SAFE.fullmatch(value):
        raise ValueError(f"{name} contains unsafe Redis key characters: {value!r}")
    return value


def mapping_keys(mapping_version, master_version, road_crosswalk_sha, run_id=None):
    base = "ml:mapping:bus-road:{}:{}:{}".format(
        _part("mapping_version", mapping_version),
        _part("master_version", master_version),
        _part("road_crosswalk_sha", road_crosswalk_sha),
    )
    result = {
        "final": base,
        "active": f"ml:mapping:bus-road:active:{master_version}",
        "metadata": base + ":meta",
    }
    if run_id is not None:
        result["staging"] = base + ":staging:" + _part("run_id", run_id)
    return result


def redis_mapping_rows(segment_mapping):
    fields = [
        "segmentId",
        "masterVersion",
        "mappingVersion",
        "roadCrosswalkVersion",
        "trafficLinkMasterVersion",
        "mappingAsOf",
        "topisLinkNamespace",
        "topisLinkIds",
        "topisLinkCount",
        "mappingStatus",
        "mappingReason",
        "occurrenceCoverageRatio",
        "roadPathValidFrom",
        "roadPathValidUntil",
    ]
    missing = [name for name in fields if name not in segment_mapping.columns]
    if missing:
        raise ValueError(f"segment road mapping is missing Redis columns: {missing}")
    return segment_mapping.select(
        F.col("segmentId").alias("redisField"),
        F.to_json(
            F.struct(*[F.col(name) for name in fields]),
            options={"ignoreNullFields": "false"},
        ).alias("redisValue"),
    )


def activate_staged_mapping(client, keys, expected_rows, ttl_seconds, metadata):
    if expected_rows <= 0 or ttl_seconds <= 0:
        raise ValueError("expected rows and TTL must be positive")
    value = json.dumps(metadata, ensure_ascii=False, separators=(",", ":"))
    return int(
        client.eval(
            _ACTIVATE,
            4,
            keys["staging"],
            keys["final"],
            keys["active"],
            keys["metadata"],
            expected_rows,
            ttl_seconds,
            value,
        )
    )


def publish_segment_road_mapping(
    segment_mapping,
    *,
    host,
    port,
    password,
    master_version,
    bus_crosswalk_sha,
    road_crosswalk_version,
    road_crosswalk_sha,
    traffic_link_master_version,
    traffic_link_master_sha,
    as_of,
    ttl_seconds=DEFAULT_TTL_SECONDS,
):
    """완성된 Hash만 active 포인터로 노출한다."""
    import redis

    ttl_seconds = int(ttl_seconds)
    if ttl_seconds <= 0:
        raise ValueError("ttl_seconds must be positive")
    rows = redis_mapping_rows(segment_mapping).cache()
    expected = rows.count()
    distinct = rows.select("redisField").distinct().count()
    invalid = rows.filter(F.col("redisField").isNull() | (F.length(F.trim("redisField")) == 0)).limit(1).count()
    if expected == 0 or expected != distinct or invalid:
        rows.unpersist()
        raise ValueError(
            f"Redis road mapping requires unique non-empty segmentId rows: rows={expected}, distinct={distinct}"
        )

    keys = mapping_keys(MAPPING_VERSION, master_version, road_crosswalk_sha, uuid.uuid4().hex)
    connection = {
        "host": host,
        "port": int(port),
        "password": password or None,
        "decode_responses": True,
    }
    driver = None

    def write_partition(items):
        import redis as redis_module

        client = redis_module.Redis(**connection)
        pipe = client.pipeline(transaction=False)
        pending = 0
        try:
            for row in items:
                pipe.hset(keys["staging"], row.redisField, row.redisValue)
                pending += 1
                if pending >= 1000:
                    pipe.execute()
                    pending = 0
            if pending:
                pipe.execute()
        finally:
            client.close()

    try:
        driver = redis.Redis(**connection)
        driver.delete(keys["staging"])
        driver.hset(keys["staging"], "__ddubi_staging__", "1")
        driver.expire(keys["staging"], min(ttl_seconds, 3600))
        rows.foreachPartition(write_partition)
        driver.hdel(keys["staging"], "__ddubi_staging__")
        metadata = {
            "mappingVersion": MAPPING_VERSION,
            "masterVersion": master_version,
            "crosswalkSha256": bus_crosswalk_sha,
            "roadCrosswalkVersion": road_crosswalk_version,
            "roadCrosswalkSha256": road_crosswalk_sha,
            "trafficLinkMasterVersion": traffic_link_master_version,
            "trafficLinkMasterSha256": traffic_link_master_sha,
            "asOf": as_of,
            "rowCount": expected,
            "publishedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        }
        activated = activate_staged_mapping(driver, keys, expected, ttl_seconds, metadata)
        return {
            "activeKey": keys["active"],
            "mappingKey": keys["final"],
            "metadataKey": keys["metadata"],
            "rowCount": activated,
            "ttlSeconds": ttl_seconds,
        }
    finally:
        if driver is not None:
            try:
                driver.delete(keys["staging"])
            finally:
                driver.close()
        rows.unpersist()
