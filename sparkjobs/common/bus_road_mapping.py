"""검증된 버스 occurrence를 승인된 TOPIS 도로 링크 목록에 연결한다."""

from pyspark.sql import functions as F
from pyspark.sql import types as T

from common.road_impact import RoadImpactIndex, time_value
from common.traffic_incident_enrichment import LINK_NAMESPACE, VerifiedLinkMaster

MAPPING_VERSION = "bus-road-link-v1"

PATH_SCHEMA = T.StructType(
    [
        T.StructField("providerNamespace", T.StringType(), False),
        T.StructField("providerRouteId", T.StringType(), False),
        T.StructField("providerFromStopOrder", T.LongType(), False),
        T.StructField("topisLinkIds", T.ArrayType(T.StringType(), False), False),
        T.StructField("directionVerified", T.BooleanType(), False),
        T.StructField("evidenceMethod", T.StringType(), False),
        T.StructField("evidenceRef", T.StringType(), False),
        T.StructField("roadPathValidFrom", T.StringType(), False),
        T.StructField("roadPathValidUntil", T.StringType(), False),
    ]
)


def load_verified_road_paths(
    spark,
    *,
    bus_crosswalk_path,
    bus_crosswalk_sha,
    road_crosswalk_path,
    road_crosswalk_sha,
    traffic_link_master_path,
    traffic_link_master_sha,
    as_of,
):
    """승인된 두 artifact를 검증하고 기준시각에 적용되는 도로 경로만 반환한다."""
    as_of_time = time_value(as_of)
    road = RoadImpactIndex(
        bus_crosswalk_path,
        bus_crosswalk_sha,
        road_crosswalk_path,
        road_crosswalk_sha,
    )
    links = VerifiedLinkMaster.load(traffic_link_master_path, traffic_link_master_sha)
    if not links.available or not links.applicable(as_of):
        raise ValueError("approved traffic link master is unavailable at as_of")

    rows = []
    for key, versions in road.paths.items():
        applicable = [
            row for row in versions if time_value(row["validFrom"]) <= as_of_time < time_value(row["validUntil"])
        ]
        if len(applicable) > 1:
            raise ValueError("overlapping road paths at as_of")
        if not applicable:
            continue
        path = applicable[0]
        link_ids = []
        for item in path["roadLinks"]:
            if item.get("namespace") != LINK_NAMESPACE:
                raise ValueError("road path namespace is not approved AccInfo namespace")
            link_id = item.get("linkId")
            if links.exact(link_id) is None:
                raise ValueError("road path link is absent from approved traffic link master")
            if not link_ids or link_ids[-1] != link_id:
                link_ids.append(link_id)
        if not link_ids:
            raise ValueError("approved road path has no TOPIS links")
        rows.append(
            (
                key[0],
                key[1],
                int(key[2]),
                link_ids,
                True,
                path["evidenceMethod"],
                path["evidenceRef"],
                path["validFrom"],
                path["validUntil"],
            )
        )

    frame = spark.createDataFrame(rows, PATH_SCHEMA)
    metadata = {
        "mappingVersion": MAPPING_VERSION,
        "masterVersion": road.master_version,
        "busCrosswalkSha256": bus_crosswalk_sha,
        "roadCrosswalkVersion": road.version,
        "roadCrosswalkSha256": road_crosswalk_sha,
        "trafficLinkMasterVersion": links.metadata["version"],
        "trafficLinkMasterSha256": traffic_link_master_sha,
        "trafficLinkMasterEvidenceRef": links.metadata["evidenceRef"],
        "asOf": as_of,
    }
    return frame, metadata


def build_edge_road_mapping(edges, verified_paths, metadata):
    """모든 버스 occurrence를 기준시각의 승인 도로 경로와 left join한다."""
    keys = ["providerNamespace", "providerRouteId", "providerFromStopOrder"]
    joined = edges.join(verified_paths, keys, "left")
    resolved = F.col("topisLinkIds").isNotNull() & (F.size("topisLinkIds") > 0)
    return joined.select(
        edges["*"],
        "topisLinkIds",
        F.when(resolved, F.lit(LINK_NAMESPACE)).alias("topisLinkNamespace"),
        F.when(resolved, F.lit("RESOLVED")).otherwise(F.lit("UNMAPPED")).alias("mappingStatus"),
        F.when(resolved, F.lit("NONE")).otherwise(F.lit("ROAD_PATH_UNMAPPED_OR_EXPIRED")).alias("mappingReason"),
        F.coalesce("directionVerified", F.lit(False)).alias("directionVerified"),
        "evidenceMethod",
        "evidenceRef",
        F.to_timestamp("roadPathValidFrom").alias("roadPathValidFrom"),
        F.to_timestamp("roadPathValidUntil").alias("roadPathValidUntil"),
        F.lit(MAPPING_VERSION).alias("mappingVersion"),
        F.lit(metadata["roadCrosswalkVersion"]).alias("roadCrosswalkVersion"),
        F.lit(metadata["roadCrosswalkSha256"]).alias("roadCrosswalkSha256"),
        F.lit(metadata["trafficLinkMasterVersion"]).alias("trafficLinkMasterVersion"),
        F.lit(metadata["trafficLinkMasterSha256"]).alias("trafficLinkMasterSha256"),
        F.lit(metadata["asOf"]).alias("mappingAsOf"),
    )


def build_segment_road_mapping(edge_mapping):
    """segmentId만으로 안전하게 조회할 수 있는 경우에만 링크 배열을 확정한다."""
    path_key = F.when(
        F.col("mappingStatus") == "RESOLVED",
        F.concat_ws(">", "topisLinkIds"),
    )
    grouped = edge_mapping.groupBy(
        "segmentId",
        "masterVersion",
        "idPolicy",
        "crosswalkSha256",
        "mappingVersion",
        "roadCrosswalkVersion",
        "roadCrosswalkSha256",
        "trafficLinkMasterVersion",
        "trafficLinkMasterSha256",
        "mappingAsOf",
    ).agg(
        F.count(F.lit(1)).alias("edgeOccurrenceCount"),
        F.sum(F.when(F.col("mappingStatus") == "RESOLVED", 1).otherwise(0)).alias("mappedOccurrenceCount"),
        F.sort_array(F.collect_set(path_key)).alias("candidateRoadPaths"),
        F.max("roadPathValidFrom").alias("roadPathValidFrom"),
        F.min("roadPathValidUntil").alias("roadPathValidUntil"),
        F.sort_array(F.collect_set("evidenceMethod")).alias("evidenceMethods"),
        F.sort_array(F.collect_set("evidenceRef")).alias("evidenceRefs"),
    )
    candidate_count = F.size("candidateRoadPaths")
    all_mapped = F.col("mappedOccurrenceCount") == F.col("edgeOccurrenceCount")
    resolved = all_mapped & (candidate_count == 1)
    status = (
        F.when(resolved, "RESOLVED")
        .when(F.col("mappedOccurrenceCount") == 0, "UNMAPPED")
        .when(candidate_count > 1, "AMBIGUOUS")
        .otherwise("PARTIAL")
    )
    reason = (
        F.when(resolved, "NONE")
        .when(F.col("mappedOccurrenceCount") == 0, "NO_APPROVED_ROAD_PATH")
        .when(candidate_count > 1, "MULTIPLE_ROAD_PATHS_FOR_SEGMENT")
        .otherwise("SOME_OCCURRENCES_UNMAPPED")
    )
    road_links = F.when(resolved, F.split(F.element_at("candidateRoadPaths", 1), ">")).cast("array<string>")
    return grouped.select(
        "segmentId",
        "masterVersion",
        "idPolicy",
        "crosswalkSha256",
        "mappingVersion",
        "roadCrosswalkVersion",
        "roadCrosswalkSha256",
        "trafficLinkMasterVersion",
        "trafficLinkMasterSha256",
        "mappingAsOf",
        F.when(resolved, F.lit(LINK_NAMESPACE)).alias("topisLinkNamespace"),
        road_links.alias("topisLinkIds"),
        F.when(resolved, F.size(road_links)).cast("int").alias("topisLinkCount"),
        status.alias("mappingStatus"),
        reason.alias("mappingReason"),
        "edgeOccurrenceCount",
        "mappedOccurrenceCount",
        (F.col("mappedOccurrenceCount") / F.col("edgeOccurrenceCount")).alias("occurrenceCoverageRatio"),
        "candidateRoadPaths",
        "roadPathValidFrom",
        "roadPathValidUntil",
        "evidenceMethods",
        "evidenceRefs",
    )
