"""weather_latest 변환 로직을 배치 DataFrame 으로 검증한다 (Kafka·Redis 없이)."""

import json
from datetime import datetime

import pytest
from pyspark.sql import SparkSession
from pyspark.sql import functions as F

from common.kafka_source import parse_envelope
from common.redis_sink import KEY_COL, VALUE_COL
from streaming.weather_latest import latest_per_grid, to_redis_rows, transform


@pytest.fixture(scope="session")
def spark():
    s = (
        SparkSession.builder.master("local[1]")
        .appName("test-weather-latest")
        .config("spark.sql.session.timeZone", "Asia/Seoul")
        .config("spark.sql.shuffle.partitions", "1")
        .config("spark.ui.enabled", "false")
        .getOrCreate()
    )
    yield s
    s.stop()


def _event(event_id: str, occurred: str, collected: str, nx: int, ny: int, temp: float, event_type="weather.observation"):
    return {
        "eventId": event_id,
        "eventType": event_type,
        "source": "kma.ultra-srt-ncst",
        "occurredAt": occurred,
        "collectedAt": collected,
        "payload": {
            "districts": ["종로구", "중구"], "nx": nx, "ny": ny, "baseDate": "20260827", "baseTime": "0900",
            "observations": {"T1H": temp, "REH": 60.0, "PTY": 0.0},
        },
    }


def _kafka_df(spark, events):
    rows = [
        (f"{e['payload']['nx']}:{e['payload']['ny']}".encode(), json.dumps(e, ensure_ascii=False).encode(), 0, i, datetime(2026, 8, 27, 9, 10))
        for i, e in enumerate(events)
    ]
    return spark.createDataFrame(rows, "key binary, value binary, partition int, offset long, timestamp timestamp")


def test_parse_envelope_reads_contract_fields(spark):
    df = parse_envelope(_kafka_df(spark, [_event("e1", "2026-08-27T09:00:00+09:00", "2026-08-27T09:10:00+09:00", 60, 127, 27.3)]))
    # pyspark 는 timestamp 를 파이썬 datetime 으로 바꿀 때 프로세스의 시스템 시간대를 쓰므로,
    # 값 비교는 Spark 쪽(세션 tz Asia/Seoul)에서 문자열로 포맷해 한다.
    row = df.withColumn("occurredAtText", F.date_format("occurredAt", "yyyy-MM-dd'T'HH:mm:ssXXX")).collect()[0]
    assert row.eventId == "e1"
    assert row.kafkaKey == "60:127"
    assert row.occurredAtText == "2026-08-27T09:00:00+09:00"
    assert json.loads(row.payload)["nx"] == 60  # payload 는 JSON 원문으로 남는다


def test_parse_envelope_drops_garbage(spark):
    raw = spark.createDataFrame([(b"k", b"not json", 0, 0, datetime(2026, 8, 27))], "key binary, value binary, partition int, offset long, timestamp timestamp")
    assert parse_envelope(raw).count() == 0


def test_transform_filters_other_event_types(spark):
    events = parse_envelope(_kafka_df(spark, [
        _event("e1", "2026-08-27T09:00:00+09:00", "2026-08-27T09:10:00+09:00", 60, 127, 27.3),
        _event("e2", "2026-08-27T09:00:00+09:00", "2026-08-27T09:10:00+09:00", 60, 127, 1.0, event_type="sample.heartbeat"),
    ]))
    out = transform(events).collect()
    assert [r.eventId for r in out] == ["e1"]
    assert out[0].observations["T1H"] == 27.3


def test_latest_per_grid_keeps_newest_observation(spark):
    events = parse_envelope(_kafka_df(spark, [
        _event("old", "2026-08-27T08:00:00+09:00", "2026-08-27T08:10:00+09:00", 60, 127, 25.0),
        _event("new", "2026-08-27T09:00:00+09:00", "2026-08-27T09:10:00+09:00", 60, 127, 27.3),
        _event("other-grid", "2026-08-27T08:00:00+09:00", "2026-08-27T08:10:00+09:00", 61, 126, 26.0),
        _event("same-time-later-collect", "2026-08-27T09:00:00+09:00", "2026-08-27T09:20:00+09:00", 60, 127, 27.5),
    ]))
    out = {(r.nx, r.ny): r.eventId for r in latest_per_grid(transform(events)).collect()}
    assert out == {(60, 127): "same-time-later-collect", (61, 126): "other-grid"}


def test_to_redis_rows_matches_contract_key_and_json_value(spark):
    events = parse_envelope(_kafka_df(spark, [_event("e1", "2026-08-27T09:00:00+09:00", "2026-08-27T09:10:00+09:00", 60, 127, 27.3)]))
    row = to_redis_rows(transform(events)).collect()[0]
    assert row[KEY_COL] == "weather:latest:60:127"
    value = json.loads(row[VALUE_COL])
    assert value["districts"] == ["종로구", "중구"]
    assert value["observations"]["T1H"] == 27.3
    assert value["occurredAt"].startswith("2026-08-27T09:00:00")
    assert value["eventId"] == "e1"


def test_transform_accepts_legacy_single_district_payload(spark):
    # 2026-08-28 이전에 쌓인 옛 형식: districts 배열 대신 district 단수
    legacy = {
        "eventId": "old1", "eventType": "weather.observation", "source": "kma.ultra-srt-ncst",
        "occurredAt": "2026-08-27T09:00:00+09:00", "collectedAt": "2026-08-27T09:10:00+09:00",
        "payload": {"district": "종로구", "nx": 60, "ny": 127, "baseDate": "20260827", "baseTime": "0900",
                    "observations": {"T1H": 25.0}},
    }
    raw = spark.createDataFrame(
        [(b"60:127", json.dumps(legacy, ensure_ascii=False).encode(), 0, 0, datetime(2026, 8, 27, 9, 10))],
        "key binary, value binary, partition int, offset long, timestamp timestamp",
    )
    out = transform(parse_envelope(raw)).collect()[0]
    assert out.districts == ["종로구"]  # 단수 형식이 배열로 승격된다
