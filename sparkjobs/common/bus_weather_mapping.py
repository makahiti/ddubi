"""버스 인접 구간을 기상청 날씨 격자에 연결하는 정적 DataFrame 변환."""

import gzip
import hashlib
import io
import json
import math

from pyspark.sql import functions as F
from pyspark.sql import types as T

from common.kma_grid import GRID_VERSION, latlon_to_kma_grid

MAPPING_VERSION = "bus-weather-grid-v1"


STOP_SCHEMA = T.StructType(
    [
        T.StructField("providerNamespace", T.StringType(), False),
        T.StructField("providerRouteId", T.StringType(), False),
        T.StructField("providerStopOrder", T.LongType(), False),
        T.StructField("providerStopId", T.StringType(), False),
        T.StructField("longitude", T.DoubleType(), True),
        T.StructField("latitude", T.DoubleType(), True),
        T.StructField("coordinateStatus", T.StringType(), True),
    ]
)
GRID_SCHEMA = T.StructType(
    [
        T.StructField("nx", T.IntegerType(), True),
        T.StructField("ny", T.IntegerType(), True),
    ]
)


def load_bus_stops(spark, path, expected_sha, expected_version):
    """배포 버스 마스터의 SHA·버전·정류장 순서를 검증해 좌표 DataFrame을 만든다."""
    with open(path, "rb") as stream:
        raw = stream.read()
    if not expected_sha or hashlib.sha256(raw).hexdigest() != expected_sha:
        raise ValueError("bus master SHA mismatch")
    with gzip.GzipFile(fileobj=io.BytesIO(raw)) as stream:
        content = stream.read(80_000_001)
    if len(content) > 80_000_000:
        raise ValueError("bus master too large")
    data = json.loads(content)
    if data.get("schemaVersion") != 1 or data.get("version") != expected_version:
        raise ValueError("bus master version mismatch")

    rows = []
    route_keys = set()
    for route in data.get("routes", []):
        namespace = route.get("namespace")
        route_id = route.get("routeId")
        route_key = (namespace, route_id)
        if not namespace or not route_id or route_key in route_keys:
            raise ValueError("bus master route identity invalid")
        route_keys.add(route_key)
        seen_orders = set()
        for stop in route.get("stops", []):
            order = stop.get("sequence")
            stop_id = stop.get("stopId")
            if not isinstance(order, int) or order < 1 or order in seen_orders or not stop_id:
                raise ValueError("bus master stop identity invalid")
            seen_orders.add(order)
            longitude, latitude = stop.get("longitude"), stop.get("latitude")
            if longitude is not None or latitude is not None:
                if (
                    isinstance(longitude, bool)
                    or isinstance(latitude, bool)
                    or not isinstance(longitude, (int, float))
                    or not isinstance(latitude, (int, float))
                    or not math.isfinite(longitude)
                    or not math.isfinite(latitude)
                    or not -180 <= longitude <= 180
                    or not -90 <= latitude <= 90
                ):
                    longitude = latitude = None
            rows.append(
                (
                    namespace,
                    route_id,
                    order,
                    str(stop_id),
                    float(longitude) if longitude is not None else None,
                    float(latitude) if latitude is not None else None,
                    stop.get("coordinateStatus"),
                )
            )
    return spark.createDataFrame(rows, STOP_SCHEMA)


@F.udf(returnType=GRID_SCHEMA)
def _to_grid(latitude, longitude):
    if latitude is None or longitude is None:
        return None
    try:
        return latlon_to_kma_grid(latitude, longitude)
    except ValueError:
        return None


def build_edge_weather_mapping(edges, stops, collected_grids):
    """순번이 있는 버스 edge마다 출발·도착·중간 날씨 격자와 상태를 계산한다."""
    from_stops = stops.select(
        "providerNamespace",
        "providerRouteId",
        F.col("providerStopOrder").alias("providerFromStopOrder"),
        F.col("providerStopId").alias("providerFromStopId"),
        F.col("longitude").alias("fromLongitude"),
        F.col("latitude").alias("fromLatitude"),
        F.col("coordinateStatus").alias("fromCoordinateStatus"),
    )
    to_stops = stops.select(
        "providerNamespace",
        "providerRouteId",
        F.col("providerStopOrder").alias("providerToStopOrder"),
        F.col("providerStopId").alias("providerToStopId"),
        F.col("longitude").alias("toLongitude"),
        F.col("latitude").alias("toLatitude"),
        F.col("coordinateStatus").alias("toCoordinateStatus"),
    )
    from_keys = ["providerNamespace", "providerRouteId", "providerFromStopOrder", "providerFromStopId"]
    to_keys = ["providerNamespace", "providerRouteId", "providerToStopOrder", "providerToStopId"]
    joined = edges.join(from_stops, from_keys, "left").join(to_stops, to_keys, "left")
    coordinate_ready = (
        F.col("fromLatitude").isNotNull()
        & F.col("fromLongitude").isNotNull()
        & F.col("toLatitude").isNotNull()
        & F.col("toLongitude").isNotNull()
    )
    joined = (
        joined.withColumn(
            "representativeLatitude",
            F.when(coordinate_ready, (F.col("fromLatitude") + F.col("toLatitude")) / 2.0),
        )
        .withColumn(
            "representativeLongitude",
            F.when(coordinate_ready, (F.col("fromLongitude") + F.col("toLongitude")) / 2.0),
        )
        .withColumn("fromGrid", _to_grid("fromLatitude", "fromLongitude"))
        .withColumn("toGrid", _to_grid("toLatitude", "toLongitude"))
        .withColumn("representativeGrid", _to_grid("representativeLatitude", "representativeLongitude"))
        .withColumn(
            "gridRelation",
            F.when(~coordinate_ready, F.lit("UNKNOWN"))
            .when(
                (F.col("fromGrid.nx") == F.col("toGrid.nx")) & (F.col("fromGrid.ny") == F.col("toGrid.ny")),
                F.lit("SAME_GRID"),
            )
            .otherwise(F.lit("CROSSES_GRID")),
        )
        .withColumn("representativeNx", F.col("representativeGrid.nx"))
        .withColumn("representativeNy", F.col("representativeGrid.ny"))
    )
    available = joined.join(
        collected_grids.withColumn("weatherGridCollected", F.lit(True)),
        (F.col("representativeNx") == F.col("collectedNx")) & (F.col("representativeNy") == F.col("collectedNy")),
        "left",
    )
    reason = (
        F.when(F.col("fromLatitude").isNull() | F.col("fromLongitude").isNull(), "FROM_COORDINATE_MISSING")
        .when(F.col("toLatitude").isNull() | F.col("toLongitude").isNull(), "TO_COORDINATE_MISSING")
        .when(F.col("representativeNx").isNull() | F.col("representativeNy").isNull(), "GRID_CONVERSION_FAILED")
        .when(~F.coalesce(F.col("weatherGridCollected"), F.lit(False)), "WEATHER_GRID_NOT_COLLECTED")
        .otherwise("NONE")
    )
    status = (
        F.when(reason.isin("FROM_COORDINATE_MISSING", "TO_COORDINATE_MISSING", "GRID_CONVERSION_FAILED"), "UNRESOLVED")
        .when(reason == "WEATHER_GRID_NOT_COLLECTED", "OUT_OF_COVERAGE")
        .otherwise("RESOLVED")
    )
    return available.select(
        edges["*"],
        "fromLongitude",
        "fromLatitude",
        "fromCoordinateStatus",
        "toLongitude",
        "toLatitude",
        "toCoordinateStatus",
        "representativeLongitude",
        "representativeLatitude",
        F.col("fromGrid.nx").alias("fromNx"),
        F.col("fromGrid.ny").alias("fromNy"),
        F.col("toGrid.nx").alias("toNx"),
        F.col("toGrid.ny").alias("toNy"),
        "representativeNx",
        "representativeNy",
        "gridRelation",
        F.coalesce("weatherGridCollected", F.lit(False)).alias("weatherGridCollected"),
        status.alias("mappingStatus"),
        reason.alias("mappingReason"),
        F.lit(GRID_VERSION).alias("weatherGridVersion"),
    )


def build_segment_weather_mapping(edge_mapping):
    """반복 순번 edge를 고유 segmentId 한 행으로 보수적으로 합친다."""
    grid_key = F.when(
        F.col("representativeNx").isNotNull() & F.col("representativeNy").isNotNull(),
        F.concat_ws(":", "representativeNx", "representativeNy"),
    )
    grouped = edge_mapping.groupBy(
        "segmentId", "masterVersion", "idPolicy", "crosswalkSha256", "weatherGridVersion"
    ).agg(
        F.count(F.lit(1)).alias("edgeOccurrenceCount"),
        F.sort_array(F.collect_set(grid_key)).alias("candidateGridKeys"),
        F.sort_array(F.collect_set("gridRelation")).alias("gridRelations"),
        F.sort_array(F.collect_set("mappingStatus")).alias("edgeMappingStatuses"),
        F.avg("representativeLatitude").alias("representativeLatitude"),
        F.avg("representativeLongitude").alias("representativeLongitude"),
    )
    candidate_count = F.size("candidateGridKeys")
    one_key = F.element_at("candidateGridKeys", 1)
    nx = F.when(candidate_count == 1, F.split(one_key, ":")[0].cast("int"))
    ny = F.when(candidate_count == 1, F.split(one_key, ":")[1].cast("int"))
    has_unresolved = F.array_contains("edgeMappingStatuses", "UNRESOLVED")
    has_outside = F.array_contains("edgeMappingStatuses", "OUT_OF_COVERAGE")
    status = (
        F.when((candidate_count == 0) | has_unresolved, "UNRESOLVED")
        .when(candidate_count > 1, "AMBIGUOUS")
        .when(has_outside, "OUT_OF_COVERAGE")
        .otherwise("RESOLVED")
    )
    reason = (
        F.when((candidate_count == 0) | has_unresolved, "EDGE_COORDINATE_OR_GRID_UNRESOLVED")
        .when(candidate_count > 1, "MULTIPLE_WEATHER_GRIDS_FOR_SEGMENT")
        .when(has_outside, "WEATHER_GRID_NOT_COLLECTED")
        .otherwise("NONE")
    )
    relation = (
        F.when(F.array_contains("gridRelations", "CROSSES_GRID"), "CROSSES_GRID")
        .when(F.array_contains("gridRelations", "SAME_GRID"), "SAME_GRID")
        .otherwise("UNKNOWN")
    )
    return grouped.select(
        "segmentId",
        "masterVersion",
        "idPolicy",
        "crosswalkSha256",
        "weatherGridVersion",
        F.lit(MAPPING_VERSION).alias("mappingVersion"),
        nx.alias("nx"),
        ny.alias("ny"),
        relation.alias("gridRelation"),
        "representativeLatitude",
        "representativeLongitude",
        "edgeOccurrenceCount",
        "candidateGridKeys",
        "gridRelations",
        "edgeMappingStatuses",
        status.alias("mappingStatus"),
        reason.alias("mappingReason"),
    )
