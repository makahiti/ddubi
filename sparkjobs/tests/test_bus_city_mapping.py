"""버스 구간이 실제 도시 영역 도로 좌표와 거리로 연결되는지 검증한다."""

import json

from common.bus_city_mapping import build_edge_city_mapping, build_segment_city_mapping
from common.bus_city_redis import redis_mapping_rows
from test_realtime_latest import (
    spark as spark,  # pytest가 이름으로 찾는 공유 fixture를 명시적으로 재노출한다.
)


def _edges(spark):
    return spark.sql("""
      SELECT 'SEOUL_BUS' providerNamespace, '1' providerRouteId, 'A' providerFromStopId,
             'B' providerToStopId, 1L providerFromStopOrder, 2L providerToStopOrder,
             'UP' direction, 'BUS:1:A:B' segmentId, 'TEST' masterVersion,
             'canonical-adjacent-v2' idPolicy, 'sha' crosswalkSha256
      UNION ALL SELECT 'SEOUL_BUS','1','B','C',2L,3L,'UP','BUS:1:B:C','TEST','canonical-adjacent-v2','sha'
      UNION ALL SELECT 'SEOUL_BUS','1','C','D',3L,4L,'UP','BUS:1:C:D','TEST','canonical-adjacent-v2','sha'
      UNION ALL SELECT 'SEOUL_BUS','2','M','N',1L,2L,'UP','BUS:2:M:N','TEST','canonical-adjacent-v2','sha'
      UNION ALL SELECT 'SEOUL_BUS','3','E','F',1L,2L,'UP','BUS:3:E:F','TEST','canonical-adjacent-v2','sha'
    """)


def _stops(spark):
    return spark.sql("""
      SELECT 'SEOUL_BUS' providerNamespace, '1' providerRouteId, 1L providerStopOrder,
             'A' providerStopId, 126.9700D longitude, 37.5600D latitude, 'TEST' coordinateStatus
      UNION ALL SELECT 'SEOUL_BUS','1',2L,'B',126.9710D,37.5600D,'TEST'
      UNION ALL SELECT 'SEOUL_BUS','1',3L,'C',126.9990D,37.5600D,'TEST'
      UNION ALL SELECT 'SEOUL_BUS','1',4L,'D',127.1000D,37.6500D,'TEST'
      UNION ALL SELECT 'SEOUL_BUS','2',1L,'M',CAST(NULL AS DOUBLE),CAST(NULL AS DOUBLE),'MISSING'
      UNION ALL SELECT 'SEOUL_BUS','2',2L,'N',126.9700D,37.5600D,'TEST'
      UNION ALL SELECT 'SEOUL_BUS','3',1L,'E',127.1000D,37.6500D,'TEST'
      UNION ALL SELECT 'SEOUL_BUS','3',2L,'F',127.1010D,37.6500D,'TEST'
    """)


def _roads(spark):
    return spark.sql("""
      SELECT 'POI001' providerAreaId, '영역1' areaName, 'SEOUL_CITYDATA_LINK' linkNamespace,
             'L1' linkId, '2026-09-18T10:00:00+09:00' observedAt,
             '2026-09-18T10:01:00+09:00' collectedAt, 'VALID' processingStatus,
             126.9700D representativeLongitude, 37.5600D representativeLatitude
      UNION ALL SELECT 'POI002','영역2','SEOUL_CITYDATA_LINK','L2',
             '2026-09-18T10:00:00+09:00','2026-09-18T10:01:00+09:00','VALID',127.0000D,37.5600D
    """)


def test_city_mapping_resolves_ambiguous_outside_and_missing(spark):
    edges = build_edge_city_mapping(_edges(spark), _stops(spark), _roads(spark), 2000, 100)
    rows = {row.segmentId: row for row in edges.collect()}
    assert rows["BUS:1:A:B"].mappingStatus == "RESOLVED"
    assert rows["BUS:1:A:B"].primaryProviderAreaId == "POI001"
    assert rows["BUS:1:B:C"].mappingStatus == "AMBIGUOUS"
    assert rows["BUS:1:C:D"].mappingStatus == "RESOLVED"
    assert rows["BUS:2:M:N"].mappingStatus == "UNRESOLVED"
    assert rows["BUS:3:E:F"].mappingStatus == "OUT_OF_COVERAGE"
    assert rows["BUS:3:E:F"].primaryProviderAreaId is None

    segments = build_segment_city_mapping(edges)
    values = {row.segmentId: row for row in segments.collect()}
    assert values["BUS:1:A:B"].mappingStatus == "RESOLVED"
    assert values["BUS:1:B:C"].primaryProviderAreaId is None


def test_same_city_mapping_dataframe_is_serialized_for_redis(spark):
    mapping = build_segment_city_mapping(
        build_edge_city_mapping(_edges(spark), _stops(spark), _roads(spark), 2000, 100)
    )
    values = {row.redisField: json.loads(row.redisValue) for row in redis_mapping_rows(mapping).collect()}
    assert values["BUS:1:A:B"]["primaryProviderAreaId"] == "POI001"
    assert values["BUS:1:B:C"]["mappingStatus"] == "AMBIGUOUS"
