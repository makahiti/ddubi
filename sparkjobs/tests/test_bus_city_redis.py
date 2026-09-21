"""버스→도시 영역 대응표의 원자적 Redis 승격을 검증한다."""

import fakeredis
import pytest
from common.bus_city_redis import activate_staged_mapping, mapping_keys
from redis.exceptions import ResponseError


def test_complete_city_mapping_is_activated():
    client = fakeredis.FakeRedis(decode_responses=True)
    keys = mapping_keys("bus-city-area-v1", "master-v1", "a" * 64, "run1")
    client.hset(keys["staging"], mapping={"S1": "{}", "S2": "{}"})
    assert activate_staged_mapping(client, keys, 2, 3600, {"rowCount": 2}) == 2
    assert client.get(keys["active"]) == keys["final"]
    assert client.hlen(keys["final"]) == 2


def test_incomplete_city_mapping_does_not_replace_active():
    client = fakeredis.FakeRedis(decode_responses=True)
    keys = mapping_keys("bus-city-area-v1", "master-v1", "b" * 64, "run2")
    client.set(keys["active"], "old", ex=3600)
    client.hset(keys["staging"], "S1", "{}")
    with pytest.raises(ResponseError, match="row count mismatch"):
        activate_staged_mapping(client, keys, 2, 3600, {"rowCount": 2})
    assert client.get(keys["active"]) == "old"
