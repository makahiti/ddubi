"""검증된 참조 Parquet의 디코딩 결과를 프로세스별 LRU로 보관한다.

모델 묶음은 실행 중 불변이다. 경로·열·표현 조합별 Arrow/pandas를 캐시하며 byte 상한을
넘는 단일 파일은 보관하지 않는다. 상한은 각 표현의 추정 바이트 합계로 RSS/임시 변환 메모리와
다르다. Predictor의 단일 추론 락 안에서만 사용하며 파일 실패는 캐시하지 않는다.
"""

from collections import OrderedDict
from pathlib import Path

import pyarrow as pa
import pyarrow.compute as compute
import pyarrow.parquet as parquet


class ReferenceCache:
    """SHA 검증 목록에 있는 파일만 읽는다. 미검증 추가 파일은 성공으로 취급하지 않는다."""

    def __init__(self, allowed_paths, max_bytes):
        if max_bytes < 0:
            raise ValueError("negative cache size")
        self.allowed = frozenset(Path(path).resolve() for path in allowed_paths)
        self.max_bytes = max_bytes
        self.entries = OrderedDict()
        self.bytes = 0
        self.hits = self.misses = self.evictions = 0
        self.monthly_scope = None

    def read(self, path, columns=None):
        """검증된 파일의 요청 열을 불변 Arrow Table로 제공한다."""
        return self._read(path, columns, pandas=False)

    def read_pandas(self, path, columns=None):
        """문자열 ID·수치 참조 표의 변환 결과를 재사용하고 요청에는 독립 복사본을 준다.

        v12의 스칼라 참조 열만 대상으로 한다. 호출자가 열을 덮어쓰거나 정렬해도 다음
        요청의 원본 표는 바뀌지 않는다. 모델 묶음 파일이나 SHA 검증 목록은 수정하지 않는다.
        """
        return self._read(path, columns, pandas=True).copy(deep=True)

    def _read(self, path, columns, *, pandas):
        resolved = Path(path).resolve()
        if resolved not in self.allowed:
            raise ValueError("UNVERIFIED_REFERENCE")
        # attach_features 호출 안에서만 요청의 두 달 전 월로 제한한다. 일반 파일 읽기는
        # 기존처럼 전체 표를 반환하며 월 집합도 키에 포함해 요청 간 자료 혼입을 막는다.
        months = (
            self.monthly_scope[1]
            if self.monthly_scope is not None and resolved == self.monthly_scope[0] and pandas
            else None
        )
        key = (resolved, None if columns is None else tuple(columns), pandas, months)
        if key in self.entries:
            self.hits += 1
            self.entries.move_to_end(key)
            return self.entries[key]
        self.misses += 1
        table = parquet.ParquetFile(resolved).read(columns=columns)
        if months is not None:
            # 원본 함수는 공개일이 확인된 자료 전체에 many_to_one 검증을 한다.
            # 월 필터가 다른 월의 중복을 숨기지 않도록 같은 적격 행의 키를 먼저 검사한다.
            keys = table.select(["month_key", "provider_stop_id", "publication_assumed", "published_date"]).to_pandas()
            eligible = keys.loc[~keys.publication_assumed & keys.published_date.notna()]
            if eligible.duplicated(["month_key", "provider_stop_id"]).any():
                raise ValueError("DUPLICATE_MONTHLY_REFERENCE")
            table = table.filter(compute.is_in(table["month_key"], value_set=pa.array(months, type=pa.int64())))
        if pandas:
            table = table.to_pandas()
        # 대용량 한 파일 때문에 다른 모든 캐시를 지우지 않는다. 읽기 오류는 그대로 전파한다.
        size = int(table.memory_usage(index=True, deep=True).sum()) if pandas else table.nbytes
        if self.max_bytes and size <= self.max_bytes:
            while self.entries and self.bytes + size > self.max_bytes:
                removed_key, removed = self.entries.popitem(last=False)
                self.bytes -= (
                    int(removed.memory_usage(index=True, deep=True).sum()) if removed_key[2] else removed.nbytes
                )
                self.evictions += 1
            self.entries[key] = table
            self.bytes += size
        return table

    def stats(self):
        """성능 측정용 집계값. 원본 파일 경로나 사용자 요청 식별자는 노출하지 않는다."""
        return {
            "hits": self.hits,
            "misses": self.misses,
            "evictions": self.evictions,
            "entries": len(self.entries),
            "bytes": self.bytes,
            "max_bytes": self.max_bytes,
        }

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
                return cache.read_pandas(path, columns)

        class PandasRead:
            """v12의 read(...).to_pandas() 형태만 지원하는 지연 변환 어댑터다.

            Arrow 전체 API로 가장하지 않는다. 다른 읽기 옵션은 명시적으로 실패하므로
            향후 portable 코드 변경 시 회귀 검증 없이 의미가 다른 변환을 적용하지 않는다.
            """

            def __init__(self, path, columns):
                self.path, self.columns = path, columns

            def to_pandas(self):
                return cache.read_pandas(self.path, self.columns)

        class CachedFile:
            def __init__(self, path):
                self.path = path

            def read(self, columns=None):
                return PandasRead(self.path, columns)

        class ParquetView:
            ParquetFile = CachedFile

            def __getattr__(self, name):
                return getattr(original_pq, name)

        original_pd, original_pq = features.pd, features.pq
        features.pd, features.pq = PandasView(), ParquetView()
        if hasattr(features, "attach_features"):
            original_attach = features.attach_features

            def attach_features(frame, reference_root):
                """v12의 두 달 지연 월자료만 읽게 범위를 설정하고 예외 발생 시에도 해제한다.

                모델·portable 원본·SHA를 바꾸지 않는 I/O 최적화다. 공개일·정류장·시간대
                선택 및 결측 처리는 기존 함수가 수행한다. Predictor의 추론 락이 범위를 보호한다.
                """
                previous = cache.monthly_scope
                try:
                    if frame.empty:
                        return original_attach(frame, reference_root)
                    prior = original_pd.to_datetime(frame.service_date).dt.to_period("M") - 2
                    months = tuple(sorted(set((prior.dt.year * 100 + prior.dt.month).tolist())))
                    cache.monthly_scope = ((Path(reference_root) / "contexts/monthly_demand.parquet").resolve(), months)
                    return original_attach(frame, reference_root)
                finally:
                    cache.monthly_scope = previous

            features.attach_features = attach_features
