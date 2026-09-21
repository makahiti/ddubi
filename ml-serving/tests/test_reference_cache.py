"""파일 I/O 캐시가 ID·순서·결측·특성값을 바꾸거나 메모리를 무한 보관하지 않게 한다."""

import sys
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import pandas as pd
import pyarrow as pa
import pyarrow.parquet as pq
import pytest
from reference_cache import ReferenceCache


def write_table(path, values):
    table = pa.table(values)
    pq.write_table(table, path)
    return table


def test_both_portable_readers_preserve_values_and_do_not_patch_global_modules(tmp_path):
    path = tmp_path / "a.parquet"
    write_table(path, {"id": ["001", "002"], "seconds": [30.0, None]})
    cache = ReferenceCache([path], 10000)
    module = SimpleNamespace(pd=pd, pq=pq)
    original_reader = pd.read_parquet
    cache.bind(module)
    first = module.pd.read_parquet(path)
    expected = pd.read_parquet(path)
    pd.testing.assert_frame_equal(first, expected)
    first.loc[0, "id"] = "corrupted"
    pd.testing.assert_frame_equal(module.pd.read_parquet(path), expected)
    actual = module.pq.ParquetFile(path).read(columns=["seconds", "id"]).to_pandas()
    pd.testing.assert_frame_equal(actual, expected[["seconds", "id"]])
    assert pd.read_parquet is original_reader
    assert cache.hits == 1 and cache.misses == 2


def test_lru_budget_disabled_and_oversized_entries(tmp_path):
    paths = [tmp_path / f"{i}.parquet" for i in range(3)]
    for i, path in enumerate(paths):
        write_table(path, {"value": [i]})
    size = pq.read_table(paths[0]).nbytes
    cache = ReferenceCache(paths, size * 2)
    cache.read(paths[0])
    cache.read(paths[1])
    cache.read(paths[0])
    cache.read(paths[2])
    assert cache.bytes <= size * 2
    assert cache.hits == 1 and cache.evictions == 1
    cache.read(paths[1])
    assert cache.misses == 4  # 최근 읽은 0번 대신 가장 오래된 1번이 제거됐다.
    for budget in [0, size - 1]:
        disabled = ReferenceCache(paths, budget)
        disabled.read(paths[0])
        disabled.read(paths[0])
        assert disabled.bytes == 0 and disabled.hits == 0 and disabled.misses == 2


def test_unverified_and_failed_reads_are_not_cached(tmp_path):
    path = tmp_path / "broken.parquet"
    cache = ReferenceCache([path], 1024)
    with pytest.raises(ValueError, match="UNVERIFIED_REFERENCE"):
        cache.read(tmp_path / "outside.parquet")
    with pytest.raises(FileNotFoundError):
        cache.read(path)
    path.write_bytes(b"not parquet")
    with pytest.raises(pa.ArrowInvalid):
        cache.read(path)
    assert cache.stats()["entries"] == 0
    write_table(path, {"seconds": [15]})
    assert cache.read(path).to_pydict() == {"seconds": [15]}


def test_pandas_conversion_is_reused_and_copies_are_isolated(tmp_path, monkeypatch):
    """두 portable 읽기 형태가 반복 변환을 피하면서 요청 간 수정을 격리한다."""
    path = tmp_path / "daily.parquet"
    write_table(path, {"id": ["001", "002"], "seconds": [0.0, None]})
    cache = ReferenceCache([path], 10000)
    module = SimpleNamespace(pd=pd, pq=pq)
    cache.bind(module)
    expected = pd.read_parquet(path)
    first = module.pq.ParquetFile(path).read().to_pandas()
    first.loc[0, "seconds"] = 999
    # 원본 읽기 자체가 다시 일어나면 실패시켜 캐시 재사용을 시간 측정 없이 검증한다.
    monkeypatch.setattr(pq, "ParquetFile", lambda *a, **k: pytest.fail("repeated conversion"))
    pd.testing.assert_frame_equal(module.pd.read_parquet(path), expected)
    assert cache.hits == 1
    assert cache.bytes == int(expected.memory_usage(index=True, deep=True).sum())


def test_pandas_and_arrow_share_budget_and_oversized_frames_are_not_retained(tmp_path):
    path = tmp_path / "daily.parquet"
    write_table(path, {"id": ["001", "002"], "seconds": [0.0, None]})
    expected = pd.read_parquet(path)
    size = int(expected.memory_usage(index=True, deep=True).sum())
    cache = ReferenceCache([path], size)
    cache.read(path)
    cache.read_pandas(path)
    assert cache.bytes == size and cache.evictions == 1
    cache.read(path)
    assert cache.bytes <= size and cache.evictions == 2
    for budget in [0, size - 1]:
        limited = ReferenceCache([path], budget)
        limited.read_pandas(path)
        limited.read_pandas(path)
        assert limited.bytes == 0 and limited.hits == 0


def monthly_module(tmp_path, months):
    """월 필터 밖의 중복·공개일도 검사할 수 있는 최소 v12 읽기 모형이다."""
    folder = tmp_path / "contexts"
    folder.mkdir()
    path = folder / "monthly_demand.parquet"
    write_table(
        path,
        {
            "month_key": months,
            "provider_stop_id": ["001"] * len(months),
            "publication_assumed": [False] * len(months),
            "published_date": pd.to_datetime(["2026-01-01"] * len(months)),
            "board_00": [0.0] * len(months),
        },
    )
    module = SimpleNamespace(pd=pd, pq=pq)
    module.attach_features = lambda frame, root: module.pd.read_parquet(path)
    cache = ReferenceCache([path], 100000)
    cache.bind(module)
    return module, cache, path


def test_month_scope_tracks_year_boundary_multiple_dates_and_restores_full_read(tmp_path):
    module, cache, path = monthly_module(tmp_path, [202511, 202512, 202607])
    request = pd.DataFrame({"service_date": ["2026-01-01", "2026-02-01"]})
    result = module.attach_features(request, tmp_path)
    assert result.month_key.tolist() == [202511, 202512]
    assert result.board_00.tolist() == [0.0, 0.0]
    pd.testing.assert_frame_equal(module.attach_features(request, tmp_path), result)
    assert cache.hits == 1
    assert module.attach_features(pd.DataFrame({"service_date": ["2026-09-18"]}), tmp_path).month_key.tolist() == [
        202607
    ]
    assert cache.monthly_scope is None
    assert len(module.pd.read_parquet(path)) == 3


def test_duplicate_in_unrequested_month_is_still_rejected_and_scope_cleared(tmp_path):
    module, cache, _ = monthly_module(tmp_path, [202511, 202511, 202607])
    with pytest.raises(ValueError, match="DUPLICATE_MONTHLY_REFERENCE"):
        module.attach_features(pd.DataFrame({"service_date": ["2026-09-18"]}), tmp_path)
    assert cache.monthly_scope is None
    assert cache.stats()["entries"] == 0
