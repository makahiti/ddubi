"""Weather 공통 정규화와 Silver 중복 정책을 로컬 Spark로 검증한다."""

import json

import pytest
from common.kafka_source import parse_envelope
from common.weather_normalizer import normalize_weather, to_weather_silver_batch, to_weather_silver_stream
from pyspark.sql import SparkSession


@pytest.fixture(scope="session")
def spark():
    session = (
        SparkSession.builder.master("local[1]")
        .appName("test-weather-silver")
        .config("spark.sql.session.timeZone", "Asia/Seoul")
        .config("spark.sql.shuffle.partitions", "1")
        .config("spark.ui.enabled", "false")
        .getOrCreate()
    )
    yield session
    session.stop()


def _events(spark, rows):
    selects = []
    for offset, row in enumerate(rows):
        value = json.dumps(row, ensure_ascii=False).replace("'", "''")
        selects.append(
            f"SELECT CAST('60:127' AS BINARY) key, CAST('{value}' AS BINARY) value, "
            f"0 partition, CAST({offset} AS BIGINT) offset, TIMESTAMP '2026-09-14 08:10:00' timestamp"
        )
    return parse_envelope(spark.sql(" UNION ALL ".join(selects)))


def _weather(event_id, collected, temperature=18.3, humidity=60.0, rain=0.0, wind=2.1):
    return {
        "eventId": event_id,
        "eventType": "weather.observation",
        "source": "kma.ultra-srt-ncst",
        "occurredAt": "2026-09-14T08:00:00+09:00",
        "collectedAt": collected,
        "payload": {
            "districts": ["종로구", "중구"],
            "nx": 60,
            "ny": 127,
            "baseDate": "20260914",
            "baseTime": "0800",
            "observations": {"T1H": temperature, "REH": humidity, "RN1": rain, "PTY": 0, "WSD": wind},
        },
    }


def test_normalizes_provider_codes_and_marks_invalid_values_missing(spark):
    event = _weather("w1", "2026-09-14T08:10:00+09:00", humidity=-998.0, rain=0.0)
    row = normalize_weather(_events(spark, [event])).collect()[0]
    assert row.temperatureC == 18.3
    assert row.precipitationMm == 0.0
    assert row.humidityPercent is None
    assert "humidityPercent" in row.missingFields
    assert "precipitationMm" not in row.missingFields


def test_stream_silver_collapses_same_content_but_keeps_corrected_revision(spark):
    rows = [
        _weather("first", "2026-09-14T08:10:00+09:00", temperature=18.3),
        _weather("repeat", "2026-09-14T08:20:00+09:00", temperature=18.3),
        _weather("corrected", "2026-09-14T08:30:00+09:00", temperature=18.7),
    ]
    silver = to_weather_silver_stream(normalize_weather(_events(spark, rows))).orderBy("collectedAt").collect()
    assert [row.eventId for row in silver] == ["first", "corrected"]
    assert [row.temperatureC for row in silver] == [18.3, 18.7]
    assert all(row.silverVersion == "weather-silver-v1" for row in silver)
    assert silver[0].eventHour == 8


def test_rejects_collection_time_before_observation(spark):
    event = _weather("bad-time", "2026-09-14T07:59:00+09:00")
    assert normalize_weather(_events(spark, [event])).count() == 0


def test_stream_projection_has_hdfs_partition_and_scalar_columns(spark):
    normalized = normalize_weather(_events(spark, [_weather("w1", "2026-09-14T08:10:00+09:00")]))
    row = to_weather_silver_stream(normalized).collect()[0]
    assert row.silverVersion == "weather-silver-v1"
    assert str(row.eventDate) == "2026-09-14"
    assert row.eventHour == 8
    assert row.temperatureC == 18.3
    assert "districts" not in row.asDict()


def test_archive_backfill_keeps_old_data_and_corrected_revisions(spark):
    rows = [
        _weather("repeat", "2026-09-14T08:20:00+09:00", temperature=18.3),
        _weather("first", "2026-09-14T08:10:00+09:00", temperature=18.3),
        _weather("corrected", "2026-09-14T08:30:00+09:00", temperature=18.7),
    ]
    normalized = normalize_weather(_events(spark, rows))
    silver = to_weather_silver_batch(normalized).orderBy("collectedAt").collect()
    assert [row.eventId for row in silver] == ["first", "corrected"]
    assert [row.temperatureC for row in silver] == [18.3, 18.7]
    assert to_weather_silver_batch(normalized).columns == to_weather_silver_stream(normalized).columns
