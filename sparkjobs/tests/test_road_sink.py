"""실제 Lua 실행으로 사건 링크 이동·중복·순서 역전·TTL 축소 회귀를 방어한다."""

import json

import fakeredis
from streaming.road_incidents import UPSERT


def put(client, link, identity="i", at="2026-09-11T09:00:00+09:00", digest="a", ttl=600):
    payload = dict(eventId=identity, contentHash=digest, expiresEpoch=1000 + ttl)
    return client.eval(
        UPSERT,
        2,
        "road:incidents:N:" + link,
        "road:incident-index:" + identity,
        identity,
        json.dumps(payload),
        at,
        ttl,
        1000,
    )


def test_older_record_cannot_move_link_and_newer_record_moves_atomically():
    client = fakeredis.FakeRedis()
    put(client, "a")
    assert put(client, "b", at="2026-09-11T08:59:59+09:00") == 0
    assert client.hexists("road:incidents:N:a", "i")
    put(client, "b", at="2026-09-11T09:00:01+09:00")
    assert not client.hexists("road:incidents:N:a", "i")
    assert client.hexists("road:incidents:N:b", "i")


def test_same_time_conflict_survives_replay_and_duplicate_is_not_conflict():
    client = fakeredis.FakeRedis()
    put(client, "a")
    put(client, "a")
    assert not json.loads(client.hget("road:incidents:N:a", "i")).get("conflicted")
    put(client, "a", digest="b")
    put(client, "a", digest="a")
    assert json.loads(client.hget("road:incidents:N:a", "i"))["conflicted"] is True
    put(client, "a", at="2026-09-11T09:00:01+09:00")
    assert not json.loads(client.hget("road:incidents:N:a", "i")).get("conflicted")


def test_other_short_lived_event_does_not_expire_newer_hash():
    client = fakeredis.FakeRedis()
    put(client, "a", identity="new", ttl=600)
    put(client, "a", identity="old", ttl=10)
    assert client.ttl("road:incidents:N:a") > 590
