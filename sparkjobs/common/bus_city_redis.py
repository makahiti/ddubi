"""버스 구간→도시 영역 대응표를 Redis에 원자적으로 배포한다."""

import datetime as dt
import json
import re
import uuid

from pyspark.sql import functions as F

from common.bus_city_mapping import MAPPING_VERSION

DEFAULT_TTL_SECONDS = 2_592_000
_SAFE_KEY_PART = re.compile(r"^[A-Za-z0-9._-]+$")
_ACTIVATE_LUA = r"""
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


def _key_part(name, value):
    value = str(value or "")
    if not value or not _SAFE_KEY_PART.fullmatch(value):
        raise ValueError(f"{name} contains unsafe Redis key characters: {value!r}")
    return value


def mapping_keys(mapping_version, master_version, area_reference_sha, run_id=None):
    base = "ml:mapping:bus-city:{}:{}:{}".format(
        _key_part("mapping_version", mapping_version),
        _key_part("master_version", master_version),
        _key_part("area_reference_sha", area_reference_sha),
    )
    keys = {
        "final": base,
        "active": "ml:mapping:bus-city:active:{}".format(_key_part("master_version", master_version)),
        "metadata": base + ":meta",
    }
    if run_id is not None:
        keys["staging"] = base + ":staging:" + _key_part("run_id", run_id)
    return keys


def redis_mapping_rows(segment_mapping):
    columns = [
        "segmentId",
        "masterVersion",
        "mappingVersion",
        "primaryProviderAreaId",
        "candidateProviderAreaIds",
        "averageNearestAreaDistanceMeters",
        "mappingStatus",
        "mappingReason",
        "maxDistanceMeters",
        "ambiguityMeters",
    ]
    missing = [name for name in columns if name not in segment_mapping.columns]
    if missing:
        raise ValueError(f"segment city mapping is missing Redis columns: {missing}")
    return segment_mapping.select(
        F.col("segmentId").alias("redisField"),
        F.to_json(F.struct(*[F.col(name) for name in columns]), {"ignoreNullFields": "false"}).alias("redisValue"),
    )


def activate_staged_mapping(client, keys, expected_rows, ttl_seconds, metadata):
    if expected_rows <= 0 or ttl_seconds <= 0:
        raise ValueError("positive row count and TTL required")
    return int(
        client.eval(
            _ACTIVATE_LUA,
            4,
            keys["staging"],
            keys["final"],
            keys["active"],
            keys["metadata"],
            expected_rows,
            ttl_seconds,
            json.dumps(metadata, ensure_ascii=False, separators=(",", ":")),
        )
    )


def publish_segment_city_mapping(
    segment_mapping,
    *,
    host,
    port,
    password,
    master_version,
    area_reference_sha,
    crosswalk_sha,
    bus_master_sha,
    mapping_version=MAPPING_VERSION,
    ttl_seconds=DEFAULT_TTL_SECONDS,
):
    import redis

    ttl_seconds = int(ttl_seconds)
    rows = redis_mapping_rows(segment_mapping).cache()
    expected = rows.count()
    distinct = rows.select("redisField").distinct().count()
    invalid = rows.filter(F.col("redisField").isNull() | (F.length(F.trim("redisField")) == 0)).limit(1).count()
    if expected <= 0 or expected != distinct or invalid:
        rows.unpersist()
        raise ValueError("Redis city mapping requires unique non-empty segmentId rows")
    keys = mapping_keys(mapping_version, master_version, area_reference_sha, uuid.uuid4().hex)
    connection = dict(host=host, port=int(port), password=password or None, decode_responses=True)
    driver = None

    def write_partition(partition):
        import redis as redis_module

        client = redis_module.Redis(**connection)
        pipe, pending = client.pipeline(transaction=False), 0
        try:
            for row in partition:
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
            "mappingVersion": mapping_version,
            "masterVersion": master_version,
            "areaReferenceSha256": area_reference_sha,
            "crosswalkSha256": crosswalk_sha,
            "busMasterSha256": bus_master_sha,
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
