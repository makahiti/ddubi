"""정확히 매핑된 버스 위치만 구간별 최신 Redis 키로 내보내는지 확인한다."""

import json

from common.bus_normalizer import ALL_FIELDS
from common.bus_stream_sink import segment_redis_rows
from pyspark.sql import functions as F
from test_realtime_latest import (
    spark as spark,  # pytest가 이름으로 찾는 공유 fixture를 명시적으로 재노출한다.
)


def test_bus_position_is_written_by_segment_and_arrival_is_excluded(spark):
    rows = spark.sql("""
      SELECT 'bus.position' eventType, 'ACCEPTED' status, 1 repeatIndex,
             array('BUS:1:A:B') segmentIds, 'BUS:1:A:B' canonicalSegment,
             '2026-09-18T10:00:00+09:00' observedAt,
             '2026-09-18T10:00:05+09:00' collectedAt,
             'e1' eventId, 'seoul.bus_location' source,
             '1:V1' providerKey, '3' congestionCode, '1' providerStateCode,
             0 kafka_partition, 1L kafka_offset,
             named_struct('state','CONFIRMED','masterVersion','TEST') canonicalMapping,
             named_struct('namespace','SEOUL_BUS','routeId','1','vehicleId','V1','stopId','A') providerIds,
             37.56D latitude, 126.97D longitude
      UNION ALL SELECT 'bus.arrival','ACCEPTED',1,array('BUS:1:A:B'),'BUS:1:A:B',
             '2026-09-18T10:00:00+09:00','2026-09-18T10:00:05+09:00',
             'e2','seoul.bus_arrival','1:A',CAST(NULL AS STRING),CAST(NULL AS STRING),
             0,2L,named_struct('state','CONFIRMED','masterVersion','TEST'),
             named_struct('namespace','SEOUL_BUS','routeId','1','vehicleId','V1','stopId','A'),
             37.56D,126.97D
      UNION ALL SELECT 'bus.position','ACCEPTED',1,array('BUS:1:A:B'),'BUS:1:A:B',
             CAST(NULL AS STRING),'2026-09-18T10:03:00+09:00',
             'e3','seoul.bus_location','1:V1','9','9',0,3L,
             named_struct('state','CONFIRMED','masterVersion','TEST'),
             named_struct('namespace','SEOUL_BUS','routeId','1','vehicleId','V1','stopId','A'),
             37.56D,126.97D
    """)
    types = {"string": "string", "double": "double", "long": "bigint", "array": "array<string>"}
    for name, kind in ALL_FIELDS.items():
        if name not in rows.columns:
            rows = rows.withColumn(name, F.lit(None).cast(types[kind]))

    output = segment_redis_rows(rows).collect()
    assert len(output) == 1
    assert output[0].redisKey == "ml:latest:bus-segment:BUS:1:A:B"
    value = json.loads(output[0].redisValue)
    assert value["segmentId"] == "BUS:1:A:B"
    assert value["observation"]["congestionCode"] == "3"
    assert value["validUntil"].startswith("2026-09-18T10:02:00")
