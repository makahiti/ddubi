"""버스 인접 구간을 서울 실시간 도시데이터 영역에 보수적으로 연결한다."""

from pyspark.sql import Window
from pyspark.sql import functions as F

MAPPING_VERSION = "bus-city-area-v1"


def latest_city_road_points(city_roads):
    """영역별 도로 링크의 최신 유효 대표 좌표만 정적 매핑 근거로 사용한다."""
    required = {
        "providerAreaId",
        "areaName",
        "linkNamespace",
        "linkId",
        "observedAt",
        "collectedAt",
        "processingStatus",
        "representativeLongitude",
        "representativeLatitude",
    }
    missing = sorted(required - set(city_roads.columns))
    if missing:
        raise ValueError(f"city road snapshot is missing columns: {missing}")
    usable = city_roads.filter(
        F.col("providerAreaId").isNotNull()
        & (F.trim("providerAreaId") != "")
        & F.col("linkId").isNotNull()
        & F.col("representativeLongitude").between(126.0, 128.0)
        & F.col("representativeLatitude").between(37.0, 38.0)
        & F.col("processingStatus").isin("VALID", "PARTIAL")
    )
    order = Window.partitionBy("providerAreaId", "linkNamespace", "linkId").orderBy(
        F.to_timestamp("observedAt").desc_nulls_last(),
        F.to_timestamp("collectedAt").desc_nulls_last(),
    )
    return (
        usable.withColumn("_rank", F.row_number().over(order))
        .filter("_rank = 1")
        .select(
            "providerAreaId",
            "areaName",
            "linkNamespace",
            "linkId",
            F.col("representativeLongitude").cast("double").alias("areaRoadLongitude"),
            F.col("representativeLatitude").cast("double").alias("areaRoadLatitude"),
        )
    )


def _haversine(lat1, lon1, lat2, lon2):
    radius = F.lit(6371008.8)
    lat1r, lat2r = F.radians(lat1), F.radians(lat2)
    dlat = lat2r - lat1r
    dlon = F.radians(lon2) - F.radians(lon1)
    value = F.pow(F.sin(dlat / 2.0), 2) + F.cos(lat1r) * F.cos(lat2r) * F.pow(F.sin(dlon / 2.0), 2)
    return radius * 2.0 * F.asin(F.least(F.lit(1.0), F.sqrt(value)))


def build_edge_city_mapping(edges, stops, city_roads, max_distance_meters=300.0, ambiguity_meters=50.0):
    """구간의 출발·중간·도착점에서 가장 가까운 영역 도로까지 거리를 계산한다."""
    if max_distance_meters <= 0 or ambiguity_meters < 0:
        raise ValueError("city mapping distance policy is invalid")
    from_stops = stops.select(
        "providerNamespace",
        "providerRouteId",
        F.col("providerStopOrder").alias("providerFromStopOrder"),
        F.col("providerStopId").alias("providerFromStopId"),
        F.col("longitude").alias("fromLongitude"),
        F.col("latitude").alias("fromLatitude"),
    )
    to_stops = stops.select(
        "providerNamespace",
        "providerRouteId",
        F.col("providerStopOrder").alias("providerToStopOrder"),
        F.col("providerStopId").alias("providerToStopId"),
        F.col("longitude").alias("toLongitude"),
        F.col("latitude").alias("toLatitude"),
    )
    from_keys = ["providerNamespace", "providerRouteId", "providerFromStopOrder", "providerFromStopId"]
    to_keys = ["providerNamespace", "providerRouteId", "providerToStopOrder", "providerToStopId"]
    base = (
        edges.join(from_stops, from_keys, "left")
        .join(to_stops, to_keys, "left")
        .withColumn("representativeLongitude", (F.col("fromLongitude") + F.col("toLongitude")) / 2.0)
        .withColumn("representativeLatitude", (F.col("fromLatitude") + F.col("toLatitude")) / 2.0)
    )
    identity = [
        "providerNamespace",
        "providerRouteId",
        "providerFromStopId",
        "providerToStopId",
        "providerFromStopOrder",
        "providerToStopOrder",
        "direction",
        "segmentId",
        "masterVersion",
        "idPolicy",
        "crosswalkSha256",
        "fromLongitude",
        "fromLatitude",
        "toLongitude",
        "toLatitude",
        "representativeLongitude",
        "representativeLatitude",
    ]
    ready = base.filter(
        F.col("fromLongitude").isNotNull()
        & F.col("fromLatitude").isNotNull()
        & F.col("toLongitude").isNotNull()
        & F.col("toLatitude").isNotNull()
    )
    points = latest_city_road_points(city_roads)
    distances = ready.crossJoin(F.broadcast(points)).withColumn(
        "distanceMeters",
        F.least(
            _haversine("fromLatitude", "fromLongitude", "areaRoadLatitude", "areaRoadLongitude"),
            _haversine("representativeLatitude", "representativeLongitude", "areaRoadLatitude", "areaRoadLongitude"),
            _haversine("toLatitude", "toLongitude", "areaRoadLatitude", "areaRoadLongitude"),
        ),
    )
    areas = (
        distances.groupBy(*identity, "providerAreaId", "areaName")
        .agg(F.min("distanceMeters").alias("distanceMeters"))
        .filter(F.col("distanceMeters") <= float(max_distance_meters))
    )
    candidates = areas.groupBy(*identity).agg(
        F.sort_array(F.collect_list(F.struct("distanceMeters", "providerAreaId", "areaName"))).alias("areaCandidates")
    )
    joined = base.join(candidates, identity, "left")
    empty_candidates = F.array().cast("array<struct<distanceMeters:double,providerAreaId:string,areaName:string>>")
    joined = joined.withColumn("areaCandidates", F.coalesce("areaCandidates", empty_candidates))
    candidate_count = F.size("areaCandidates")
    first = F.element_at("areaCandidates", 1)
    second = F.element_at("areaCandidates", 2)
    coordinate_ready = F.col("representativeLongitude").isNotNull() & F.col("representativeLatitude").isNotNull()
    ambiguous = (candidate_count > 1) & ((second.distanceMeters - first.distanceMeters) < float(ambiguity_meters))
    status = (
        F.when(~coordinate_ready, "UNRESOLVED")
        .when(candidate_count == 0, "OUT_OF_COVERAGE")
        .when(ambiguous, "AMBIGUOUS")
        .otherwise("RESOLVED")
    )
    reason = (
        F.when(~coordinate_ready, "STOP_COORDINATE_MISSING")
        .when(candidate_count == 0, "NO_CITY_AREA_WITHIN_DISTANCE")
        .when(ambiguous, "MULTIPLE_AREAS_AT_SIMILAR_DISTANCE")
        .otherwise("NONE")
    )
    return joined.select(
        edges["*"],
        "fromLongitude",
        "fromLatitude",
        "toLongitude",
        "toLatitude",
        "representativeLongitude",
        "representativeLatitude",
        F.when(status == "RESOLVED", first.providerAreaId).alias("primaryProviderAreaId"),
        F.when(status == "RESOLVED", first.areaName).alias("primaryAreaName"),
        F.when(candidate_count > 0, first.distanceMeters).alias("nearestAreaDistanceMeters"),
        F.transform("areaCandidates", lambda item: item.providerAreaId).alias("candidateProviderAreaIds"),
        F.transform("areaCandidates", lambda item: item.areaName).alias("candidateAreaNames"),
        status.alias("mappingStatus"),
        reason.alias("mappingReason"),
        F.lit(float(max_distance_meters)).alias("maxDistanceMeters"),
        F.lit(float(ambiguity_meters)).alias("ambiguityMeters"),
        F.lit(MAPPING_VERSION).alias("mappingVersion"),
    )


def build_segment_city_mapping(edge_mapping):
    """같은 segmentId의 모든 occurrence가 같은 영역을 가리킬 때만 확정한다."""
    grouped = edge_mapping.groupBy(
        "segmentId",
        "masterVersion",
        "idPolicy",
        "crosswalkSha256",
        "mappingVersion",
        "maxDistanceMeters",
        "ambiguityMeters",
    ).agg(
        F.count(F.lit(1)).alias("edgeOccurrenceCount"),
        F.sort_array(F.collect_set("primaryProviderAreaId")).alias("resolvedProviderAreaIds"),
        F.sort_array(F.collect_set("mappingStatus")).alias("edgeMappingStatuses"),
        F.sort_array(F.array_distinct(F.flatten(F.collect_list("candidateProviderAreaIds")))).alias(
            "candidateProviderAreaIds"
        ),
        F.avg("nearestAreaDistanceMeters").alias("averageNearestAreaDistanceMeters"),
    )
    resolved_count = F.size("resolvedProviderAreaIds")
    has_resolved = F.array_contains("edgeMappingStatuses", "RESOLVED")
    has_unresolved = F.array_contains("edgeMappingStatuses", "UNRESOLVED")
    has_ambiguous = F.array_contains("edgeMappingStatuses", "AMBIGUOUS")
    has_outside = F.array_contains("edgeMappingStatuses", "OUT_OF_COVERAGE")
    status = (
        F.when(has_unresolved & ~has_resolved, "UNRESOLVED")
        .when(has_ambiguous | (resolved_count > 1), "AMBIGUOUS")
        .when(has_resolved & (has_unresolved | has_outside), "PARTIAL")
        .when(resolved_count == 1, "RESOLVED")
        .otherwise("OUT_OF_COVERAGE")
    )
    reason = (
        F.when(status == "UNRESOLVED", "EDGE_COORDINATE_UNRESOLVED")
        .when(status == "AMBIGUOUS", "MULTIPLE_CITY_AREAS_FOR_SEGMENT")
        .when(status == "PARTIAL", "ONLY_SOME_OCCURRENCES_MAPPED")
        .when(status == "OUT_OF_COVERAGE", "NO_CITY_AREA_WITHIN_DISTANCE")
        .otherwise("NONE")
    )
    return grouped.select(
        "segmentId",
        "masterVersion",
        "idPolicy",
        "crosswalkSha256",
        "mappingVersion",
        F.when(status == "RESOLVED", F.element_at("resolvedProviderAreaIds", 1)).alias("primaryProviderAreaId"),
        "resolvedProviderAreaIds",
        "candidateProviderAreaIds",
        "averageNearestAreaDistanceMeters",
        "edgeOccurrenceCount",
        "edgeMappingStatuses",
        "maxDistanceMeters",
        "ambiguityMeters",
        status.alias("mappingStatus"),
        reason.alias("mappingReason"),
    )
