from datetime import datetime

import httpx
import pytest

from config import ConfigError, Settings
from sources import weather
from sources.base import KST

KMA_OK = {
    "response": {
        "header": {"resultCode": "00", "resultMsg": "NORMAL_SERVICE"},
        "body": {
            "dataType": "JSON",
            "items": {
                "item": [
                    {"baseDate": "20260827", "baseTime": "0900", "category": "T1H", "nx": 60, "ny": 127, "obsrValue": "27.3"},
                    {"baseDate": "20260827", "baseTime": "0900", "category": "RN1", "nx": 60, "ny": 127, "obsrValue": "0"},
                    {"baseDate": "20260827", "baseTime": "0900", "category": "REH", "nx": 60, "ny": 127, "obsrValue": "64"},
                    {"baseDate": "20260827", "baseTime": "0900", "category": "PTY", "nx": 60, "ny": 127, "obsrValue": "0"},
                    {"baseDate": "20260827", "baseTime": "0900", "category": "WSD", "nx": 60, "ny": 127, "obsrValue": "2.1"},
                ]
            },
            "numOfRows": 20, "pageNo": 1, "totalCount": 5,
        },
    }
}


@pytest.mark.parametrize(
    ("now", "expected"),
    [
        (datetime(2026, 8, 27, 10, 5, tzinfo=KST), datetime(2026, 8, 27, 9, 0, tzinfo=KST)),   # 10:05 → 아직 09시 자료
        (datetime(2026, 8, 27, 10, 45, tzinfo=KST), datetime(2026, 8, 27, 10, 0, tzinfo=KST)),  # 10:45 → 10시 자료
        (datetime(2026, 8, 27, 0, 20, tzinfo=KST), datetime(2026, 8, 26, 23, 0, tzinfo=KST)),   # 날짜 경계
    ],
)
def test_base_datetime(now, expected):
    assert weather.base_datetime(now) == expected


def test_parse_response_ok():
    assert weather.parse_response(KMA_OK) == {"T1H": 27.3, "RN1": 0.0, "REH": 64.0, "PTY": 0.0, "WSD": 2.1}


def test_parse_response_error_code():
    body = {"response": {"header": {"resultCode": "03", "resultMsg": "NO_DATA"}}}
    with pytest.raises(ValueError, match="resultCode=03"):
        weather.parse_response(body)


def test_default_grid_points_cover_25_districts():
    points = weather.parse_grid_points(weather.DEFAULT_GRID_POINTS)
    assert len(points) == 25
    assert ("강남구", 61, 126) in points


def test_group_by_grid_merges_shared_grids():
    grids = weather.group_by_grid(weather.parse_grid_points(weather.DEFAULT_GRID_POINTS))
    assert len(grids) == 14  # 서울 25개 구가 공유하는 고유 격자 수
    by_grid = {(nx, ny): districts for nx, ny, districts in grids}
    assert by_grid[(60, 127)] == ["종로구", "중구"]
    assert by_grid[(58, 126)] == ["양천구", "강서구", "영등포구"]


def test_grid_points_format_error():
    with pytest.raises(ConfigError):
        weather.parse_grid_points("강남구:61")


def test_select_endpoint_prefers_kma_hub_then_data_go_kr():
    both = Settings(source="weather", kafka_bootstrap_servers="k", api_keys={"KMA_API_KEY": "hub", "DATA_GO_KR_API_KEY": "portal"})
    url, param, key = weather.select_endpoint(both)
    assert "apihub.kma.go.kr" in url and param == "authKey" and key == "hub"

    portal_only = Settings(source="weather", kafka_bootstrap_servers="k", api_keys={"DATA_GO_KR_API_KEY": "portal"})
    url, param, key = weather.select_endpoint(portal_only)
    assert "apis.data.go.kr" in url and param == "serviceKey" and key == "portal"


def test_fetch_builds_city_records_with_mock_transport():
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append({"host": request.url.host, **dict(request.url.params)})
        return httpx.Response(200, json=KMA_OK)

    settings = Settings(
        source="weather", kafka_bootstrap_servers="kafka:9092",
        api_keys={"DATA_GO_KR_API_KEY": "test-key"},
        extra={"WEATHER_GRID_POINTS": "종로구:60:127,강남구:61:126"},
    )
    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        records = weather.fetch(settings, client)

    assert len(records) == 2
    assert calls[0]["host"] == "apis.data.go.kr"
    assert calls[0]["serviceKey"] == "test-key"
    assert calls[0]["dataType"] == "JSON"
    assert records[0].key == "60:127"
    assert records[0].event_type == "weather.observation"
    assert records[0].payload["districts"] == ["종로구"]
    assert records[0].payload["observations"]["T1H"] == 27.3
    assert records[0].occurred_at.tzinfo is not None


def test_fetch_calls_shared_grid_once_with_district_list():
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(dict(request.url.params))
        return httpx.Response(200, json=KMA_OK)

    settings = Settings(
        source="weather", kafka_bootstrap_servers="kafka:9092",
        api_keys={"DATA_GO_KR_API_KEY": "test-key"},
        extra={"WEATHER_GRID_POINTS": "종로구:60:127,중구:60:127,용산구:60:126"},
    )
    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        records = weather.fetch(settings, client)

    assert len(calls) == 2  # 격자 2개 → 호출 2번 (구 3개여도)
    assert len(records) == 2
    by_key = {r.key: r for r in records}
    assert by_key["60:127"].payload["districts"] == ["종로구", "중구"]
    assert by_key["60:126"].payload["districts"] == ["용산구"]


def test_fetch_without_key_raises_config_error():
    settings = Settings(source="weather", kafka_bootstrap_servers="kafka:9092")
    with httpx.Client(transport=httpx.MockTransport(lambda r: httpx.Response(200, json=KMA_OK))) as client:
        with pytest.raises(ConfigError):
            weather.fetch(settings, client)


def test_fetch_uses_kma_hub_auth_key_when_set():
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append({"host": request.url.host, **dict(request.url.params)})
        return httpx.Response(200, json=KMA_OK)

    settings = Settings(
        source="weather", kafka_bootstrap_servers="kafka:9092",
        api_keys={"KMA_API_KEY": "hub-key"},
        extra={"WEATHER_GRID_POINTS": "종로구:60:127"},
    )
    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        records = weather.fetch(settings, client)
    assert len(records) == 1
    assert calls[0]["host"] == "apihub.kma.go.kr"
    assert calls[0]["authKey"] == "hub-key"
    assert "serviceKey" not in calls[0]


def test_fetch_http_403_is_logged_without_url_and_counted_as_failure():
    settings = Settings(
        source="weather", kafka_bootstrap_servers="kafka:9092",
        api_keys={"KMA_API_KEY": "secret-key"},
        extra={"WEATHER_GRID_POINTS": "종로구:60:127"},
    )
    body = '{"result":{"status":403,"message":"활용신청이 필요한 API 입니다."}}'
    with httpx.Client(transport=httpx.MockTransport(lambda r: httpx.Response(403, text=body))) as client:
        with pytest.raises(RuntimeError, match="전부 실패") as excinfo:
            weather.fetch(settings, client)
    assert "secret-key" not in str(excinfo.value)


def test_fetch_all_points_failing_raises():
    settings = Settings(
        source="weather", kafka_bootstrap_servers="kafka:9092",
        api_keys={"DATA_GO_KR_API_KEY": "bad"},
        extra={"WEATHER_GRID_POINTS": "종로구:60:127"},
    )
    xml = "<OpenAPI_ServiceResponse><cmmMsgHeader><returnAuthMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</returnAuthMsg></cmmMsgHeader></OpenAPI_ServiceResponse>"
    with httpx.Client(transport=httpx.MockTransport(lambda r: httpx.Response(200, text=xml))) as client:
        with pytest.raises(RuntimeError, match="전부 실패"):
            weather.fetch(settings, client)
