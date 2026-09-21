"""검증된 버스 대응표를 구간 단위 DataFrame으로 바꾼다.

Collector와 실시간 Spark가 사용하는 ``load_crosswalk``의 결과만 입력으로 받는다.
따라서 이 모듈은 segmentId를 새로 조합하지 않고, SHA와 ID 규칙 검증을 통과한
대응표의 값을 그대로 사용한다.
"""

from pyspark.sql import functions as F


def build_bus_segment_edges(verified_edges):
    """검증된 원본 edge를 순번·방향을 보존한 표준 컬럼으로 바꾼다."""
    if verified_edges is None:
        raise ValueError("verified bus crosswalk edges required")

    edges, master_version, crosswalk_sha = verified_edges
    return edges.select(
        F.col("namespace").alias("providerNamespace"),
        F.col("routeId").alias("providerRouteId"),
        F.col("fromStopId").alias("providerFromStopId"),
        F.col("toStopId").alias("providerToStopId"),
        F.col("fromOrder").alias("providerFromStopOrder"),
        F.col("toOrder").alias("providerToStopOrder"),
        "direction",
        "segmentId",
        F.lit(master_version).alias("masterVersion"),
        F.lit("canonical-adjacent-v2").alias("idPolicy"),
        F.lit(crosswalk_sha).alias("crosswalkSha256"),
    )


def build_bus_segment_mapping(edges):
    """버스 위치의 제공자 ID를 canonical segmentId에 연결하는 표를 만든다.

    결과는 제공자 노선과 인접 정류장 쌍당 한 행이다. ``bus.arrival`` 대응은
    도착 대상 정류장의 진입 구간이며 현재 차량 위치가 아니므로 이 기본 표에서
    제외한다.
    """
    grouped = edges.groupBy(
        "providerNamespace",
        "providerRouteId",
        "providerFromStopId",
        "providerToStopId",
        "masterVersion",
        "idPolicy",
        "crosswalkSha256",
    ).agg(F.sort_array(F.collect_set("segmentId")).alias("candidateSegmentIds"))
    candidates = F.coalesce(F.col("candidateSegmentIds"), F.array().cast("array<string>"))
    candidate_count = F.size(candidates)

    return grouped.select(
        "providerNamespace",
        "providerRouteId",
        "providerFromStopId",
        "providerToStopId",
        F.when(candidate_count == 1, F.element_at(candidates, 1)).cast("string").alias("segmentId"),
        candidates.alias("candidateSegmentIds"),
        F.when(candidate_count == 1, F.lit("RESOLVED"))
        .when(candidate_count > 1, F.lit("AMBIGUOUS"))
        .otherwise(F.lit("UNRESOLVED"))
        .alias("mappingStatus"),
        "masterVersion",
        "idPolicy",
        "crosswalkSha256",
    )


def build_bus_segment_registry(mapping):
    """Feature 조립기가 사용할 고유 segmentId와 버전 목록을 만든다."""
    return (
        mapping.filter((F.col("mappingStatus") == "RESOLVED") & F.col("segmentId").isNotNull())
        .select("segmentId", "masterVersion", "idPolicy", "crosswalkSha256")
        .distinct()
    )
