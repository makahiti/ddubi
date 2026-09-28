"""공식 노선 링크 후보는 링크가 끝까지 이어진 segment 만 PROVISIONAL 로 발행하는지 검증한다."""

import fakeredis
from common.official_road_mapping import build_rows, selected_link_ids
from common.provisional_road_mapping import mapping_keys, publish

VALIDITY = ("2026-09-23T00:00:00+09:00", "2026-10-23T00:00:00+09:00")


def candidate(segment="BUS:1:A:B", links=("1000015200",), unmapped=(), method="OFFICIAL_ROUTE_LINKS"):
    return {
        "namespace": "SEOUL_BUS",
        "routeId": "1",
        "segmentId": segment,
        "bisLinkIds": ["1000015201"],
        "topisServiceLinkIds": list(links),
        "unmappedBisLinkIds": list(unmapped),
        "state": "REVIEW_REQUIRED",
        "evidenceMethod": method,
    }


def test_complete_official_path_becomes_provisional_row():
    rows, rejected = build_rows(
        [candidate(links=("1000015200", "1000015200", "1000015300"))], "master-v1", "links-v1", *VALIDITY, "a" * 64
    )
    row = rows["BUS:1:A:B"]
    assert row["mappingStatus"] == "PROVISIONAL"
    assert row["topisLinkIds"] == ["1000015200", "1000015300"]  # 연속 중복은 하나로
    assert row["topisLinkNamespace"] == "SEOUL_ACCINFO_LINK"
    assert row["masterVersion"] == "master-v1"
    assert row["roadPathValidFrom"] == VALIDITY[0] and row["roadPathValidUntil"] == VALIDITY[1]
    assert rejected == {}


def test_partial_or_foreign_candidates_are_rejected_with_reason():
    assert selected_link_ids(candidate(unmapped=("1000015205",))) == (None, "BIS_LINKS_UNMAPPED")
    assert selected_link_ids(candidate(links=())) == (None, "TOPIS_LINKS_MISSING")
    assert selected_link_ids(candidate(links=("0",))) == (None, "LINK_ID_UNSAFE")
    assert selected_link_ids(candidate(links=("1000015200 ;",))) == (None, "LINK_ID_UNSAFE")
    assert selected_link_ids(candidate(method="GEOMETRY")) == (None, "EVIDENCE_METHOD_UNSUPPORTED")


def test_segment_with_one_incomplete_occurrence_is_held_back_entirely():
    rows, rejected = build_rows(
        [candidate(), candidate(unmapped=("1000015205",)), candidate("BUS:2:C:D", links=("1000020000",))],
        "master-v1",
        "links-v1",
        *VALIDITY,
        "a" * 64,
    )
    assert set(rows) == {"BUS:2:C:D"}
    assert rejected == {"BIS_LINKS_UNMAPPED": 1}


def test_disagreeing_occurrences_are_rejected():
    rows, rejected = build_rows(
        [candidate(links=("1000015200",)), candidate(links=("1000015300",))],
        "master-v1",
        "links-v1",
        *VALIDITY,
        "a" * 64,
    )
    assert rows == {}
    assert rejected == {"SEGMENT_PATHS_DISAGREE": 1}


def test_publish_uses_the_provisional_pointer_only():
    client = fakeredis.FakeRedis(decode_responses=True)
    rows, _ = build_rows([candidate()], "master-v1", "links-v1", *VALIDITY, "a" * 64)
    result = publish(
        client,
        rows,
        {
            "masterVersion": "master-v1",
            "crosswalkSha256": "b" * 64,
            "trafficLinkMasterVersion": "links-v1",
            "candidateSourceSha256": "a" * 64,
            "asOf": VALIDITY[0],
        },
        3600,
    )
    keys = mapping_keys("master-v1", "a" * 64)
    assert result["rowCount"] == 1
    assert client.get(keys["active"]) == keys["final"]
    assert client.get("ml:mapping:bus-road:active:master-v1") is None
