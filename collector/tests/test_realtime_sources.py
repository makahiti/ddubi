"""실시간 외부 API 응답의 정상·단건·오류·설정누락 경계를 검증한다.

실제 API와 Kafka 없이 MockTransport로 실행하여 CI 결과를 고정한다. 특히 provider ID를
canonical ``segment_id``로 잘못 채우지 않는지와 인증키가 예외에 노출되지 않는지를 확인한다.
"""

from __future__ import annotations

import httpx
import pytest

from config import ConfigError, Settings
from sources import (
    bus_arrival,
    bus_position,
    city_realtime,
    event,
    sdot_population,
    subway,
    subway_alert,
    subway_arrival,
    subway_position,
    traffic_incident,
    traffic_realtime,
)
from sources.api_utils import page_size, parse_kst, safe_json, seoul_rows


def settings(source: str, *, keys=None, extra=None) -> Settings:
    return Settings(source=source, kafka_bootstrap_servers="k", api_keys=keys or {}, extra=extra or {})


def json_client(body: dict, calls: list | None = None) -> httpx.Client:
    def handler(request: httpx.Request) -> httpx.Response:
        if calls is not None:
            calls.append(request)
        return httpx.Response(200, json=body)

    return httpx.Client(transport=httpx.MockTransport(handler))


def test_bus_position_fetch_keeps_provider_ids_and_coordinates():
    calls = []
    body = {
        "msgHeader": {"headerCd": "0", "headerMsg": "정상"},
        "msgBody": {"itemList": {"vehId": "V1", "gpsX": "127.1", "gpsY": "37.5", "sectOrd": "3"}},
    }
    cfg = settings("bus_position", keys={"DATA_GO_KR_API_KEY": "secret"}, extra={"BUS_ROUTE_IDS": "R1,R1"})
    with json_client(body, calls) as client:
        records = bus_position.fetch(cfg, client)
    assert len(calls) == 1 and len(records) == 1
    assert records[0].key == "V1"
    assert records[0].location == (37.5, 127.1)
    assert records[0].payload["providerRouteId"] == "R1"
    assert records[0].segment_ids is None


def test_bus_arrival_accepts_list_and_uses_route_stop_key():
    body = {
        "msgHeader": {"headerCd": "0"},
        "msgBody": {"itemList": [{"stId": "S1", "traTime1": "120"}, {"stId": "S2", "traTime1": "240"}]},
    }
    cfg = settings("bus_arrival", keys={"DATA_GO_KR_API_KEY": "k"}, extra={"BUS_ROUTE_IDS": "R1"})
    with json_client(body) as client:
        records = bus_arrival.fetch(cfg, client)
    assert [record.key for record in records] == ["R1:S1", "R1:S2"]


def test_bus_requires_explicit_route_scope():
    cfg = settings("bus_position", keys={"DATA_GO_KR_API_KEY": "k"})
    with json_client({}) as client, pytest.raises(ConfigError, match="BUS_ROUTE_IDS"):
        bus_position.fetch(cfg, client)


def test_bus_partial_route_failure_keeps_successful_route_records():
    """한 노선의 제공자 장애가 같은 주기의 다른 노선 관측을 지우지 않아야 한다."""
    def handler(request: httpx.Request) -> httpx.Response:
        route_id = request.url.params["busRouteId"]
        if route_id == "BROKEN":
            return httpx.Response(503, text="temporary unavailable")
        body = {"msgHeader": {"headerCd": "0"}, "msgBody": {"itemList": {"vehId": "V1"}}}
        return httpx.Response(200, json=body)

    cfg = settings(
        "bus_position",
        keys={"DATA_GO_KR_API_KEY": "secret"},
        extra={"BUS_ROUTE_IDS": "BROKEN,HEALTHY"},
    )
    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        records = bus_position.fetch(cfg, client)
    assert [record.payload["providerRouteId"] for record in records] == ["HEALTHY"]


def test_bus_all_route_failures_mark_cycle_failed():
    """전 대상 장애를 정상 빈 응답으로 숨기면 안 되므로 사이클 실패로 올린다."""
    cfg = settings(
        "bus_position",
        keys={"DATA_GO_KR_API_KEY": "secret"},
        extra={"BUS_ROUTE_IDS": "BROKEN"},
    )
    client = httpx.Client(transport=httpx.MockTransport(lambda _: httpx.Response(503, text="unavailable")))
    with client, pytest.raises(RuntimeError, match="전부 실패"):
        bus_position.fetch(cfg, client)


def test_subway_position_uses_source_timestamp_and_provider_station():
    body = {
        "errorMessage": {"status": 200, "message": "정상"},
        "realtimePositionList": {
            "subwayId": "1002", "statnId": "0201", "trainNo": "2210",
            "recptnDt": "2026-09-03 12:34:56",
        },
    }
    cfg = settings("subway_position", keys={"SEOUL_API_KEY": "k"}, extra={"SUBWAY_LINES": "2호선"})
    with json_client(body) as client:
        records = subway_position.fetch(cfg, client)
    assert records[0].key == "2호선:2210"
    assert records[0].payload["providerStationId"] == "0201"
    assert records[0].occurred_at.isoformat() == "2026-09-03T12:34:56+09:00"


def test_subway_position_uses_official_line_registry_by_default():
    calls = []
    body = {"errorMessage": {"status": 200}, "realtimePositionList": []}
    cfg = settings("subway_position", keys={"SEOUL_API_KEY": "k"})
    with json_client(body, calls) as client:
        assert subway_position.fetch(cfg, client) == []
    assert len(calls) == len(subway_position.DEFAULT_LINES) == 16


def test_subway_arrival_builds_station_train_records():
    body = {
        "errorMessage": {"status": 200},
        "realtimeArrivalList": [{"subwayId": "1002", "statnId": "0201", "btrainNo": "2210"}],
    }
    cfg = settings("subway_arrival", keys={"SEOUL_API_KEY": "k"}, extra={"SUBWAY_STATIONS": "시청"})
    with json_client(body) as client:
        records = subway_arrival.fetch(cfg, client)
    assert records[0].key == "1002:0201:2210"
    assert records[0].event_type == "subway.arrival"


def test_subway_aggregate_allows_only_one_configured_kind():
    body = {"errorMessage": {"status": 200}, "realtimePositionList": []}
    cfg = settings("subway", keys={"SEOUL_API_KEY": "k"}, extra={"SUBWAY_LINES": "1호선"})
    with json_client(body) as client:
        assert subway.fetch(cfg, client) == []


def test_traffic_realtime_parses_xml_without_promoting_link_to_segment():
    xml = """<TrafficInfo><RESULT><CODE>INFO-000</CODE></RESULT><row><LINK_ID>L1</LINK_ID><SPD>22.5</SPD></row></TrafficInfo>"""
    cfg = settings("traffic_realtime", keys={"SEOUL_API_KEY": "k"}, extra={"TRAFFIC_LINK_IDS": "L1"})
    client = httpx.Client(transport=httpx.MockTransport(lambda _: httpx.Response(200, text=xml)))
    with client:
        records = traffic_realtime.fetch(cfg, client)
    assert records[0].payload["providerLinkId"] == "L1"
    assert records[0].segment_ids is None


def test_traffic_incident_parses_event_start():
    xml = """<AccInfo><RESULT><CODE>INFO-000</CODE></RESULT><row><ACC_ID>A1</ACC_ID><OCC_DT>20260903120000</OCC_DT></row></AccInfo>"""
    cfg = settings("traffic_incident", keys={"SEOUL_API_KEY": "k"})
    client = httpx.Client(transport=httpx.MockTransport(lambda _: httpx.Response(200, text=xml)))
    with client:
        records = traffic_incident.fetch(cfg, client)
    assert records[0].key == "A1"
    assert records[0].occurred_at.hour == 12


def test_city_realtime_calls_each_area_once():
    calls = []
    body = {"CITYDATA": {"LIVE_PPLTN_STTS": {"PPLTN_TIME": "2026-09-03 12:10"}}}
    cfg = settings(
        "city_realtime",
        keys={"SEOUL_API_KEY": "k"},
        extra={"CITY_AREA_IDENTIFIERS": "POI001,POI002"},
    )
    with json_client(body, calls) as client:
        records = city_realtime.fetch(cfg, client)
    assert len(calls) == 2 and [record.key for record in records] == ["POI001", "POI002"]


def test_city_realtime_reads_replaceable_utf8_registry(tmp_path):
    registry = tmp_path / "areas.txt"
    registry.write_text("# 최신 공식 목록\n광화문·덕수궁\n강남역\n광화문·덕수궁\n", encoding="utf-8")
    cfg = settings("city_realtime", extra={"CITY_AREA_REGISTRY_PATH": str(registry)})
    assert city_realtime.area_identifiers(cfg) == ["광화문·덕수궁", "강남역"]


def test_city_realtime_bundles_current_official_121_area_codes():
    values = city_realtime.area_identifiers(settings("city_realtime"))
    assert len(values) == len(set(values)) == 121
    assert values[0] == "POI001" and values[-1] == "POI131"


def test_sdot_population_extracts_service_rows():
    body = {"IotVdata018": {"RESULT": {"CODE": "INFO-000"}, "row": {"SENSOR_ID": "D1", "SENSING_TIME": "20260903121000"}}}
    cfg = settings("sdot_population", keys={"SEOUL_API_KEY": "k"}, extra={"SDOT_DISTRICTS": "Gangnam-gu"})
    with json_client(body) as client:
        records = sdot_population.fetch(cfg, client)
    assert records[0].key == "D1"
    assert records[0].event_type == "sdot.population"


def test_sdot_population_keeps_only_latest_observation_per_sensor():
    rows = [
        {"SENSOR_ID": "D1", "SENSING_TIME": "20260903120000", "VISITOR_COUNT": "10"},
        {"SENSOR_ID": "D1", "SENSING_TIME": "20260903121000", "VISITOR_COUNT": "12"},
        {"SENSOR_ID": "D2", "SENSING_TIME": "20260903120500", "VISITOR_COUNT": "3"},
    ]
    records = sdot_population.records_from_rows(rows)
    by_sensor = {record.key: record for record in records}
    assert set(by_sensor) == {"D1", "D2"}
    assert by_sensor["D1"].payload["raw"]["VISITOR_COUNT"] == "12"


def test_sdot_population_pages_until_expected_sensors_are_seen():
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        start = int(request.url.path.split("/")[-2])
        if start == 1:
            rows = [
                {"SENSOR_ID": "D1", "SENSING_TIME": "20260903121000"},
                {"SENSOR_ID": "D2", "SENSING_TIME": "20260903121000"},
            ]
        else:
            rows = [{"SENSOR_ID": "D3", "SENSING_TIME": "20260903121000"}]
        return httpx.Response(
            200,
            json={"IotVdata018": {"list_total_count": 3, "RESULT": {"CODE": "INFO-000"}, "row": rows}},
        )

    cfg = settings(
        "sdot_population",
        keys={"SEOUL_API_KEY": "k"},
        extra={"SEOUL_API_PAGE_SIZE": "2", "SDOT_EXPECTED_SENSOR_COUNT": "3"},
    )
    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        records = sdot_population.fetch(cfg, client)
    assert len(calls) == 2
    assert {record.key for record in records} == {"D1", "D2", "D3"}


def test_subway_alert_gateway_shape_and_key_guard():
    body = {"response": {"header": {"resultCode": "00"}, "body": {"items": {"item": {"noticeId": "N1", "startDt": "2026-09-03 12:00:00"}}}}}
    cfg = settings(
        "subway_alert",
        keys={"DATA_GO_KR_API_KEY": "secret"},
        extra={"SUBWAY_ALERT_API_URL": "https://apis.data.go.kr/provider/notices"},
    )
    with json_client(body) as client:
        records = subway_alert.fetch(cfg, client)
    assert records[0].key == "N1"
    bad = settings(
        "subway_alert", keys={"DATA_GO_KR_API_KEY": "secret"},
        extra={"SUBWAY_ALERT_API_URL": "https://example.test?serviceKey=secret"},
    )
    with json_client(body) as client, pytest.raises(ConfigError, match="인증키"):
        subway_alert.fetch(bad, client)


def test_event_aggregate_refuses_silent_empty_configuration():
    with json_client({}) as client, pytest.raises(ConfigError, match="하나 이상"):
        event.fetch(settings("event", keys={"SEOUL_API_KEY": "k"}), client)


def test_shared_validation_handles_limits_no_data_and_secret_safe_error():
    assert parse_kst("20260903123456").utcoffset().total_seconds() == 9 * 3600
    assert page_size(settings("x", extra={"SEOUL_API_PAGE_SIZE": "1"})) == 1
    with pytest.raises(ConfigError):
        page_size(settings("x", extra={"SEOUL_API_PAGE_SIZE": "1001"}))
    assert seoul_rows({"RESULT": {"CODE": "INFO-200"}}, "missing") == []
    response = httpx.Response(403, text="denied")
    with pytest.raises(ValueError) as excinfo:
        safe_json(response, provider="provider")
    assert "secret" not in str(excinfo.value)
