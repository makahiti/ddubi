"""버스→날씨 대응표의 Redis 버전 승격 계약을 검증한다."""

import json

import fakeredis
import pytest
from common.bus_weather_redis import activate_staged_mapping, mapping_keys
from redis.exceptions import ResponseError


def test_complete_staging_is_activated_with_ttl():
    client = fakeredis.FakeRedis(decode_responses=True)
    keys = mapping_keys("bus-weather-grid-v1", "master-v1", "a" * 64, "run1")
    client.hset(keys["staging"], mapping={"S1": "{}", "S2": "{}"})

    activated = activate_staged_mapping(
        client,
        keys,
        expected_rows=2,
        ttl_seconds=3600,
        metadata={"rowCount": 2},
    )

    assert activated == 2
    assert client.get(keys["active"]) == keys["final"]
    assert client.hlen(keys["final"]) == 2
    assert json.loads(client.get(keys["metadata"]))["rowCount"] == 2
    assert 0 < client.ttl(keys["final"]) <= 3600
    assert 0 < client.ttl(keys["active"]) <= 3600


def test_incomplete_staging_does_not_replace_active_mapping():
    client = fakeredis.FakeRedis(decode_responses=True)
    keys = mapping_keys("bus-weather-grid-v1", "master-v1", "b" * 64, "run2")
    client.set(keys["active"], "old-complete-mapping", ex=3600)
    client.hset(keys["staging"], "S1", "{}")

    with pytest.raises(ResponseError, match="row count mismatch"):
        activate_staged_mapping(
            client,
            keys,
            expected_rows=2,
            ttl_seconds=3600,
            metadata={"rowCount": 2},
        )

    assert client.get(keys["active"]) == "old-complete-mapping"
    assert not client.exists(keys["final"])


def test_mapping_key_rejects_unsafe_version_text():
    with pytest.raises(ValueError, match="unsafe Redis key"):
        mapping_keys("bus weather", "master-v1", "c" * 64)
