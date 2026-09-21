"""Storage 재시도: 짧은 HDFS 장애(연결 거부·세이프 모드 403)는 넘기고, 잘못된 요청(404)은 바로 실패한다."""

import pyarrow as pa
import pytest
import requests
import storage as storage_mod
from storage import Storage, with_retry


@pytest.fixture(autouse=True)
def no_sleep(monkeypatch):
    waits = []
    monkeypatch.setattr(storage_mod, "_sleep", lambda s: waits.append(s))
    return waits


def _http_error(status):
    resp = requests.Response()
    resp.status_code = status
    return requests.exceptions.HTTPError(response=resp)


def test_retries_connection_error_then_succeeds(no_sleep):
    calls = {"n": 0}

    def flaky():
        calls["n"] += 1
        if calls["n"] < 3:
            raise requests.exceptions.ConnectionError("dn refused")
        return "ok"

    assert with_retry(flaky, "test") == "ok"
    assert calls["n"] == 3
    assert no_sleep == [2.0, 4.0]  # 2초, 4초 뒤 재시도 (지수 백오프)


def test_retries_timeout_without_response_then_succeeds(no_sleep):
    calls = {"n": 0}

    def flaky():
        calls["n"] += 1
        if calls["n"] == 1:
            raise requests.exceptions.Timeout("namenode timeout")
        return "ok"

    assert with_retry(flaky, "test") == "ok"
    assert calls["n"] == 2
    assert no_sleep == [2.0]


def test_plain_custom_exception_without_response_is_not_retried(no_sleep):
    class CustomError(Exception):
        pass

    with pytest.raises(CustomError):
        with_retry(lambda: (_ for _ in ()).throw(CustomError("custom failure")), "test")
    assert no_sleep == []


def test_request_exception_response_without_status_code_is_retried(no_sleep):
    calls = {"n": 0}

    def flaky():
        calls["n"] += 1
        if calls["n"] == 1:
            raise requests.exceptions.RequestException(response=object())
        return "ok"

    assert with_retry(flaky, "test") == "ok"
    assert calls["n"] == 2
    assert no_sleep == [2.0]


def test_broken_custom_response_property_does_not_mask_original_error(no_sleep):
    class BrokenResponseError(OSError):
        @property
        def response(self):
            raise RuntimeError("broken response property")

    with pytest.raises(BrokenResponseError):
        with_retry(lambda: (_ for _ in ()).throw(BrokenResponseError("original error")), "test")
    assert no_sleep == [2.0, 4.0]


def test_gives_up_after_attempts(no_sleep):
    def always_fail():
        raise _http_error(403)  # 세이프 모드: 재시도 대상이지만 끝까지 안 풀리면 예외

    with pytest.raises(requests.exceptions.HTTPError):
        with_retry(always_fail, "test")
    assert no_sleep == [2.0, 4.0]  # 3회 시도, 대기 2회


def test_safe_mode_runtime_error_is_retried(no_sleep):
    calls = {"n": 0}

    def nn_in_safe_mode():
        calls["n"] += 1
        if calls["n"] == 1:
            raise RuntimeError("Cannot create directory /ddubi/archive. Name node is in safe mode.")
        return "ok"

    assert with_retry(nn_in_safe_mode, "test") == "ok"
    assert no_sleep == [2.0]


def test_other_runtime_error_is_not_retried(no_sleep):
    def bug():
        raise RuntimeError("unrelated programming error")

    with pytest.raises(RuntimeError):
        with_retry(bug, "test")
    assert no_sleep == []


def test_backoff_is_capped(no_sleep):
    def always():
        raise requests.exceptions.ConnectionError("down")

    with pytest.raises(requests.exceptions.ConnectionError):
        with_retry(always, "test", attempts=8)
    assert no_sleep == [2.0, 4.0, 8.0, 16.0, 30.0, 30.0, 30.0]  # 상한 30초, 합계 120초


def test_from_uri_waits_for_namenode(monkeypatch, tmp_path, no_sleep):
    real = storage_mod.fsspec.core.url_to_fs
    state = {"n": 0}

    def flaky_url_to_fs(uri, **kw):
        fs, root = real(uri, **kw)
        real_makedirs = fs.makedirs

        def makedirs(path, exist_ok=False):
            state["n"] += 1
            if state["n"] <= 2:
                raise RuntimeError("Name node is in safe mode.")
            return real_makedirs(path, exist_ok=exist_ok)

        fs.makedirs = makedirs
        return fs, root

    monkeypatch.setattr(storage_mod.fsspec.core, "url_to_fs", flaky_url_to_fs)
    st = Storage.from_uri(f"file://{tmp_path}/archive")
    assert st.root.endswith("archive") and state["n"] == 3
    assert no_sleep == [2.0, 4.0]


def test_404_is_not_retried(no_sleep):
    calls = {"n": 0}

    def missing():
        calls["n"] += 1
        raise _http_error(404)

    with pytest.raises(requests.exceptions.HTTPError):
        with_retry(missing, "test")
    assert calls["n"] == 1 and no_sleep == []


def test_write_parquet_recovers_from_one_failure(tmp_path, no_sleep):
    st = Storage.from_uri(f"file://{tmp_path}")
    real_open = st.fs.open
    state = {"failed": False}

    def open_once_failing(path, mode="rb", **kw):
        if mode == "wb" and not state["failed"]:
            state["failed"] = True
            raise OSError("datanode restarting")
        return real_open(path, mode, **kw)

    st.fs.open = open_once_failing
    table = pa.table({"kafka_key": ["k"], "value": ["v"], "kafka_timestamp": [1]})
    final = st.write_parquet("t/date=2026-09-09/hour=01/a.parquet", table)
    assert st.fs.exists(final) and not st.fs.exists(final + ".tmp")
    assert st.read_parquet(final).num_rows == 1
    assert no_sleep == [2.0]
