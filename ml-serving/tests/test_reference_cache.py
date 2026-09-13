"""파일 I/O 캐시가 ID·순서·결측·특성값을 바꾸거나 메모리를 무한 보관하지 않게 한다."""
from pathlib import Path
import sys
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
    path = tmp_path / 'a.parquet'
    write_table(path, {'id': ['001', '002'], 'seconds': [30., None]})
    cache = ReferenceCache([path], 10000)
    module = SimpleNamespace(pd=pd, pq=pq)
    original_reader = pd.read_parquet
    cache.bind(module)
    first = module.pd.read_parquet(path)
    expected = pd.read_parquet(path)
    pd.testing.assert_frame_equal(first, expected)
    first.loc[0, 'id'] = 'corrupted'
    pd.testing.assert_frame_equal(module.pd.read_parquet(path), expected)
    actual = module.pq.ParquetFile(path).read(columns=['seconds', 'id']).to_pandas()
    pd.testing.assert_frame_equal(actual, expected[['seconds', 'id']])
    assert pd.read_parquet is original_reader
    assert cache.hits == 1 and cache.misses == 2


def test_lru_budget_disabled_and_oversized_entries(tmp_path):
    paths = [tmp_path / f'{i}.parquet' for i in range(3)]
    for i, path in enumerate(paths):
        write_table(path, {'value': [i]})
    size = pq.read_table(paths[0]).nbytes
    cache = ReferenceCache(paths, size * 2)
    cache.read(paths[0]); cache.read(paths[1]); cache.read(paths[0]); cache.read(paths[2])
    assert cache.bytes <= size * 2
    assert cache.hits == 1 and cache.evictions == 1
    cache.read(paths[1])
    assert cache.misses == 4  # 최근 읽은 0번 대신 가장 오래된 1번이 제거됐다.
    for budget in [0, size - 1]:
        disabled = ReferenceCache(paths, budget)
        disabled.read(paths[0]); disabled.read(paths[0])
        assert disabled.bytes == 0 and disabled.hits == 0 and disabled.misses == 2


def test_unverified_and_failed_reads_are_not_cached(tmp_path):
    path = tmp_path / 'broken.parquet'
    cache = ReferenceCache([path], 1024)
    with pytest.raises(ValueError, match='UNVERIFIED_REFERENCE'):
        cache.read(tmp_path / 'outside.parquet')
    with pytest.raises(FileNotFoundError):
        cache.read(path)
    path.write_bytes(b'not parquet')
    with pytest.raises(pa.ArrowInvalid):
        cache.read(path)
    assert cache.stats()['entries'] == 0
    write_table(path, {'seconds': [15]})
    assert cache.read(path).to_pydict() == {'seconds': [15]}
