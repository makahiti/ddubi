"""weather_baseline 집계와 archive_source 변환을 로컬 Spark 로 검증한다 (HDFS 없이 tmp 디렉터리 Parquet)."""

import json
from datetime import date, datetime

import pytest
from batch.weather_baseline import aggregate
from common.archive_source import lookback_window, read_archive_events
from pyspark.sql import SparkSession
from streaming.weather_latest import transform


@pytest.fixture(scope="session")
def spark():
    s = (
        SparkSession.builder.master("local[1]")
        .appName("test-weather-baseline")
        .config("spark.sql.session.timeZone", "Asia/Seoul")
        .config("spark.sql.shuffle.partitions", "1")
        .config("spark.ui.enabled", "false")
        .getOrCreate()
    )
    yield s
    s.stop()


def _archive_row(event_id, occurred, nx, ny, temp, pty, date_str, hour, offset):
    value = json.dumps(
        {
            "eventId": event_id,
            "eventType": "weather.observation",
            "source": "kma.ultra-srt-ncst",
            "occurredAt": occurred,
            "collectedAt": occurred,
            "payload": {
                "district": "종로구",
                "nx": nx,
                "ny": ny,
                "baseDate": date_str.replace("-", ""),
                "baseTime": f"{hour:02d}00",
                "observations": {"T1H": temp, "REH": 60.0, "WSD": 2.0, "RN1": 0.0, "PTY": pty},
            },
        },
        ensure_ascii=False,
    )
    return (
        f"{nx}:{ny}",
        value,
        event_id,
        "weather.observation",
        "kma.ultra-srt-ncst",
        datetime.fromisoformat(occurred),
        datetime.fromisoformat(occurred),
        0,
        offset,
        datetime.fromisoformat(occurred),
        date_str,
        hour,
    )


ARCHIVE_SCHEMA = (
    "kafka_key string, value string, event_id string, event_type string, source string, "
    "occurred_at timestamp, collected_at timestamp, kafka_partition int, kafka_offset long, kafka_timestamp timestamp, "
    "date string, hour int"
)


def _write_archive(spark, tmp_path):
    rows = [
        # 월요일 09시, 격자 60:127 — 두 번 관측 (+ 하나는 중복 eventId)
        _archive_row("a", "2026-08-24T09:00:00+09:00", 60, 127, 24.0, 0.0, "2026-08-24", 9, 1),
        _archive_row("b", "2026-08-31T09:00:00+09:00", 60, 127, 26.0, 1.0, "2026-08-31", 9, 2),
        _archive_row("b", "2026-08-31T09:00:00+09:00", 60, 127, 26.0, 1.0, "2026-08-31", 9, 3),  # 재아카이브 중복
        # 월요일 10시, 다른 격자
        _archive_row("c", "2026-08-24T10:00:00+09:00", 61, 126, 27.0, 0.0, "2026-08-24", 10, 4),
        # 날짜 창 밖 (걸러져야 함)
        _archive_row("d", "2026-07-01T09:00:00+09:00", 60, 127, 99.0, 0.0, "2026-07-01", 9, 5),
        # 다른 이벤트 유형 (걸러져야 함)
        (
            None,
            json.dumps(
                {
                    "eventId": "s",
                    "eventType": "sample.heartbeat",
                    "source": "sample",
                    "occurredAt": "2026-08-24T09:00:00+09:00",
                    "collectedAt": "2026-08-24T09:00:00+09:00",
                    "payload": {},
                }
            ),
            "s",
            "sample.heartbeat",
            "sample",
            datetime(2026, 8, 24, 9),
            datetime(2026, 8, 24, 9),
            0,
            6,
            datetime(2026, 8, 24, 9),
            "2026-08-24",
            9,
        ),
    ]
    df = spark.createDataFrame(rows, ARCHIVE_SCHEMA)
    df.write.partitionBy("date", "hour").parquet(str(tmp_path / "city.raw"))
    return tmp_path.as_uri()


def test_lookback_window_excludes_today():
    assert lookback_window(date(2026, 9, 1), 7) == (date(2026, 8, 25), date(2026, 8, 31))


def test_read_archive_filters_dates_and_dedupes(spark, tmp_path):
    uri = _write_archive(spark, tmp_path)
    events = read_archive_events(spark, uri, "city.raw", date(2026, 8, 1), date(2026, 8, 31))
    ids = sorted(r.eventId for r in events.collect())
    assert ids == ["a", "b", "c", "s"]  # d 는 날짜 밖, b 중복은 하나만


def test_aggregate_weather_baseline(spark, tmp_path):
    uri = _write_archive(spark, tmp_path)
    events = read_archive_events(spark, uri, "city.raw", date(2026, 8, 1), date(2026, 8, 31))
    baseline = aggregate(transform(events), as_of=date(2026, 9, 1), window_days=31)
    rows = {(r.nx, r.ny, r.dow, r.hour): r for r in baseline.collect()}

    assert set(rows) == {(60, 127, 2, 9), (61, 126, 2, 10)}  # dow 2 = 월요일
    mon9 = rows[(60, 127, 2, 9)]
    assert mon9.samples == 2
    assert mon9.tempAvg == 25.0 and mon9.tempMin == 24.0 and mon9.tempMax == 26.0
    assert mon9.precipRatio == 0.5  # 두 번 중 한 번 강수
    assert mon9.asOf == "2026-09-01" and mon9.windowDays == 31
    assert rows[(61, 126, 2, 10)].samples == 1
