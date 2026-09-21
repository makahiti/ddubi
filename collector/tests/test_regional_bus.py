"""지역 namespace·초 단위 원문과 페이지/오류 처리, 운영 기본값 보존을 검증한다."""

import sys
from pathlib import Path
from types import SimpleNamespace

import httpx
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from sources.regional_bus import collect, make_source, parse_page, to_record


def xml(rows, total=1, code="00"):
    return (
        "<response><header><resultCode>"
        + code
        + "</resultCode></header><body><totalCount>"
        + str(total)
        + "</totalCount><items>"
        + rows
        + "</items></body></response>"
    ).encode()


def test_namespace_raw_and_units():
    record = to_record(
        "incheon_arrival",
        {"ROUTEID": "001", "ARRIVALESTIMATETIME": "120", "BUSID": "v"},
        "002",
    )
    assert record.payload["providerNamespace"] == "INCHEON_API"
    assert record.payload["providerStopId"] == "002"
    assert record.payload["raw"]["ARRIVALESTIMATETIME"] == "120"
    assert record.segment_ids is None
    assert record.payload["timestampBasis"] == "received"
    assert make_source("gbis_arrival").default_interval_seconds == 28800


@pytest.mark.parametrize("code", ["99", "", "-1"])
def test_provider_failure_is_not_empty_success(code):
    with pytest.raises(ValueError):
        parse_page(xml("", 0, code), "INCHEON_API")


def test_paging_and_shared_key_budget(tmp_path):
    calls = []

    def handler(request):
        calls.append(request)
        page = request.url.params["pageNo"]
        assert request.url.params["ServiceKey"] == "test+key"
        return httpx.Response(
            200,
            content=xml("<item><ROUTEID>" + page + "</ROUTEID><BSTOPID>100</BSTOPID></item>", 2),
        )

    settings = SimpleNamespace(
        extra={
            "REGIONAL_BUS_TARGET_IDS": "100",
            "API_BUDGET_PATH": str(tmp_path / "budget.sqlite"),
            "API_DAILY_LIMIT": "10",
        },
        interval_seconds=28800,
        api_key=lambda _: "test%2Bkey",
    )
    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        assert len(collect("incheon_arrival", settings, client)) == 2
        assert collect("incheon_arrival", settings, client) == []
        assert not client.event_hooks["request"]
    assert len(calls) == 2


def test_incomplete_page_fails_without_secret(tmp_path):
    calls = []

    def handler(request):
        calls.append(request)
        return httpx.Response(
            200,
            content=xml("<item><ROUTEID>1</ROUTEID></item>" if len(calls) == 1 else "", 2),
        )

    settings = SimpleNamespace(
        extra={
            "REGIONAL_BUS_TARGET_IDS": "100",
            "API_BUDGET_PATH": str(tmp_path / "budget.sqlite"),
        },
        interval_seconds=28800,
        api_key=lambda _: "not-for-logs",
    )
    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        with pytest.raises(RuntimeError, match="ValueError") as error:
            collect("incheon_arrival", settings, client)
        assert "not-for-logs" not in str(error.value)
        assert not client.event_hooks["request"]
