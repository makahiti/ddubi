import json

import pytest
from pyspark.sql import SparkSession
from pyspark.sql.types import TimestampType
from streaming.city_realtime_silver import normalize_batch, output_uri, redis_rows


@pytest.fixture(scope="session")
def spark():
    session = (
        SparkSession.builder.master("local[1]")
        .appName("test-city-realtime-silver")
        .config("spark.sql.session.timeZone", "Asia/Seoul")
        .config("spark.sql.shuffle.partitions", "1")
        .config("spark.ui.enabled", "false")
        .getOrCreate()
    )
    yield session
    session.stop()


def envelope(event_id="city-1", area="POI009", observed="2026-09-17 16:25"):
    return {
        "eventId": event_id,
        "eventType": "city.realtime",
        "source": "seoul.citydata",
        "occurredAt": "2026-09-17T16:30:00+09:00",
        "collectedAt": "2026-09-17T16:30:00+09:00",
        "payload": {
            "providerAreaId": area,
            "contentHash": event_id,
            "raw": {
                "AREA_CD": area,
                "AREA_NM": "광화문·덕수궁",
                "LIVE_PPLTN_STTS": [
                    {
                        "PPLTN_TIME": observed,
                        "AREA_CONGEST_LVL": "보통",
                        "AREA_PPLTN_MIN": "100",
                        "AREA_PPLTN_MAX": "200",
                        "FCST_PPLTN": [
                            {"FCST_TIME": "2026-09-17 17:00", "FCST_PPLTN_MIN": "150", "FCST_PPLTN_MAX": "250"}
                        ],
                    }
                ],
                "ROAD_TRAFFIC_STTS": {
                    "AVG_ROAD_DATA": {"ROAD_TRAFFIC_SPD": "20", "ROAD_TRAFFIC_TIME": observed},
                    "ROAD_TRAFFIC_STTS": [
                        {
                            "LINK_ID": "L1",
                            "SPD": "18",
                            "START_ND_XY": "126.97_37.57",
                            "END_ND_XY": "126.98_37.58",
                            "XYLIST": "126.97_37.57|126.98_37.58",
                        }
                    ],
                },
            },
        },
    }


def batch(spark, events):
    # JVM SQL 고정 행을 써서 Windows 로컬 Python worker 설정과 무관하게 입력 계약을 검증한다.
    selects = []
    for item in events:
        raw = json.dumps(item, ensure_ascii=False).replace("'", "''")
        selects.append(f"SELECT 'city.realtime' eventType, '{raw}' rawEventJson")
    return spark.sql(" UNION ALL ".join(selects))


def test_final_frames_have_timestamp_types_and_all_logical_outputs(spark):
    frames = normalize_batch(batch(spark, [envelope()]), 7)
    assert frames.area.count() == frames.roads.count() == frames.forecasts.count() == 1
    assert isinstance(frames.area.schema["observedAt"].dataType, TimestampType)
    assert isinstance(frames.area.schema["roadTrafficObservedAt"].dataType, TimestampType)
    assert isinstance(frames.roads.schema["collectedAt"].dataType, TimestampType)
    assert isinstance(frames.forecasts.schema["forecastAt"].dataType, TimestampType)
    quality = frames.quality.first()
    assert quality.inputCount == 1 and quality.areaCount == 1 and quality.roadCount == 1


def test_redis_uses_dedicated_keys_and_rejected_rows_are_never_written(spark):
    good = envelope()
    bad = envelope("bad", area=None)
    bad["payload"].pop("providerAreaId")
    bad["payload"]["raw"].pop("AREA_CD")
    frames = normalize_batch(batch(spark, [good, bad]), 8)
    keys = {row.redisKey for row in redis_rows(frames).collect()}
    assert "city:latest:area:POI009" in keys
    assert "city:latest:road:SEOUL_CITYDATA_LINK:L1" in keys
    assert "city:forecast:population:POI009:20260917170000" in keys
    assert frames.rejected.count() == 1
    assert all("None" not in key and "null" not in key for key in keys)


def test_duplicate_and_changed_content_counts_are_visible(spark):
    same_a = envelope("same")
    same_b = envelope("same")
    same_b["eventId"] = "repeat"
    changed = envelope("changed")
    frames = normalize_batch(batch(spark, [same_a, same_b, changed]), 9)
    assert frames.area.count() == 2
    assert frames.quality.first().duplicateCount == 3  # area + road + forecast 각 1건


def test_future_provider_snapshot_is_kept_in_hdfs_shape_but_not_served(spark):
    future = envelope(observed="2026-09-17 18:25")
    frames = normalize_batch(batch(spark, [future]), 10)
    assert "PROVIDER_TIME_IN_FUTURE" in frames.area.first().qualityFlags
    assert redis_rows(frames).count() == 0


def test_empty_or_invalid_hdfs_config_disables_only_hdfs():
    assert output_uri({}) is None
    assert output_uri({"CITY_SILVER_OUTPUT_URI": ""}) is None
    assert output_uri({"CITY_SILVER_OUTPUT_URI": "not-a-uri"}) is None
    assert (
        output_uri({"CITY_SILVER_OUTPUT_URI": "hdfs://nn:9000/ddubi/silver/city/realtime-v1"})
        == "hdfs://nn:9000/ddubi/silver/city/realtime-v1"
    )
