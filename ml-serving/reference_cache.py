"""검증된 참조 Parquet의 디코딩 결과를 프로세스별 LRU로 보관한다.

모델 묶음은 실행 중 불변이다. 경로·열 조합별 Arrow Table을 캐시하며 byte 상한을
넘는 단일 파일은 보관하지 않는다. 상한은 Table.nbytes 합계로 RSS/임시 변환 메모리와
다르다. Predictor의 단일 추론 락 안에서만 사용하며 파일 실패는 캐시하지 않는다.
"""
from collections import OrderedDict
from pathlib import Path

import pyarrow.parquet as parquet


class ReferenceCache:
    """SHA 검증 목록에 있는 파일만 읽는다. 미검증 추가 파일은 성공으로 취급하지 않는다."""

    def __init__(self, allowed_paths, max_bytes):
        if max_bytes < 0:
            raise ValueError('negative cache size')
        self.allowed = frozenset(Path(path).resolve() for path in allowed_paths)
        self.max_bytes = max_bytes
        self.entries = OrderedDict()
        self.bytes = 0
        self.hits = self.misses = self.evictions = 0

    def read(self, path, columns=None):
        """검증된 파일의 요청 열을 읽는다. Arrow Table은 불변이며 pandas 결과는 매번 새로 만든다."""
        resolved = Path(path).resolve()
        if resolved not in self.allowed:
            raise ValueError('UNVERIFIED_REFERENCE')
        key = (resolved, None if columns is None else tuple(columns))
        if key in self.entries:
            self.hits += 1
            self.entries.move_to_end(key)
            return self.entries[key]
        self.misses += 1
        table = parquet.ParquetFile(resolved).read(columns=columns)
        # 대용량 한 파일 때문에 다른 모든 캐시를 지우지 않는다. 읽기 오류는 그대로 전파한다.
        size = table.nbytes
        if self.max_bytes and size <= self.max_bytes:
            while self.entries and self.bytes + size > self.max_bytes:
                _, removed = self.entries.popitem(last=False)
                self.bytes -= removed.nbytes
                self.evictions += 1
            self.entries[key] = table
            self.bytes += size
        return table

    def stats(self):
        """성능 측정용 집계값. 원본 파일 경로나 사용자 요청 식별자는 노출하지 않는다."""
        return {'hits': self.hits, 'misses': self.misses, 'evictions': self.evictions,
                'entries': len(self.entries), 'bytes': self.bytes, 'max_bytes': self.max_bytes}

    def bind(self, features):
        """검증된 portable 모듈의 I/O만 객체별로 치환한다. 전역 pandas/pyarrow는 수정하지 않는다.

기존 묶음의 SHA와 특성 계산식을 그대로 유지한다. 현재 v12가 사용하는 두 읽기 형태만
지원하며 모르는 옵션은 TypeError로 실패시켜 의미가 다른 읽기를 조용히 캐시하지 않는다.
"""
        cache = self

        class PandasView:
            def __getattr__(self, name):
                return getattr(original_pd, name)

            def read_parquet(self, path, columns=None):
                return cache.read(path, columns).to_pandas()

        class CachedFile:
            def __init__(self, path):
                self.path = path

            def read(self, columns=None):
                return cache.read(self.path, columns)

        class ParquetView:
            ParquetFile = CachedFile

            def __getattr__(self, name):
                return getattr(original_pq, name)

        original_pd, original_pq = features.pd, features.pq
        features.pd, features.pq = PandasView(), ParquetView()
