import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from common.city_realtime_normalizer import LINK_NAMESPACE, deduplicate, normalize_event


def event(raw, event_id="city-1", content_hash="hash-1"):
    return {
        "eventId": event_id,
        "eventType": "city.realtime",
        "collectedAt": "2026-09-17T16:30:00+09:00",
        "payload": {"providerAreaId": "POI009", "contentHash": content_hash, "raw": raw},
    }


def sample_city():
    return {
        "AREA_CD": "POI009",
        "AREA_NM": "광화문·덕수궁",
        "LIVE_PPLTN_STTS": [
            {
                "AREA_CONGEST_LVL": "약간 붐빔",
                "AREA_CONGEST_MSG": "이동에 약간 시간이 걸립니다.",
                "AREA_PPLTN_MIN": "44000",
                "AREA_PPLTN_MAX": "46000",
                "PPLTN_TIME": "2026-09-17 16:25",
                "FCST_PPLTN": [
                    {
                        "FCST_TIME": "2026-09-17 17:00",
                        "FCST_CONGEST_LVL": "붐빔",
                        "FCST_PPLTN_MIN": "48000",
                        "FCST_PPLTN_MAX": "50000",
                    },
                    {"FCST_TIME": "bad", "FCST_PPLTN_MIN": "1", "FCST_PPLTN_MAX": "2"},
                ],
            }
        ],
        "ROAD_TRAFFIC_STTS": {
            "AVG_ROAD_DATA": {"ROAD_TRAFFIC_SPD": 12, "ROAD_TRAFFIC_TIME": "2026-09-17 16:20"},
            "ROAD_TRAFFIC_STTS": [
                {
                    "LINK_ID": "1000000301",
                    "ROAD_NM": "율곡로",
                    "START_ND_CD": "S1",
                    "END_ND_CD": "E1",
                    "START_ND_XY": "126.9798999832241_37.5701535606807",
                    "END_ND_XY": "126.9793805233784_37.5701494116837",
                    "DIST": "46.0",
                    "SPD": 17,
                    "IDX": "서행",
                    "XYLIST": "126.9793805233784_37.5701494116837|126.9798999832241_37.5701535606807",
                }
            ],
        },
        "WEATHER_STTS": [{"TEMP": "25.0"}],
    }


def test_splits_area_road_forecast_and_keeps_full_linestring():
    result = normalize_event(event(sample_city()))
    assert len(result.area) == len(result.roads) == len(result.forecasts) == 1
    assert len(result.rejected) == 1
    area = result.area[0]
    assert area["populationMin"] == 44000
    assert area["populationMid"] == 45000
    assert area["congestionLevel"] == "BUSY"
    assert area["avgRoadSpeedKph"] == 12.0
    assert "WEATHER_STTS" in json.loads(area["rawPayloadJson"])
    road = result.roads[0]
    assert road["linkNamespace"] == LINK_NAMESPACE
    assert road["coordinateCrs"] == "EPSG:4326"
    assert road["coordinateOrder"] == "LONGITUDE_LATITUDE"
    assert road["geometry"]["type"] == "LineString"
    assert len(road["geometry"]["coordinates"]) == 2
    assert road["startLongitude"] == 126.9798999832241
    assert road["endLongitude"] == 126.9793805233784
    assert road["representativeLongitude"] > 126


def test_accepts_citydata_object_and_list_shapes():
    city = sample_city()
    object_result = normalize_event(event({"CITYDATA": city}))
    list_result = normalize_event(event([city]))
    nested_objects = sample_city()
    nested_objects["LIVE_PPLTN_STTS"] = nested_objects["LIVE_PPLTN_STTS"][0]
    nested_objects["ROAD_TRAFFIC_STTS"] = [nested_objects["ROAD_TRAFFIC_STTS"]]
    nested_result = normalize_event(event(nested_objects))
    assert object_result.area[0]["providerAreaId"] == "POI009"
    assert list_result.area[0]["providerAreaId"] == "POI009"
    assert len(nested_result.roads) == 1 and len(nested_result.forecasts) == 1


def test_expands_multiple_road_links_and_forecast_times():
    city = sample_city()
    second_road = dict(city["ROAD_TRAFFIC_STTS"]["ROAD_TRAFFIC_STTS"][0])
    second_road["LINK_ID"] = "1000000401"
    city["ROAD_TRAFFIC_STTS"]["ROAD_TRAFFIC_STTS"].append(second_road)
    city["LIVE_PPLTN_STTS"][0]["FCST_PPLTN"].append(
        {
            "FCST_TIME": "2026-09-17 18:00",
            "FCST_CONGEST_LVL": "보통",
            "FCST_PPLTN_MIN": "30000",
            "FCST_PPLTN_MAX": "32000",
        }
    )
    result = normalize_event(event(city))
    assert [row["linkId"] for row in result.roads] == ["1000000301", "1000000401"]
    assert [row["forecastAt"] for row in result.forecasts] == ["2026-09-17T17:00:00+09:00", "2026-09-17T18:00:00+09:00"]


def test_missing_values_stay_null_and_min_max_are_not_silently_swapped():
    city = sample_city()
    live = city["LIVE_PPLTN_STTS"][0]
    live["AREA_PPLTN_MIN"], live["AREA_PPLTN_MAX"] = "500", "100"
    city["ROAD_TRAFFIC_STTS"]["AVG_ROAD_DATA"]["ROAD_TRAFFIC_SPD"] = -1
    result = normalize_event(event(city))
    row = result.area[0]
    assert row["populationMin"] == 500 and row["populationMax"] == 100
    assert row["populationMid"] is None
    assert row["avgRoadSpeedKph"] is None
    assert row["processingStatus"] == "PARTIAL"
    assert "POPULATION_MIN_GREATER_THAN_MAX" in row["qualityFlags"]
    assert "AVG_ROAD_SPEED_OUT_OF_RANGE" in row["qualityFlags"]


def test_bad_number_and_unknown_category_are_partial_without_guessing():
    city = sample_city()
    live = city["LIVE_PPLTN_STTS"][0]
    live["AREA_PPLTN_MIN"] = "not-a-number"
    live["AREA_CONGEST_LVL"] = "새로운 단계"
    row = normalize_event(event(city)).area[0]
    assert row["populationMin"] is None
    assert row["congestionLevelRaw"] == "새로운 단계"
    assert row["congestionLevel"] == "UNKNOWN"
    assert row["congestionScore"] is None
    assert "AREA_PPLTN_MIN" in row["missingFields"]
    assert "UNKNOWN_CATEGORY:새로운 단계" in row["qualityFlags"]


def test_missing_area_is_rejected_and_empty_differs_from_absent():
    city = sample_city()
    city.pop("AREA_CD")
    missing = event(city)
    missing["payload"].pop("providerAreaId")
    assert normalize_event(missing).rejected[0]["rejectionReasons"] == ["AREA_CD_MISSING"]

    city = sample_city()
    city["LIVE_PPLTN_STTS"] = []
    row = normalize_event(event(city)).area[0]
    assert "LIVE_PPLTN_EMPTY" in row["qualityFlags"]


def test_bad_or_out_of_seoul_geometry_is_partial_without_batch_failure():
    city = sample_city()
    city["ROAD_TRAFFIC_STTS"]["ROAD_TRAFFIC_STTS"][0]["XYLIST"] = "129.0_35.0|129.1_35.1"
    row = normalize_event(event(city)).roads[0]
    assert row["geometry"] is None
    assert row["coordinateStatus"] == "OUT_OF_SEOUL_BOUNDS"
    assert row["processingStatus"] == "PARTIAL"


def test_same_content_is_duplicate_but_changed_content_is_preserved():
    first = normalize_event(event(sample_city(), "first", "same")).area[0]
    repeat = normalize_event(event(sample_city(), "repeat", "same")).area[0]
    changed = normalize_event(event(sample_city(), "changed", "different")).area[0]
    rows, duplicates = deduplicate([first, repeat, changed], ("providerAreaId", "observedAt"))
    assert duplicates == 1
    assert [row["eventId"] for row in rows] == ["first", "changed"]


def test_no_ai_or_gms_dependency_in_normalizer():
    source = Path(__file__).resolve().parents[1] / "common" / "city_realtime_normalizer.py"
    text = source.read_text(encoding="utf-8").lower()
    assert "gms_api_key" not in text
    assert "openai" not in text
