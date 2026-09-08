"""비기상 실시간 이벤트의 Kafka → Spark → Redis 변환을 외부 I/O 없이 검증한다.

운영에서 특히 위험한 경우는 서로 다른 노선의 같은 차량 ID가 충돌하는 경우, 늦게 도착한
과거 이벤트가 최신값을 덮는 경우, 누락 ID를 ``unknown`` 공용 키에 쓰는 경우, provider ID를
canonical 매핑 완료로 오인하는 경우다. 이 테스트는 배치 DataFrame으로 그 경계를 고정한다.
"""

import json

import pytest
from pyspark.sql import SparkSession

from common.kafka_source import parse_envelope
from common.redis_sink import COLLECTED_TIME_COL, EVENT_TIME_COL, KEY_COL, TTL_COL, VALUE_COL
from streaming.realtime_latest import latest_per_provider, to_redis_rows, transform


@pytest.fixture(scope="session")
def spark():
    session = (
        SparkSession.builder.master("local[1]")
        .appName("test-realtime-latest")
        .config("spark.sql.session.timeZone", "Asia/Seoul")
        .config("spark.sql.shuffle.partitions", "1")
        .config("spark.ui.enabled", "false")
        .getOrCreate()
    )
    yield session
    session.stop()


def _event(event_id, event_type, occurred, payload, *, segment_ids=None):
    event = {
        "eventId": event_id,
        "eventType": event_type,
        "source": "test.provider",
        "region": "SEOUL",
        "occurredAt": occurred,
        "collectedAt": "2026-09-04T12:00:10+09:00",
        "payload": payload,
    }
    if segment_ids is not None:
        event["segmentIds"] = segment_ids
    return event


def _kafka_df(spark, events):
    # Windows 로컬 Spark에서 Python worker 소켓이 방화벽에 막히더라도 순수 Catalyst 변환을
    # 검증할 수 있도록 JVM SQL의 고정 행으로 Kafka 입력을 만든다. 실제 운영 입력과 동일하게
    # key/value는 binary이고 value만 UTF-8 JSON이다.
    selects = []
    for index, event in enumerate(events):
        value = json.dumps(event, ensure_ascii=False).replace("'", "''")
        selects.append(
            "SELECT CAST('provider-{0}' AS BINARY) AS key, "
            "CAST('{1}' AS BINARY) AS value, {2} AS partition, "
            "CAST({0} AS BIGINT) AS offset, "
            "TIMESTAMP '2026-09-04 12:00:10' AS timestamp".format(index, value, index % 2)
        )
    raw = spark.sql(" UNION ALL ".join(selects))
    return parse_envelope(raw)


def test_transform_extracts_typed_metrics_without_copying_raw(spark):
    """문자열 숫자를 계약 단위의 숫자로 바꾸고 대형 raw 응답은 출력에서 제외한다."""

    events = [
        _event(
            "bus",
            "bus.arrival",
            "2026-09-04T12:00:00+09:00",
            {
                "providerRouteId": "R1",
                "providerStopId": "S1",
                "raw": {"traTime1": "120", "traTime2": "360", "arrmsg1": "2분 후"},
            },
        ),
        _event(
            "road",
            "traffic.flow",
            "2026-09-04T12:00:00+09:00",
            {"providerLinkId": "L1", "raw": {"SPD": "22.5", "TRV_TIME": "91"}},
        ),
        _event(
            "city",
            "city.realtime",
            "2026-09-04T12:00:00+09:00",
            {
                "providerAreaId": "POI001",
                "raw": {
                    "LIVE_PPLTN_STTS": {
                        "AREA_CONGEST_LVL": "붐빔",
                        "AREA_PPLTN_MIN": "10000",
                        "AREA_PPLTN_MAX": "12000",
                    }
                },
            },
        ),
    ]

    rows = {row.eventId: row for row in transform(_kafka_df(spark, events)).collect()}
    assert rows["bus"].providerKey == "R1:S1"
    assert rows["bus"].observation.firstArrivalSeconds == 120
    assert rows["road"].observation.speedKph == 22.5
    assert rows["road"].observation.travelTimeSeconds == 91
    assert rows["city"].observation.areaCongestionLevel == "붐빔"
    assert rows["city"].observation.areaPopulationMax == 12000
    assert "raw" not in rows["city"].observation.asDict()


def test_latest_per_provider_uses_event_time_before_collection_order(spark):
    """늦게 수집된 과거 관측이 먼저 수집된 현재 관측을 되돌리는 회귀를 막는다."""

    old = _event(
        "old",
        "traffic.flow",
        "2026-09-04T11:55:00+09:00",
        {"providerLinkId": "L1", "raw": {"SPD": "10"}},
    )
    new = _event(
        "new",
        "traffic.flow",
        "2026-09-04T12:00:00+09:00",
        {"providerLinkId": "L1", "raw": {"SPD": "30"}},
    )
    # Kafka에서는 과거 이벤트가 뒤에 도착해도 occurredAt이 더 최신인 관측을 선택해야 한다.
    rows = latest_per_provider(transform(_kafka_df(spark, [new, old]))).collect()
    assert len(rows) == 1
    assert rows[0].eventId == "new"
    assert rows[0].observation.speedKph == 30.0


def test_missing_provider_identity_is_countable_but_not_served(spark):
    """누락 ID는 원문/품질 건수에는 남지만 ``unknown`` Redis 키로 합쳐지지 않는다."""

    event = _event(
        "bad-id",
        "bus.position",
        "2026-09-04T12:00:00+09:00",
        {"providerRouteId": "R1", "providerVehicleId": "unknown", "raw": {}},
    )
    normalized = transform(_kafka_df(spark, [event]))
    assert normalized.count() == 1
    assert normalized.collect()[0].providerKey is None
    assert latest_per_provider(normalized).count() == 0


@pytest.mark.parametrize(
    ("event_type", "payload", "expected_key", "expected_ttl"),
    [
        ("bus.position", {"providerRouteId": "R1", "providerVehicleId": "V1", "raw": {}},
         "transit:latest:bus-position:R1:V1", 120),
        ("bus.arrival", {"providerRouteId": "R1", "providerStopId": "S1", "raw": {}},
         "transit:latest:bus-arrival:R1:S1", 120),
        ("subway.position", {"providerLineId": "1002", "providerTrainId": "T1", "raw": {}},
         "transit:latest:subway-position:1002:T1", 120),
        ("subway.arrival", {"providerLineId": "1002", "providerStationId": "0201", "providerTrainId": "T1", "raw": {}},
         "transit:latest:subway-arrival:1002:0201:T1", 120),
        ("traffic.flow", {"providerLinkId": "L1", "raw": {}},
         "traffic:latest:flow:L1", 900),
        ("traffic.incident", {"providerIncidentId": "I1", "raw": {}},
         "traffic:latest:incident:I1", 600),
        ("city.realtime", {"providerAreaId": "POI001", "raw": {}},
         "city:latest:area:POI001", 900),
        ("sdot.population", {"providerSensorId": "D1", "raw": {}},
         "city:latest:sdot-population:D1", 1800),
        ("subway.alert", {"providerAlertId": "A1", "raw": {}},
         "transit:latest:subway-alert:A1", 600),
    ],
)
def test_redis_key_and_ttl_match_contract(spark, event_type, payload, expected_key, expected_ttl):
    event = _event("e1", event_type, "2026-09-04T12:00:00+09:00", payload)
    row = to_redis_rows(latest_per_provider(transform(_kafka_df(spark, [event])))).collect()[0]
    value = json.loads(row[VALUE_COL])

    assert row[KEY_COL] == expected_key
    assert row[TTL_COL] == expected_ttl
    assert row[EVENT_TIME_COL] == "2026-09-04T12:00:00.000+09:00"
    assert row[COLLECTED_TIME_COL] == "2026-09-04T12:00:10.000+09:00"
    assert value["canonicalMappingAvailable"] is False
    assert value["eventType"] == event_type
    assert value["providerKey"]


def test_verified_segment_ids_are_forwarded_without_claiming_exact(spark):
    event = _event(
        "mapped",
        "traffic.flow",
        "2026-09-04T12:00:00+09:00",
        {"providerLinkId": "L1", "raw": {"SPD": "20"}},
        segment_ids=["BUS:100100609:123456:123789"],
    )
    row = to_redis_rows(latest_per_provider(transform(_kafka_df(spark, [event])))).collect()[0]
    value = json.loads(row[VALUE_COL])
    assert value["canonicalMappingAvailable"] is True
    assert value["segmentIds"] == ["BUS:100100609:123456:123789"]
    assert "EXACT" not in row[VALUE_COL]
