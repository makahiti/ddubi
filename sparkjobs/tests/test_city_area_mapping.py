"""Redis 근거 버스→도시 영역 대응표가 보수적으로만 확정하는지 검증한다."""

import fakeredis
from common.city_area_mapping import (
    area_points,
    build_rows,
    load_area_points,
    mapping_keys,
    publish,
    stop_index,
)

# 좌표 1도 차이는 위도 기준 약 111km. 0.001도 ≈ 111m 로 잡고 거리 조건을 만든다.
AREA_LAT, AREA_LNG = 37.5000, 127.0000


def road_doc(area="POI001", name="가영역", lat=AREA_LAT, lng=AREA_LNG, status="VALID", link="L1"):
    return {
        "providerAreaId": area,
        "areaName": name,
        "linkNamespace": "SEOUL_CITYDATA_LINK",
        "linkId": link,
        "processingStatus": status,
        "representativeLatitude": lat,
        "representativeLongitude": lng,
    }


def master(lat=AREA_LAT, lng=AREA_LNG):
    return {
        "version": "master-v1",
        "routes": [
            {
                "routeId": "R1",
                "stops": [
                    {"namespace": "SEOUL_BUS", "stopId": "S1", "sequence": 1, "latitude": lat, "longitude": lng},
                    {"namespace": "SEOUL_BUS", "stopId": "S2", "sequence": 2, "latitude": lat, "longitude": lng},
                ],
            }
        ],
    }


def edge(segment="BUS:R1:S1:S2", from_stop="S1", to_stop="S2", from_order=1, to_order=2):
    return {
        "namespace": "SEOUL_BUS",
        "routeId": "R1",
        "fromStopId": from_stop,
        "toStopId": to_stop,
        "fromOrder": from_order,
        "toOrder": to_order,
        "segmentId": segment,
    }


def rows_for(docs, edges, master_doc=None, **kwargs):
    points = area_points(docs)
    return build_rows(edges, stop_index(master_doc or master()), points, "master-v1", "a" * 64, "b" * 64, **kwargs)


def test_nearby_single_area_resolves():
    rows, counts = rows_for([road_doc()], [edge()])
    row = rows["BUS:R1:S1:S2"]
    assert row["mappingStatus"] == "RESOLVED"
    assert row["primaryProviderAreaId"] == "POI001"
    assert row["masterVersion"] == "master-v1"
    assert row["averageNearestAreaDistanceMeters"] < 1.0
    assert counts == {"RESOLVED": 1}


def test_two_areas_at_similar_distance_stay_ambiguous():
    # 두 영역이 정류장에서 각각 0m·약 22m — 차이가 50m 미만이라 확정하지 않는다.
    docs = [road_doc(), road_doc(area="POI002", name="나영역", lat=AREA_LAT + 0.0002, link="L2")]
    rows, _ = rows_for(docs, [edge()])
    row = rows["BUS:R1:S1:S2"]
    assert row["mappingStatus"] == "AMBIGUOUS"
    assert row["primaryProviderAreaId"] is None
    assert row["candidateProviderAreaIds"] == ["POI001", "POI002"]


def test_far_area_is_out_of_coverage():
    rows, _ = rows_for([road_doc(lat=AREA_LAT + 0.02)], [edge()])  # 약 2.2km
    assert rows["BUS:R1:S1:S2"]["mappingStatus"] == "OUT_OF_COVERAGE"


def test_missing_stop_coordinate_is_unresolved():
    rows, _ = rows_for([road_doc()], [edge(from_stop="S9")])
    assert rows["BUS:R1:S1:S2"]["mappingStatus"] == "UNRESOLVED"


def test_segment_occurrences_must_agree():
    """같은 구간이 두 번 등장하는데 한쪽만 이어지면 확정하지 않는다."""
    edges = [edge(), edge(from_stop="S9")]
    rows, _ = rows_for([road_doc()], edges)
    assert rows["BUS:R1:S1:S2"]["mappingStatus"] == "PARTIAL"


def test_invalid_or_out_of_seoul_points_are_dropped():
    assert area_points([road_doc(status="REJECTED")]) == []
    assert area_points([road_doc(lat=35.1)]) == []
    assert area_points([{"providerAreaId": "", "linkId": "L1"}]) == []


def test_load_reads_redis_and_fingerprints_points():
    import json

    client = fakeredis.FakeRedis(decode_responses=True)
    client.set("city:latest:road:SEOUL_CITYDATA_LINK:L1", json.dumps(road_doc()))
    client.set("city:latest:road:SEOUL_CITYDATA_LINK:L2", "not json")
    points, sha = load_area_points(client)
    assert len(points) == 1
    assert len(sha) == 64
    assert load_area_points(client)[1] == sha  # 같은 근거면 같은 지문 → 같은 Redis 키


def test_publish_sets_the_same_active_pointer_the_backend_reads():
    client = fakeredis.FakeRedis(decode_responses=True)
    rows, _ = rows_for([road_doc()], [edge()])
    result = publish(
        client,
        rows,
        {
            "masterVersion": "master-v1",
            "areaReferenceSha256": "a" * 64,
            "crosswalkSha256": "b" * 64,
            "busMasterSha256": "c" * 64,
        },
        3600,
    )
    keys = mapping_keys("master-v1", "a" * 64)
    assert result["rowCount"] == 1
    assert client.get(keys["active"]) == keys["final"]
    assert client.hget(keys["final"], "BUS:R1:S1:S2")
    assert client.get("ml:mapping:bus-road:active:master-v1") is None  # 도로 표는 건드리지 않는다
