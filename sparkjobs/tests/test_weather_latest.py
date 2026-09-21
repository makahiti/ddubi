"""weather_latest 변환 로직을 배치 DataFrame 으로 검증한다 (Kafka·Redis 없이)."""

import json

import pytest
from common.kafka_source import parse_envelope
from common.redis_sink import COLLECTED_TIME_COL, EVENT_TIME_COL, KEY_COL, TTL_COL, VALUE_COL
from pyspark.sql import SparkSession
from pyspark.sql import functions as F
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


def _event(
    event_id: str, occurred: str, collected: str, nx: int, ny: int, temp: float, event_type="weather.observation"
):
    return {
        "eventId": event_id,
        "eventType": event_type,
        "source": "kma.ultra-srt-ncst",
        "occurredAt": occurred,
        "collectedAt": collected,
        "payload": {
            "districts": ["종로구", "중구"],
            "nx": nx,
            "ny": ny,
            "baseDate": "20260827",
            "baseTime": "0900",
            "observations": {"T1H": temp, "REH": 60.0, "PTY": 0.0},
        },
    }


def _kafka_df(spark, events):
    # Windows 로컬 Spark의 Python worker 소켓 상태와 무관하게 Catalyst 변환을 검증하도록
    # Kafka 모양의 고정 행을 JVM SQL에서 만든다. key/value의 binary 계약은 운영 입력과 같다.
    selects = []
    for index, event in enumerate(events):
        key = f"{event['payload']['nx']}:{event['payload']['ny']}"
        value = json.dumps(event, ensure_ascii=False).replace("'", "''")
        selects.append(
            f"SELECT CAST('{key}' AS BINARY) AS key, CAST('{value}' AS BINARY) AS value, "
            f"0 AS partition, CAST({index} AS BIGINT) AS offset, "
            "TIMESTAMP '2026-08-27 09:10:00' AS timestamp"
        )
    return spark.sql(" UNION ALL ".join(selects))


def test_parse_envelope_reads_contract_fields(spark):
    df = parse_envelope(
        _kafka_df(spark, [_event("e1", "2026-08-27T09:00:00+09:00", "2026-08-27T09:10:00+09:00", 60, 127, 27.3)])
    )
    # pyspark 는 timestamp 를 파이썬 datetime 으로 바꿀 때 프로세스의 시스템 시간대를 쓰므로,
    # 값 비교는 Spark 쪽(세션 tz Asia/Seoul)에서 문자열로 포맷해 한다.
    row = df.withColumn("occurredAtText", F.date_format("occurredAt", "yyyy-MM-dd'T'HH:mm:ssXXX")).collect()[0]
    assert row.eventId == "e1"
    assert row.kafkaKey == "60:127"
    assert row.occurredAtText == "2026-08-27T09:00:00+09:00"
    assert json.loads(row.payload)["nx"] == 60  # payload 는 JSON 원문으로 남는다


def test_parse_envelope_drops_garbage(spark):
    raw = spark.sql(
        "SELECT CAST('k' AS BINARY) AS key, CAST('not json' AS BINARY) AS value, "
        "0 AS partition, CAST(0 AS BIGINT) AS offset, TIMESTAMP '2026-08-27 00:00:00' AS timestamp"
    )
    assert parse_envelope(raw).count() == 0


def test_transform_filters_other_event_types(spark):
    events = parse_envelope(
        _kafka_df(
            spark,
            [
                _event("e1", "2026-08-27T09:00:00+09:00", "2026-08-27T09:10:00+09:00", 60, 127, 27.3),
                _event(
                    "e2",
                    "2026-08-27T09:00:00+09:00",
                    "2026-08-27T09:10:00+09:00",
                    60,
                    127,
                    1.0,
                    event_type="sample.heartbeat",
                ),
            ],
        )
    )
    out = transform(events).collect()
    assert [r.eventId for r in out] == ["e1"]
    assert out[0].observations["T1H"] == 27.3


def test_latest_per_grid_keeps_newest_observation(spark):
    events = parse_envelope(
        _kafka_df(
            spark,
            [
                _event("old", "2026-08-27T08:00:00+09:00", "2026-08-27T08:10:00+09:00", 60, 127, 25.0),
                _event("new", "2026-08-27T09:00:00+09:00", "2026-08-27T09:10:00+09:00", 60, 127, 27.3),
                _event("other-grid", "2026-08-27T08:00:00+09:00", "2026-08-27T08:10:00+09:00", 61, 126, 26.0),
                _event(
                    "same-time-later-collect", "2026-08-27T09:00:00+09:00", "2026-08-27T09:20:00+09:00", 60, 127, 27.5
                ),
            ],
        )
    )
    out = {(r.nx, r.ny): r.eventId for r in latest_per_grid(transform(events)).collect()}
    assert out == {(60, 127): "same-time-later-collect", (61, 126): "other-grid"}


def test_to_redis_rows_matches_contract_key_and_json_value(spark):
    events = parse_envelope(
        _kafka_df(spark, [_event("e1", "2026-08-27T09:00:00+09:00", "2026-08-27T09:10:00+09:00", 60, 127, 27.3)])
    )
    row = to_redis_rows(transform(events)).collect()[0]
    assert row[KEY_COL] == "weather:latest:60:127"
    assert row[TTL_COL] == 7200
    assert row[EVENT_TIME_COL] == "2026-08-27T09:00:00.000+09:00"
    assert row[COLLECTED_TIME_COL] == "2026-08-27T09:10:00.000+09:00"
    value = json.loads(row[VALUE_COL])
    assert value["districts"] == ["종로구", "중구"]
    assert value["observations"]["T1H"] == 27.3
    assert value["featureVersion"] == "weather-feature-v1"
    assert value["features"]["temperatureC"] == 27.3
    assert "windSpeedMps" in value["missingFields"]
    assert value["occurredAt"].startswith("2026-08-27T09:00:00")
    assert value["eventId"] == "e1"


def test_transform_accepts_legacy_single_district_payload(spark):
    # 2026-08-28 이전에 쌓인 옛 형식: districts 배열 대신 district 단수
    legacy = {
        "eventId": "old1",
        "eventType": "weather.observation",
        "source": "kma.ultra-srt-ncst",
        "occurredAt": "2026-08-27T09:00:00+09:00",
        "collectedAt": "2026-08-27T09:10:00+09:00",
        "payload": {
            "district": "종로구",
            "nx": 60,
            "ny": 127,
            "baseDate": "20260827",
            "baseTime": "0900",
            "observations": {"T1H": 25.0},
        },
    }
    value = json.dumps(legacy, ensure_ascii=False).replace("'", "''")
    raw = spark.sql(
        f"SELECT CAST('60:127' AS BINARY) AS key, CAST('{value}' AS BINARY) AS value, "
        "0 AS partition, CAST(0 AS BIGINT) AS offset, "
        "TIMESTAMP '2026-08-27 09:10:00' AS timestamp"
    )
    out = transform(parse_envelope(raw)).collect()[0]
    assert out.districts == ["종로구"]  # 단수 형식이 배열로 승격된다
