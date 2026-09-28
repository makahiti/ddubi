"""후보 대응표는 엄격한 단일 경로만 별도 Redis 포인터에 발행하는지 검증한다."""

import fakeredis
from common.provisional_road_mapping import build_rows, mapping_keys, publish


def candidate(segment="BUS:1:A:B", ambiguous=0, link_id="L1"):
    road = {
        "linkId": "source-road-1",
        "orientation": "FORWARD",
        "directionEvidence": {
            "permitted": True,
            "nationalStandardLinkId": link_id,
        },
    }
    return {
        "edge": {"segmentId": segment},
        "sampleCount": 1,
        "directionPermittedSamples": 1,
        "ambiguousSamples": ambiguous,
        "candidatePath": {
            "state": "SINGLE_CONNECTED_CANDIDATE_PATH",
            "path": [{"linkId": "source-road-1", "orientation": "FORWARD"}],
        },
        "reviewSamples": [{"permittedCandidates": [road]}],
    }


def test_only_unambiguous_complete_candidate_becomes_provisional():
    rows, rejected = build_rows(
        [candidate(), candidate("BUS:2:C:D", ambiguous=1)],
        "master-v1",
        "links-v1",
        "2026-09-23T00:00:00+09:00",
        "2026-10-01T00:00:00+09:00",
        "a" * 64,
    )
    assert set(rows) == {"BUS:1:A:B"}
    assert rows["BUS:1:A:B"]["mappingStatus"] == "PROVISIONAL"
    assert rows["BUS:1:A:B"]["topisLinkIds"] == ["L1"]
    assert rejected == {"AMBIGUOUS_SAMPLE": 1}


def test_provisional_publish_uses_separate_active_pointer():
    client = fakeredis.FakeRedis(decode_responses=True)
    rows, _ = build_rows(
        [candidate()],
        "master-v1",
        "links-v1",
        "2026-09-23T00:00:00+09:00",
        "2026-10-01T00:00:00+09:00",
        "a" * 64,
    )
    result = publish(
        client,
        rows,
        {
            "masterVersion": "master-v1",
            "crosswalkSha256": "b" * 64,
            "trafficLinkMasterVersion": "links-v1",
            "candidateSourceSha256": "a" * 64,
            "asOf": "2026-09-23T00:00:00+09:00",
        },
        3600,
    )
    keys = mapping_keys("master-v1", "a" * 64)
    assert result["activeKey"] == keys["active"]
    assert client.get(keys["active"]) == keys["final"]
    assert client.get("ml:mapping:bus-road:active:master-v1") is None
