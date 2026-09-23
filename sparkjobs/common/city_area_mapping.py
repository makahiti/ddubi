"""버스 구간→도시 영역 대응표를 Redis 최신값만으로 만든다(Spark 없이).

`common.bus_city_mapping` 과 같은 판정 규칙을 쓰되 입력이 다르다. 원래 배치는 City Silver
`road-link-snapshot` Parquet 을 HDFS 에서 읽는데, 운영에서는 `CITY_SILVER_OUTPUT_URI` 가 비어 있어
그 Parquet 이 만들어지지 않는다(2026-09-23 확인). 같은 내용이 Redis `city:latest:road:*` 에
링크별 최신값으로 들어오므로 그것을 근거로 쓴다. 정류장 좌표는 버스 마스터에서 읽는다.

출력 행·Redis 키·활성 포인터는 기존 배치와 같아서 백엔드가 그대로 읽는다. 판정은 보수적이다 —
반경 밖이면 잇지 않고, 가까운 영역이 둘이면 모호로 남기며, 한 구간의 모든 occurrence 가 같은
영역을 가리킬 때만 확정한다.
"""

import datetime as dt
import gzip
import json
import math
import re
import uuid
from pathlib import Path

# common.bus_city_mapping.MAPPING_VERSION 과 같아야 한다. 그 모듈은 pyspark 를 임포트해서 여기서 쓰지 못한다.
MAPPING_VERSION = "bus-city-area-v1"
DEFAULT_TTL_SECONDS = 2_592_000
DEFAULT_MAX_DISTANCE_METERS = 300.0
DEFAULT_AMBIGUITY_METERS = 50.0
AREA_KEY_PREFIX = "city:latest:road:"
_SAFE_KEY_PART = re.compile(r"^[A-Za-z0-9._-]+$")
_EARTH_RADIUS_M = 6371008.8
# 격자 한 칸은 약 500m. 후보는 이웃 9칸에서만 찾는다 — 최대 반경 300m 가 한 칸을 넘지 않으므로 빠짐이 없다.
_CELL_DEGREES = 0.0045

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


def mapping_keys(master_version, area_reference_sha, run_id=None):
    base = "ml:mapping:bus-city:{}:{}:{}".format(
        _key_part("mapping_version", MAPPING_VERSION),
        _key_part("master_version", master_version),
        _key_part("area_reference_sha", area_reference_sha),
    )
    keys = {
        "final": base,
        "active": "ml:mapping:bus-city:active:" + _key_part("master_version", master_version),
        "metadata": base + ":meta",
    }
    if run_id is not None:
        keys["staging"] = base + ":staging:" + _key_part("run_id", run_id)
    return keys


def haversine_meters(lat1, lon1, lat2, lon2):
    lat1r, lat2r = math.radians(lat1), math.radians(lat2)
    dlat = lat2r - lat1r
    dlon = math.radians(lon2) - math.radians(lon1)
    value = math.sin(dlat / 2.0) ** 2 + math.cos(lat1r) * math.cos(lat2r) * math.sin(dlon / 2.0) ** 2
    return _EARTH_RADIUS_M * 2.0 * math.asin(min(1.0, math.sqrt(value)))


def area_points(documents):
    """도시 도로 링크 문서에서 매핑 근거로 쓸 대표 좌표만 남긴다.

    Redis 는 링크별 최신값 하나만 들고 있으므로 Spark 판의 "최신 1건 선택"은 필요 없다.
    """
    points = []
    for doc in documents:
        if not isinstance(doc, dict):
            continue
        area_id = str(doc.get("providerAreaId") or "").strip()
        link_id = doc.get("linkId")
        if not area_id or not link_id:
            continue
        if doc.get("processingStatus") not in ("VALID", "PARTIAL"):
            continue
        lat, lng = doc.get("representativeLatitude"), doc.get("representativeLongitude")
        if not isinstance(lat, (int, float)) or not isinstance(lng, (int, float)):
            continue
        # 서울 바깥 좌표는 원본 오류로 본다. Spark 판과 같은 경계값이다.
        if not (37.0 <= lat <= 38.0) or not (126.0 <= lng <= 128.0):
            continue
        points.append((area_id, str(doc.get("areaName") or ""), float(lat), float(lng)))
    return points


def load_area_points(client, scan_count=1000):
    """Redis `city:latest:road:*` 를 훑어 대표 좌표 목록과 재현용 지문을 만든다."""
    documents = []
    for key in client.scan_iter(match=AREA_KEY_PREFIX + "*", count=scan_count):
        raw = client.get(key)
        if not raw:
            continue
        try:
            documents.append(json.loads(raw))
        except ValueError:
            continue
    points = area_points(documents)
    return points, reference_sha256(points)


def reference_sha256(points):
    """같은 근거로 다시 돌리면 같은 Redis 키가 되도록 좌표 집합의 지문을 만든다."""
    import hashlib

    digest = hashlib.sha256()
    for area_id, _name, lat, lng in sorted(points):
        digest.update(f"{area_id}|{lat:.7f}|{lng:.7f}\n".encode())
    return digest.hexdigest()


class _Grid:
    """반경 안 후보만 빠르게 찾기 위한 격자 색인. 전수 비교(1억 회 이상)를 피한다."""

    def __init__(self, points):
        self.cells = {}
        for point in points:
            cell = (int(point[2] / _CELL_DEGREES), int(point[3] / _CELL_DEGREES))
            self.cells.setdefault(cell, []).append(point)

    def near(self, lat, lng):
        row, col = int(lat / _CELL_DEGREES), int(lng / _CELL_DEGREES)
        for drow in (-1, 0, 1):
            for dcol in (-1, 0, 1):
                yield from self.cells.get((row + drow, col + dcol), ())


def stop_index(master):
    """(namespace, routeId, stopId, sequence) → 좌표."""
    index = {}
    for route in master.get("routes") or []:
        route_id = route.get("routeId")
        for stop in route.get("stops") or []:
            lat, lng = stop.get("latitude"), stop.get("longitude")
            if not isinstance(lat, (int, float)) or not isinstance(lng, (int, float)):
                continue
            index[(stop.get("namespace"), route_id, stop.get("stopId"), stop.get("sequence"))] = (
                float(lat),
                float(lng),
            )
    return index


def edge_candidates(edge, stops, grid, max_distance_meters, ambiguity_meters):
    """구간 하나의 영역 후보와 판정. Spark 판 build_edge_city_mapping 과 같은 규칙이다."""
    namespace, route_id = edge.get("namespace"), edge.get("routeId")
    start = stops.get((namespace, route_id, edge.get("fromStopId"), edge.get("fromOrder")))
    end = stops.get((namespace, route_id, edge.get("toStopId"), edge.get("toOrder")))
    if start is None or end is None:
        return None, "UNRESOLVED", "STOP_COORDINATE_MISSING"
    middle = ((start[0] + end[0]) / 2.0, (start[1] + end[1]) / 2.0)

    nearest = {}
    for lat, lng in (start, middle, end):
        for area_id, area_name, point_lat, point_lng in grid.near(lat, lng):
            distance = haversine_meters(lat, lng, point_lat, point_lng)
            if distance > max_distance_meters:
                continue
            previous = nearest.get(area_id)
            if previous is None or distance < previous[0]:
                nearest[area_id] = (distance, area_name)
    if not nearest:
        return [], "OUT_OF_COVERAGE", "NO_CITY_AREA_WITHIN_DISTANCE"

    ordered = sorted((distance, area_id, name) for area_id, (distance, name) in nearest.items())
    if len(ordered) > 1 and (ordered[1][0] - ordered[0][0]) < ambiguity_meters:
        return ordered, "AMBIGUOUS", "MULTIPLE_AREAS_AT_SIMILAR_DISTANCE"
    return ordered, "RESOLVED", "NONE"


def build_rows(
    edges,
    stops,
    points,
    master_version,
    area_reference_sha,
    crosswalk_sha,
    max_distance_meters=DEFAULT_MAX_DISTANCE_METERS,
    ambiguity_meters=DEFAULT_AMBIGUITY_METERS,
):
    """구간별 대응 행과 상태 집계. 한 구간의 occurrence 가 서로 다른 영역을 가리키면 확정하지 않는다."""
    if max_distance_meters <= 0 or ambiguity_meters < 0:
        raise ValueError("city mapping distance policy is invalid")
    grid = _Grid(points)
    grouped = {}
    for edge in edges:
        segment_id = edge.get("segmentId")
        if not isinstance(segment_id, str) or not segment_id:
            continue
        ordered, status, reason = edge_candidates(edge, stops, grid, max_distance_meters, ambiguity_meters)
        grouped.setdefault(segment_id, []).append((ordered, status, reason))

    rows, counts = {}, {}
    for segment_id, occurrences in grouped.items():
        statuses = {status for _o, status, _r in occurrences}
        resolved = {o[0][1] for o, status, _r in occurrences if status == "RESOLVED"}
        candidates = sorted({item[1] for o, _s, _r in occurrences if o for item in o})
        distances = [o[0][0] for o, _s, _r in occurrences if o]
        if "UNRESOLVED" in statuses and "RESOLVED" not in statuses:
            status, reason = "UNRESOLVED", "EDGE_COORDINATE_UNRESOLVED"
        elif "AMBIGUOUS" in statuses or len(resolved) > 1:
            status, reason = "AMBIGUOUS", "MULTIPLE_CITY_AREAS_FOR_SEGMENT"
        elif resolved and ({"UNRESOLVED", "OUT_OF_COVERAGE"} & statuses):
            status, reason = "PARTIAL", "ONLY_SOME_OCCURRENCES_MAPPED"
        elif len(resolved) == 1:
            status, reason = "RESOLVED", "NONE"
        else:
            status, reason = "OUT_OF_COVERAGE", "NO_CITY_AREA_WITHIN_DISTANCE"
        counts[status] = counts.get(status, 0) + 1
        rows[segment_id] = {
            "segmentId": segment_id,
            "masterVersion": master_version,
            "mappingVersion": MAPPING_VERSION,
            "primaryProviderAreaId": next(iter(resolved)) if status == "RESOLVED" else None,
            "candidateProviderAreaIds": candidates,
            "averageNearestAreaDistanceMeters": (sum(distances) / len(distances)) if distances else None,
            "mappingStatus": status,
            "mappingReason": reason,
            "maxDistanceMeters": float(max_distance_meters),
            "ambiguityMeters": float(ambiguity_meters),
            "crosswalkSha256": crosswalk_sha,
            "areaReferenceSha256": area_reference_sha,
        }
    return rows, counts


def load_master(path):
    opener = gzip.open if str(path).endswith(".gz") else open
    with opener(path, "rt", encoding="utf-8") as source:
        return json.load(source)


def file_sha256(path):
    import hashlib

    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def publish(client, rows, metadata, ttl_seconds=DEFAULT_TTL_SECONDS):
    """staging 에 모두 쓴 뒤 행 수가 맞을 때만 활성 포인터를 원자적으로 바꾼다."""
    if not rows:
        raise ValueError("no city area mappings to publish")
    ttl_seconds = int(ttl_seconds)
    keys = mapping_keys(metadata["masterVersion"], metadata["areaReferenceSha256"], uuid.uuid4().hex)
    client.delete(keys["staging"])
    client.hset(keys["staging"], "__ddubi_staging__", "1")
    client.expire(keys["staging"], min(ttl_seconds, 3600))
    pipe = client.pipeline(transaction=False)
    for index, (segment, row) in enumerate(rows.items(), start=1):
        pipe.hset(keys["staging"], segment, json.dumps(row, ensure_ascii=False, separators=(",", ":")))
        if index % 1000 == 0:
            pipe.execute()
    pipe.execute()
    client.hdel(keys["staging"], "__ddubi_staging__")
    complete = {
        **metadata,
        "mappingVersion": MAPPING_VERSION,
        "rowCount": len(rows),
        "publishedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
    }
    activated = int(
        client.eval(
            _ACTIVATE_LUA,
            4,
            keys["staging"],
            keys["final"],
            keys["active"],
            keys["metadata"],
            len(rows),
            ttl_seconds,
            json.dumps(complete, ensure_ascii=False, separators=(",", ":")),
        )
    )
    return {
        "activeKey": keys["active"],
        "mappingKey": keys["final"],
        "metadataKey": keys["metadata"],
        "rowCount": activated,
        "ttlSeconds": ttl_seconds,
    }
