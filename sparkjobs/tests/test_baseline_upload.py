"""업로드 클라이언트를 가짜 수신 서버(계약 구현)에 붙여 검증한다. Spark 불필요."""

from __future__ import annotations

import threading

import pytest

from common.baseline_upload import BaselineUploadClient, UploadError
from tests.mock_baseline_server import make_server

API_KEY = "test-key"


@pytest.fixture()
def server():
    srv = make_server(port=0, api_key=API_KEY)
    thread = threading.Thread(target=srv.serve_forever, daemon=True)
    thread.start()
    yield srv
    srv.shutdown()


@pytest.fixture()
def client(server):
    return BaselineUploadClient(
        base_url="http://127.0.0.1:{}".format(server.server_address[1]),
        api_key=API_KEY, chunk_size=100, backoff_seconds=0.01)


def make_rows(n, version_tag="a", p50=300, p95=600):
    return [
        {"segmentId": "seg-{}-{:06d}".format(version_tag, i), "dow": 1 + i % 7,
         "hourSlot": i % 24, "weatherBucket": "DRY",
         "p50": p50, "p80": (p50 + p95) // 2, "p95": p95, "sampleCount": 10}
        for i in range(n)
    ]


def test_upload_and_activate_happy_path(server, client):
    result = client.upload_and_activate("2026-09-01", make_rows(250))
    assert result["activeVersion"] == "2026-09-01"
    state = server.state
    assert state.active_version == "2026-09-01"
    assert len(state.versions["2026-09-01"]) == 250
    assert state.last_activated_at is not None
    # 청크 100행 × 3회(100+100+50) + activate 1회
    assert state.request_count == 4


def test_generator_rows_are_chunked(client, server):
    rows = ({"segmentId": "seg-g-{}".format(i), "dow": 2, "hourSlot": 8,
             "weatherBucket": "RAIN", "p50": 1, "p80": 2, "p95": 3, "sampleCount": 1}
            for i in range(101))
    assert client.upload_rows("v-gen", rows) == 101
    assert len(server.state.versions["v-gen"]) == 101


def test_invalid_row_rejected_without_retry(server, client):
    bad = make_rows(1)
    bad[0]["p50"] = 999  # p50 > p95
    with pytest.raises(UploadError) as excinfo:
        client.upload_rows("v-bad", bad)
    assert excinfo.value.status == 400
    assert excinfo.value.code == "INVALID_REQUEST"
    assert excinfo.value.request_id  # 래퍼의 requestId 가 보존된다
    assert server.state.request_count == 1  # 4xx 는 재시도하지 않는다
    assert "v-bad" not in server.state.versions  # 부분 저장 없음


def test_wrong_api_key_is_401(server):
    bad_client = BaselineUploadClient(
        base_url="http://127.0.0.1:{}".format(server.server_address[1]), api_key="wrong")
    with pytest.raises(UploadError) as excinfo:
        bad_client.upload_rows("v", make_rows(1))
    assert excinfo.value.status == 401


def test_transient_500_is_retried(server, client):
    server.state.fail_next = 2  # 처음 2개 요청은 500
    assert client.upload_rows("v-retry", make_rows(50)) == 50
    # 실패 2회 + 성공 1회
    assert server.state.request_count == 3


def test_activate_row_count_mismatch_is_409(server, client):
    client.upload_rows("v-mis", make_rows(50))
    with pytest.raises(UploadError) as excinfo:
        client.activate("v-mis", total_rows=999)
    assert excinfo.value.status == 409
    assert excinfo.value.code == "ROW_COUNT_MISMATCH"
    assert server.state.active_version is None  # 활성 전환 안 됨


def test_upload_to_activated_version_is_409(server, client):
    client.upload_and_activate("v1", make_rows(10, "x"))
    with pytest.raises(UploadError) as excinfo:
        client.upload_rows("v1", make_rows(1, "y"))
    assert excinfo.value.status == 409
    assert excinfo.value.code == "ALREADY_ACTIVATED"


def test_row_count_drop_is_rejected(server, client):
    client.upload_and_activate("v1", make_rows(100, "x"))
    client.upload_rows("v2", make_rows(40, "y"))  # 직전 100행의 절반 미만
    with pytest.raises(UploadError) as excinfo:
        client.activate("v2", total_rows=40)
    assert excinfo.value.code == "ROW_COUNT_DROPPED"
    assert server.state.active_version == "v1"  # 이전 버전이 계속 서빙


def test_shrink_allowed_with_reason(server, client):
    client.upload_and_activate("v1", make_rows(100, "x"))
    client.upload_rows("v2", make_rows(40, "y"))  # 절반 미만이지만 운영자가 허용
    result = client.activate("v2", total_rows=40, allow_shrink=True, shrink_reason="노선 3개 폐지")
    assert result["activeVersion"] == "v2" and result["rowCount"] == 40
    assert server.state.last_shrink_reason == "노선 3개 폐지"


def test_shrink_without_reason_is_refused_before_sending(server, client):
    client.upload_rows("v-nr", make_rows(5, "z"))
    with pytest.raises(ValueError):
        client.activate("v-nr", total_rows=5, allow_shrink=True)


def test_shrink_reason_over_100_chars_is_refused_before_sending(server, client):
    client.upload_rows("v-long", make_rows(5, "z"))
    with pytest.raises(ValueError):
        client.activate("v-long", total_rows=5, allow_shrink=True, shrink_reason="사" * 101)
    # 정확히 100자는 통과해야 한다 (서버 mock 도 같은 기준)
    result = client.activate("v-long", total_rows=5, allow_shrink=True, shrink_reason="사" * 100)
    assert result["activeVersion"] == "v-long"


def test_shrink_options_from_env():
    from common.baseline_upload import shrink_options_from_env
    assert shrink_options_from_env({}) == {"allow_shrink": False, "shrink_reason": None}
    opts = shrink_options_from_env({"BASELINE_ALLOW_SHRINK": "true", "BASELINE_SHRINK_REASON": " 폐지 "})
    assert opts == {"allow_shrink": True, "shrink_reason": "폐지"}


def test_version_protection_keeps_active_and_previous(server, client):
    client.upload_and_activate("v1", make_rows(100, "a"))
    client.upload_and_activate("v2", make_rows(90, "b"))
    result = client.upload_and_activate("v3", make_rows(80, "c"))
    assert result["purgedVersions"] == ["v1"]  # 활성(v3)·직전(v2)만 보호
    assert set(server.state.versions) == {"v2", "v3"}


def test_duplicate_rows_are_upserted(server, client):
    rows = make_rows(30, "dup")
    client.upload_rows("v-dup", rows)
    client.upload_rows("v-dup", rows)  # 같은 청크 재전송 (재시도 시나리오)
    assert len(server.state.versions["v-dup"]) == 30  # 중복 없이 30행 유지
    # 클라이언트가 센 총계(60)가 아니라 실제 고유 행 수(30)로 활성화해야 한다
    result = client.activate("v-dup", total_rows=30)
    assert result["activeVersion"] == "v-dup"


def test_empty_rows_raise(client):
    with pytest.raises(UploadError):
        client.upload_rows("v-empty", [])
