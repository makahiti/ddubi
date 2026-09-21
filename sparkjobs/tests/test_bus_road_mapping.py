"""승인 artifact만 버스 segmentId→TOPIS 링크 대응표가 되는지 검증한다."""

import gzip
import hashlib
import json

import pytest
from common.bus_crosswalk import load_crosswalk_edges
from common.bus_road_mapping import (
    build_edge_road_mapping,
    build_segment_road_mapping,
    load_verified_road_paths,
)
from common.bus_road_redis import redis_mapping_rows
from common.bus_segment_mapping import build_bus_segment_edges
from test_realtime_latest import (
    spark as spark,  # pytest가 이름으로 찾는 공유 fixture를 명시적으로 재노출한다.
)


def _artifacts(tmp_path, omit_link=None):
    edges = [
        dict(
            namespace="SEOUL_BUS",
            routeId="r",
            fromStopId="A",
            toStopId="B",
            fromOrder=1,
            toOrder=2,
            direction="UP",
            segmentId="BUS:r:A:B",
        ),
        dict(
            namespace="SEOUL_BUS",
            routeId="r",
            fromStopId="A",
            toStopId="B",
            fromOrder=3,
            toOrder=4,
            direction="UP",
            segmentId="BUS:r:A:B",
        ),
        dict(
            namespace="SEOUL_BUS",
            routeId="r",
            fromStopId="C",
            toStopId="D",
            fromOrder=5,
            toOrder=6,
            direction="UP",
            segmentId="BUS:r:C:D",
        ),
        dict(
            namespace="SEOUL_BUS",
            routeId="r",
            fromStopId="E",
            toStopId="F",
            fromOrder=7,
            toOrder=8,
            direction="UP",
            segmentId="BUS:r:E:F",
        ),
    ]
    bus = dict(
        schemaVersion=1,
        idPolicy="canonical-adjacent-v2",
        masterVersion="TEST",
        masterSha256="a" * 64,
        edgeCount=len(edges),
        edges=edges,
    )
    bus_raw = gzip.compress(json.dumps(bus).encode(), mtime=0)
    bus_path = tmp_path / "bus.gz"
    bus_path.write_bytes(bus_raw)
    bus_sha = hashlib.sha256(bus_raw).hexdigest()

    def reviewed(edge, links):
        return dict(
            edge,
            validFrom="2026-01-01T00:00:00+09:00",
            validUntil="2027-01-01T00:00:00+09:00",
            evidenceMethod="OFFICIAL_ROUTE_LINKS",
            evidenceRef="fixture",
            coverage="COMPLETE",
            directionVerified=True,
            roadLinks=[{"namespace": "SEOUL_ACCINFO_LINK", "linkId": value} for value in links],
        )

    road = dict(
        schemaVersion=1,
        version="road-v1",
        masterVersion="TEST",
        busCrosswalkSha256=bus_sha,
        mappings=[reviewed(edges[0], ["L1"]), reviewed(edges[1], ["L2"]), reviewed(edges[2], ["L1", "L2"])],
    )
    road_raw = json.dumps(road, sort_keys=True).encode()
    road_path = tmp_path / "road.json"
    road_path.write_bytes(road_raw)
    road_sha = hashlib.sha256(road_raw).hexdigest()

    link_rows = [
        {
            "linkId": link_id,
            "roadName": "테스트로",
            "districtName": "중구",
            "startNodeId": "N1",
            "endNodeId": "N2",
            "geometry": [[126.97, 37.56], [126.98, 37.57]],
        }
        for link_id in ("L1", "L2")
        if link_id != omit_link
    ]
    link_master = {
        "schemaVersion": 1,
        "namespace": "SEOUL_ACCINFO_LINK",
        "idSystem": "SEOUL_TOPIS_ACCINFO_LINK_ID",
        "sourceCrs": "EPSG:4326",
        "sourceDataset": "OA-13310",
        "accInfoLinkIdVerified": True,
        "reviewStatus": "APPROVED",
        "version": "link-v1",
        "evidenceRef": "fixture",
        "validFrom": "2026-01-01T00:00:00+09:00",
        "validUntil": "2027-01-01T00:00:00+09:00",
        "links": link_rows,
    }
    link_raw = json.dumps(link_master, sort_keys=True).encode()
    link_path = tmp_path / "links.json"
    link_path.write_bytes(link_raw)
    return bus_path, bus_sha, road_path, road_sha, link_path, hashlib.sha256(link_raw).hexdigest()


def _mapping(spark, artifacts):
    bus_path, bus_sha, road_path, road_sha, link_path, link_sha = artifacts
    verified = load_crosswalk_edges(spark, bus_path, bus_sha)
    paths, metadata = load_verified_road_paths(
        spark,
        bus_crosswalk_path=bus_path,
        bus_crosswalk_sha=bus_sha,
        road_crosswalk_path=road_path,
        road_crosswalk_sha=road_sha,
        traffic_link_master_path=link_path,
        traffic_link_master_sha=link_sha,
        as_of="2026-09-18T12:00:00+09:00",
    )
    edge_mapping = build_edge_road_mapping(build_bus_segment_edges(verified), paths, metadata)
    return edge_mapping, build_segment_road_mapping(edge_mapping)


def test_segment_mapping_resolves_only_one_safe_road_path(spark, tmp_path):
    edge_mapping, segment_mapping = _mapping(spark, _artifacts(tmp_path))
    rows = {row.segmentId: row for row in segment_mapping.collect()}

    assert rows["BUS:r:C:D"].mappingStatus == "RESOLVED"
    assert rows["BUS:r:C:D"].topisLinkIds == ["L1", "L2"]
    assert rows["BUS:r:A:B"].mappingStatus == "AMBIGUOUS"
    assert rows["BUS:r:A:B"].topisLinkIds is None
    assert rows["BUS:r:E:F"].mappingStatus == "UNMAPPED"
    assert edge_mapping.filter("mappingStatus = 'RESOLVED'").count() == 3

    redis_values = {row.redisField: json.loads(row.redisValue) for row in redis_mapping_rows(segment_mapping).collect()}
    assert redis_values["BUS:r:C:D"]["topisLinkIds"] == ["L1", "L2"]
    assert redis_values["BUS:r:A:B"]["topisLinkIds"] is None


def test_path_with_link_absent_from_approved_master_is_rejected(spark, tmp_path):
    artifacts = _artifacts(tmp_path, omit_link="L2")
    with pytest.raises(ValueError, match="absent from approved traffic link master"):
        _mapping(spark, artifacts)
