"""버스→도로 Redis 대응표는 완성본만 활성화하는지 검증한다."""

import json

import fakeredis
import pytest
from common.bus_road_redis import activate_staged_mapping, mapping_keys
from redis.exceptions import ResponseError


def test_complete_road_mapping_is_activated():
    client = fakeredis.FakeRedis(decode_responses=True)
    keys = mapping_keys("bus-road-link-v1", "master-v1", "a" * 64, "run1")
    client.hset(keys["staging"], mapping={"S1": "{}", "S2": "{}"})
    result = activate_staged_mapping(client, keys, 2, 3600, {"mappingVersion": "bus-road-link-v1"})
    assert result == 2
    assert client.get(keys["active"]) == keys["final"]
    assert client.hlen(keys["final"]) == 2
    assert json.loads(client.get(keys["metadata"]))["mappingVersion"] == "bus-road-link-v1"
    assert 0 < client.ttl(keys["final"]) <= 3600


def test_incomplete_road_mapping_keeps_old_active_pointer():
    client = fakeredis.FakeRedis(decode_responses=True)
    keys = mapping_keys("bus-road-link-v1", "master-v1", "b" * 64, "run2")
    client.set(keys["active"], "old", ex=3600)
    client.hset(keys["staging"], "S1", "{}")
    with pytest.raises(ResponseError, match="row count mismatch"):
        activate_staged_mapping(client, keys, 2, 3600, {})
    assert client.get(keys["active"]) == "old"
    assert not client.exists(keys["final"])
