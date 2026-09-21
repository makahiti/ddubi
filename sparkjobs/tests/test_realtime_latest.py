"""비기상 실시간 이벤트의 Kafka → Spark → Redis 변환을 외부 I/O 없이 검증한다.

운영에서 특히 위험한 경우는 서로 다른 노선의 같은 차량 ID가 충돌하는 경우, 늦게 도착한
과거 이벤트가 최신값을 덮는 경우, 누락 ID를 ``unknown`` 공용 키에 쓰는 경우, provider ID를
canonical 매핑 완료로 오인하는 경우다. 이 테스트는 배치 DataFrame으로 그 경계를 고정한다.
"""

import json

import pytest
from common.kafka_source import parse_envelope
from common.redis_sink import (
    COLLECTED_TIME_COL,
    EVENT_TIME_COL,
    KEY_COL,
    MAX_ITEMS_COL,
    TTL_COL,
    VALUE_COL,
)
from pyspark.sql import SparkSession
from streaming.realtime_latest import latest_per_provider, to_redis_rows, transform
from streaming.subway_snapshots import snapshot_rows


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
    # 합성 BUS fixture도 원천 발급 체계를 명시한다. 미확인 출처를 서울로 기본 해석하지 않는다.
    if event_type.startswith("bus."):
        payload = {"providerNamespace": "SEOUL_BUS", **payload}
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
            f"SELECT CAST('provider-{index}' AS BINARY) AS key, "
            f"CAST('{value}' AS BINARY) AS value, {index % 2} AS partition, "
            f"CAST({index} AS BIGINT) AS offset, "
            "TIMESTAMP '2026-09-04 12:00:10' AS timestamp"
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
                "raw": {"exps1": "120", "exps2": "360", "traTime1": "2", "arrmsg1": "2분 후"},
            },
        ),
        _event(
            "road",
            "traffic.flow",
            "2026-09-04T12:00:00+09:00",
            {"providerLinkId": "L1", "raw": {"SPD": "22.5", "TRV_TIME": "91"}},
        ),
    ]

    rows = {row.eventId: row for row in transform(_kafka_df(spark, events)).collect()}
    assert rows["bus"].providerKey == "R1:S1"
    assert rows["bus"].observation.firstArrivalSeconds == 120
    assert rows["bus"].observation.firstTravelTimeMinutes == 2
    assert rows["bus"].observation.arrivalTimeBasis == "SEOUL_EXPS_SECONDS_V1"
    assert rows["road"].observation.speedKph == 22.5
    assert rows["road"].observation.travelTimeSeconds == 91


def test_subway_timing_preserves_source_time_and_train_type_without_filling_missing(spark):
    """생성시각 없는 수집을 fresh 도착예측으로 오인하거나 급행을 일반 열차와 섞는 회귀를 막는다."""
    events = [
        _event(
            "arrival",
            "subway.arrival",
            "2026-09-04T12:00:00+09:00",
            {
                "providerLineId": "1002",
                "providerStationId": "PA",
                "providerTrainId": "T1",
                "raw": {"barvlDt": "80", "recptnDt": "2026-09-04 12:00:00", "btrainStt": "급행"},
            },
        ),
        _event(
            "position",
            "subway.position",
            "2026-09-04T12:00:00+09:00",
            {
                "providerLineId": "1002",
                "providerTrainId": "T1",
                "raw": {},
            },
        ),
    ]
    payloads = [json.loads(row[VALUE_COL]) for row in to_redis_rows(transform(_kafka_df(spark, events))).collect()]
    rows = {payload["eventId"]: payload for payload in payloads}
    assert rows["arrival"]["observation"]["arrivalTrainType"] == "급행"
    assert rows["arrival"]["observation"]["sourceGeneratedAt"] == "2026-09-04 12:00:00"
    assert rows["arrival"]["observation"]["arrivalSeconds"] == 80
    assert rows["position"]["observation"].get("sourceGeneratedAt") is None


def test_subway_arrivals_are_grouped_into_one_station_snapshot(spark):
    """같은 역의 열차 여러 대를 Backend가 한 GET으로 읽을 수 있어야 한다."""
    events = [
        _event(
            "a2",
            "subway.arrival",
            "2026-09-04T12:00:00+09:00",
            {
                "providerLineId": "1002",
                "providerStationId": "0201",
                "providerTrainId": "T2",
                "raw": {"barvlDt": "180", "arvlMsg2": "3분 후", "recptnDt": "2026-09-04 12:00:00"},
            },
        ),
        _event(
            "a1",
            "subway.arrival",
            "2026-09-04T12:00:00+09:00",
            {
                "providerLineId": "1002",
                "providerStationId": "0201",
                "providerTrainId": "T1",
                "raw": {"barvlDt": "60", "arvlMsg2": "1분 후", "recptnDt": "2026-09-04 12:00:00"},
            },
        ),
        _event(
            "other",
            "subway.arrival",
            "2026-09-04T12:00:00+09:00",
            {
                "providerLineId": "1002",
                "providerStationId": "0202",
                "providerTrainId": "T3",
                "raw": {"barvlDt": "30"},
            },
        ),
    ]
    rows = snapshot_rows(latest_per_provider(transform(_kafka_df(spark, events)))).collect()
    by_key = {row[KEY_COL]: row for row in rows}

    row = by_key["transit:snapshot:subway-arrivals:1002:0201"]
    value = json.loads(row[VALUE_COL])
    assert value["schemaVersion"] == "subway-snapshot-v1"
    assert value["scope"] == {"lineId": "1002", "stationId": "0201"}
    assert value["itemCount"] == 2
    assert [item["trainId"] for item in value["items"]] == ["T1", "T2"]
    assert value["items"][0]["arrivalSeconds"] == 60
    assert value["items"][0]["validUntil"].startswith("2026-09-04T12:02:00")
    assert row[TTL_COL] == 1080
    assert row[MAX_ITEMS_COL] == 128


def test_subway_positions_are_grouped_into_one_line_snapshot(spark):
    """같은 호선의 열차 위치 여러 개를 노선 키 하나로 묶는다."""
    events = [
        _event(
            "p1",
            "subway.position",
            "2026-09-04T12:00:00+09:00",
            {
                "providerLineId": "1002",
                "providerStationId": "0201",
                "providerTrainId": "T1",
                "raw": {"statnNm": "시청", "updnLine": "내선", "trainSttus": "0"},
            },
        ),
        _event(
            "p2",
            "subway.position",
            "2026-09-04T12:00:01+09:00",
            {
                "providerLineId": "1002",
                "providerStationId": "0202",
                "providerTrainId": "T2",
                "raw": {"statnNm": "을지로입구", "updnLine": "외선", "trainSttus": "1"},
            },
        ),
    ]
    rows = snapshot_rows(latest_per_provider(transform(_kafka_df(spark, events)))).collect()
    assert len(rows) == 1
    row = rows[0]
    value = json.loads(row[VALUE_COL])
    assert row[KEY_COL] == "transit:snapshot:subway-positions:1002"
    assert value["snapshotType"] == "SUBWAY_POSITIONS_BY_LINE"
    assert value["scope"] == {"lineId": "1002"}
    assert {item["trainId"] for item in value["items"]} == {"T1", "T2"}
    assert {item["stationId"] for item in value["items"]} == {"0201", "0202"}
    assert row[TTL_COL] == 10800
    assert row[MAX_ITEMS_COL] == 512


def test_bus_waiting_preserves_units_identity_status_and_missing_prediction(spark):
    """분 단위 traTime을 도착예측 초로 재사용하지 않고 운행·순번·제공시각을 함께 전달한다."""
    raw = {
        "exps1": "90",
        "exps2": "110",
        "traTime1": "7",
        "staOrd": "3",
        "mkTm": "2026-09-04 12:00:00.0",
        "deTourAt": "00",
        "isArrive1": "0",
        "isArrive2": "1",
        "full1": "0",
        "full2": "1",
        "vehId1": "101",
        "vehId2": "102",
    }
    events = [
        _event(
            "current",
            "bus.arrival",
            "2026-09-04T12:00:00+09:00",
            {"providerRouteId": "1", "providerStopId": "2", "raw": raw},
        ),
        _event(
            "missing",
            "bus.arrival",
            "2026-09-04T12:00:00+09:00",
            {"providerRouteId": "1", "providerStopId": "3", "raw": {"traTime1": "7"}},
        ),
    ]
    payloads = [json.loads(row[VALUE_COL]) for row in to_redis_rows(transform(_kafka_df(spark, events))).collect()]
    by_id = {p["eventId"]: p["observation"] for p in payloads}
    assert by_id["current"]["firstArrivalSeconds"] == 90
    assert by_id["current"]["firstTravelTimeMinutes"] == 7
    assert by_id["current"]["stopOrder"] == 3
    assert by_id["current"]["sourceGeneratedAt"] == raw["mkTm"]
    assert by_id["current"]["arrivalTimeBasis"] == "SEOUL_EXPS_SECONDS_V1"
    assert by_id["current"]["secondTerminalArrivalCode"] == "1"
    assert by_id["current"]["secondFullCode"] == "1"
    assert by_id["missing"].get("firstArrivalSeconds") is None
    assert by_id["missing"].get("sourceGeneratedAt") is None


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
        (
            "bus.position",
            {"providerRouteId": "R1", "providerVehicleId": "V1", "raw": {}},
            "transit:latest:bus-position:R1:V1",
            86400,
        ),
        (
            "bus.arrival",
            {"providerRouteId": "R1", "providerStopId": "S1", "raw": {}},
            "transit:latest:bus-arrival:R1:S1",
            86400,
        ),
        (
            "subway.position",
            {"providerLineId": "1002", "providerTrainId": "T1", "raw": {}},
            "transit:latest:subway-position:1002:T1",
            10800,
        ),
        (
            "subway.arrival",
            {"providerLineId": "1002", "providerStationId": "0201", "providerTrainId": "T1", "raw": {}},
            "transit:latest:subway-arrival:1002:0201:T1",
            1080,
        ),
        ("traffic.flow", {"providerLinkId": "L1", "raw": {}}, "traffic:latest:flow:L1", 900),
        ("traffic.incident", {"providerIncidentId": "I1", "raw": {}}, "traffic:latest:incident:I1", 2700),
        ("sdot.population", {"providerSensorId": "D1", "raw": {}}, "city:latest:sdot-population:D1", 1800),
        ("subway.alert", {"providerAlertId": "A1", "raw": {}}, "transit:latest:subway-alert:A1", 2700),
        ("topis.notice", {"providerNoticeId": "N1", "raw": {}}, "traffic:latest:topis-notice:N1", 2700),
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
    # 24시간 보관하는 버스도 현재 관측으로 쓸 수 있는 기간은 120초뿐이다.
    if event_type.startswith("bus."):
        assert value["retentionSeconds"] == 86400
        assert value["freshnessSeconds"] == 120
        assert value["validUntil"].startswith("2026-09-04T12:02:00")
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


def test_regional_arrival_units_and_namespace_remain_separate(spark):
    rows = []
    for ns, raw in [
        ("GBIS", {"predictTime1": "2", "predictTimeSec2": "90"}),
        ("INCHEON_API", {"ARRIVALESTIMATETIME": "120"}),
    ]:
        rows.append(
            _event(
                ns,
                "bus.arrival",
                "2026-09-04T12:00:00+09:00",
                dict(providerNamespace=ns, providerRouteId="1", providerStopId="2", providerVehicleId="v", raw=raw),
            )
        )
    result = {r.eventId: r for r in transform(_kafka_df(spark, rows)).collect()}
    assert result["GBIS"].observation.firstArrivalSeconds == 120
    assert result["GBIS"].observation.secondArrivalSeconds == 90
    assert result["GBIS"].observation.arrivalTimeBasis == "GBIS_PREDICT_SECONDS_V1"
    assert result["INCHEON_API"].providerKey == "INCHEON_API:1:2:v"
    assert result["INCHEON_API"].observation.firstArrivalSeconds == 120
    assert result["INCHEON_API"].observation.secondArrivalSeconds is None
    assert result["INCHEON_API"].observation.arrivalTimeBasis == "INCHEON_ESTIMATE_SECONDS_V1"


def test_road_incidents_reject_invalid_expected_date_and_retain_payload(spark):
    from streaming.road_incidents import normalize

    payload = dict(
        providerIncidentId="1",
        providerLinkNamespace="SEOUL_ACCINFO_LINK",
        providerLinkId="2",
        raw=dict(
            occurredDate="20260904", occurredTime="110000", expectedClearDate="20260904", expectedClearTime="130000"
        ),
    )
    good = _event("ok", "traffic.incident", "2026-09-04T12:00:00+09:00", payload)
    bad = _event(
        "bad",
        "traffic.incident",
        "2026-09-04T12:00:00+09:00",
        dict(payload, raw=dict(payload["raw"], expectedClearDate="not-a-date")),
    )
    valid, rejected = normalize(_kafka_df(spark, [good, bad]))
    assert [r.eventId for r in valid.collect()] == ["ok"]
    invalid = rejected.first()
    assert invalid.eventId == "bad" and "not-a-date" in invalid.payload


def test_status_clock_is_kst_minute():
    from datetime import datetime

    from streaming.segment_status_live import minute

    assert minute(datetime.fromisoformat("2026-09-10T14:59:59+00:00").timestamp()) == "2026-09-10T23:59:00+09:00"
