"""버스 edge 좌표가 날씨 격자 대응표로 보수적으로 변환되는지 검사한다."""

import json

from common.bus_weather_mapping import build_edge_weather_mapping, build_segment_weather_mapping
from common.bus_weather_redis import redis_mapping_rows
from pyspark.sql import types as T
from test_realtime_latest import (
    spark as spark,  # pytest가 이름으로 찾는 공유 fixture를 명시적으로 재노출한다.
)


def _edges(spark):
    return spark.createDataFrame(
        [
            ("SEOUL_BUS", "1", "A", "B", 1, 2, "UP", "BUS:1:A:B"),
            ("SEOUL_BUS", "1", "B", "C", 2, 3, "UP", "BUS:1:B:C"),
            ("GBIS", "2", "X", "Y", 1, 2, "UP", "BUS:GBIS.2:GBIS.X:GBIS.Y"),
            ("SEOUL_BUS", "3", "M", "N", 1, 2, "UP", "BUS:3:M:N"),
        ],
        "providerNamespace string, providerRouteId string, providerFromStopId string, "
        "providerToStopId string, providerFromStopOrder long, providerToStopOrder long, "
        "direction string, segmentId string",
    ).selectExpr("*", "'TEST' masterVersion", "'canonical-adjacent-v2' idPolicy", "'sha' crosswalkSha256")


def _stops(spark):
    return spark.createDataFrame(
        [
            ("SEOUL_BUS", "1", 1, "A", 126.9780, 37.5665, "TEST"),
            ("SEOUL_BUS", "1", 2, "B", 126.9790, 37.5670, "TEST"),
            ("SEOUL_BUS", "1", 3, "C", 127.0473, 37.5172, "TEST"),
            ("GBIS", "2", 1, "X", 129.0756, 35.1796, "TEST"),
            ("GBIS", "2", 2, "Y", 129.0760, 35.1800, "TEST"),
            ("SEOUL_BUS", "3", 1, "M", None, None, "MISSING"),
            ("SEOUL_BUS", "3", 2, "N", 126.9780, 37.5665, "TEST"),
        ],
        "providerNamespace string, providerRouteId string, providerStopOrder long, "
        "providerStopId string, longitude double, latitude double, coordinateStatus string",
    )


def test_edge_and_segment_weather_mapping_statuses(spark):
    collected = spark.createDataFrame(
        [(60, 127), (60, 126), (61, 126)],
        T.StructType(
            [T.StructField("collectedNx", T.IntegerType(), False), T.StructField("collectedNy", T.IntegerType(), False)]
        ),
    )
    edge_rows = {
        row.segmentId: row for row in build_edge_weather_mapping(_edges(spark), _stops(spark), collected).collect()
    }
    assert edge_rows["BUS:1:A:B"].mappingStatus == "RESOLVED"
    assert edge_rows["BUS:1:A:B"].gridRelation == "SAME_GRID"
    assert (edge_rows["BUS:1:A:B"].representativeNx, edge_rows["BUS:1:A:B"].representativeNy) == (60, 127)
    assert edge_rows["BUS:1:B:C"].gridRelation == "CROSSES_GRID"
    assert edge_rows["BUS:GBIS.2:GBIS.X:GBIS.Y"].mappingStatus == "OUT_OF_COVERAGE"
    assert edge_rows["BUS:3:M:N"].mappingStatus == "UNRESOLVED"

    segment_rows = {
        row.segmentId: row
        for row in build_segment_weather_mapping(
            build_edge_weather_mapping(_edges(spark), _stops(spark), collected)
        ).collect()
    }
    assert segment_rows["BUS:1:A:B"].mappingStatus == "RESOLVED"
    assert segment_rows["BUS:GBIS.2:GBIS.X:GBIS.Y"].mappingStatus == "OUT_OF_COVERAGE"
    assert segment_rows["BUS:3:M:N"].nx is None


def test_same_segment_dataframe_is_serialized_for_redis(spark):
    collected = spark.createDataFrame(
        [(60, 127), (60, 126), (61, 126)],
        T.StructType(
            [T.StructField("collectedNx", T.IntegerType(), False), T.StructField("collectedNy", T.IntegerType(), False)]
        ),
    )
    segment_mapping = build_segment_weather_mapping(build_edge_weather_mapping(_edges(spark), _stops(spark), collected))
    values = {row.redisField: json.loads(row.redisValue) for row in redis_mapping_rows(segment_mapping).collect()}
    assert values["BUS:1:A:B"]["nx"] == 60
    assert values["BUS:1:A:B"]["ny"] == 127
    assert values["BUS:3:M:N"]["nx"] is None
    assert values["BUS:3:M:N"]["mappingStatus"] == "UNRESOLVED"
