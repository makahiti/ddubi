"""버스 구간→날씨 격자 대응표를 Redis에 원자적으로 배포한다."""

import datetime as dt
import json
import re
import uuid

from pyspark.sql import functions as F

from common.bus_weather_mapping import MAPPING_VERSION

DEFAULT_TTL_SECONDS = 2_592_000  # 30일. volatile-ttl 정책에서 퇴출 가능한 상태로 둔다.
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


def mapping_keys(mapping_version, master_version, grid_config_sha, run_id=None):
    """배포 버전 키와 active 포인터 키를 만든다."""
    mapping_version = _key_part("mapping_version", mapping_version)
    master_version = _key_part("master_version", master_version)
    grid_config_sha = _key_part("grid_config_sha", grid_config_sha)
    base = f"ml:mapping:bus-weather:{mapping_version}:{master_version}:{grid_config_sha}"
    keys = {
        "final": base,
        "active": f"ml:mapping:bus-weather:active:{master_version}",
        "metadata": base + ":meta",
    }
    if run_id is not None:
        keys["staging"] = base + ":staging:" + _key_part("run_id", run_id)
    return keys


def redis_mapping_rows(segment_mapping):
    """동일한 segment mapping DataFrame에서 Redis용 field/value 두 열만 직렬화한다."""
    payload_columns = [
        "segmentId",
        "masterVersion",
        "mappingVersion",
        "weatherGridVersion",
        "nx",
        "ny",
        "gridRelation",
        "mappingStatus",
        "mappingReason",
    ]
    missing = [name for name in payload_columns if name not in segment_mapping.columns]
    if missing:
        raise ValueError(f"segment mapping is missing Redis columns: {missing}")
    return segment_mapping.select(
        F.col("segmentId").alias("redisField"),
        F.to_json(
            F.struct(*[F.col(name) for name in payload_columns]),
            options={"ignoreNullFields": "false"},
        ).alias("redisValue"),
    )


def activate_staged_mapping(client, keys, expected_rows, ttl_seconds, metadata):
    """완성된 staging Hash만 최종 키로 승격하고 active 포인터를 한 번에 바꾼다."""
    if expected_rows <= 0:
        raise ValueError("expected_rows must be positive")
    if ttl_seconds <= 0:
        raise ValueError("ttl_seconds must be positive")
    encoded_metadata = json.dumps(metadata, ensure_ascii=False, separators=(",", ":"))
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
            encoded_metadata,
        )
    )


def publish_segment_weather_mapping(
    segment_mapping,
    *,
    host,
    port,
    password,
    master_version,
    grid_config_sha,
    crosswalk_sha,
    bus_master_sha,
    mapping_version=MAPPING_VERSION,
    ttl_seconds=DEFAULT_TTL_SECONDS,
):
    """Spark DataFrame을 staging Hash에 분산 기록한 뒤 검증·활성화한다."""
    import redis

    ttl_seconds = int(ttl_seconds)
    if ttl_seconds <= 0:
        raise ValueError("ttl_seconds must be positive")
    rows = redis_mapping_rows(segment_mapping).cache()
    expected_rows = rows.count()
    distinct_rows = rows.select("redisField").distinct().count()
    invalid_rows = rows.filter(F.col("redisField").isNull() | (F.length(F.trim("redisField")) == 0)).limit(1).count()
    if expected_rows == 0 or expected_rows != distinct_rows or invalid_rows:
        rows.unpersist()
        raise ValueError(
            f"Redis mapping requires unique non-empty segmentId rows: rows={expected_rows}, distinct={distinct_rows}"
        )

    keys = mapping_keys(mapping_version, master_version, grid_config_sha, uuid.uuid4().hex)
    connection = {
        "host": host,
        "port": int(port),
        "password": password or None,
        "decode_responses": True,
    }
    driver = None

    def write_partition(partition):
        import redis as redis_module

        client = redis_module.Redis(**connection)
        pipeline = client.pipeline(transaction=False)
        pending = 0
        try:
            for row in partition:
                pipeline.hset(keys["staging"], row.redisField, row.redisValue)
                pending += 1
                if pending >= 1000:
                    pipeline.execute()
                    pending = 0
            if pending:
                pipeline.execute()
        finally:
            client.close()

    try:
        driver = redis.Redis(**connection)
        driver.delete(keys["staging"])
        # executor 기록 중 프로세스가 죽어도 미완성 staging Hash가 영구 잔류하지 않게 한다.
        driver.hset(keys["staging"], "__ddubi_staging__", "1")
        driver.expire(keys["staging"], min(ttl_seconds, 3600))
        rows.foreachPartition(write_partition)
        driver.hdel(keys["staging"], "__ddubi_staging__")
        metadata = {
            "mappingVersion": mapping_version,
            "masterVersion": master_version,
            "gridConfigSha256": grid_config_sha,
            "crosswalkSha256": crosswalk_sha,
            "busMasterSha256": bus_master_sha,
            "rowCount": expected_rows,
            "publishedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        }
        activated_rows = activate_staged_mapping(driver, keys, expected_rows, ttl_seconds, metadata)
        return {
            "activeKey": keys["active"],
            "mappingKey": keys["final"],
            "metadataKey": keys["metadata"],
            "rowCount": activated_rows,
            "ttlSeconds": ttl_seconds,
        }
    finally:
        if driver is not None:
            try:
                driver.delete(keys["staging"])
            finally:
                driver.close()
        rows.unpersist()
