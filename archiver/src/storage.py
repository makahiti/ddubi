"""Parquet 파일 저장. fsspec 으로 file:// 와 webhdfs:// 를 같은 코드로 다룬다.

- 2단계(지금): ARCHIVE_URI=file:///opt/ddubi/archive  → 컨테이너 로컬 볼륨
- 3단계(HDFS):  ARCHIVE_URI=webhdfs://hdfs-namenode:9870/ddubi/archive → NameNode WebHDFS
파일은 .tmp 로 먼저 쓰고 이름을 바꿔서, 읽는 쪽(Spark)이 반쯤 쓰인 파일을 보지 않게 한다.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass

import fsspec
import pyarrow as pa
import pyarrow.parquet as pq

log = logging.getLogger(__name__)


@dataclass
class Storage:
    fs: fsspec.AbstractFileSystem
    root: str

    @classmethod
    def from_uri(cls, uri: str, hdfs_user: str | None = None) -> "Storage":
        options = {}
        if uri.startswith("webhdfs://") and hdfs_user:
            options["user"] = hdfs_user
        fs, root = fsspec.core.url_to_fs(uri, **options)
        fs.makedirs(root, exist_ok=True)
        return cls(fs=fs, root=root.rstrip("/"))

    def write_parquet(self, relative_path: str, table: pa.Table) -> str:
        """table 을 root/relative_path 에 원자적으로 쓴다. 최종 경로를 돌려준다."""
        final = f"{self.root}/{relative_path}"
        tmp = f"{final}.tmp"
        parent = final.rsplit("/", 1)[0]
        self.fs.makedirs(parent, exist_ok=True)
        with self.fs.open(tmp, "wb") as f:
            pq.write_table(table, f, compression="snappy")
        self.fs.mv(tmp, final)
        return final

    def size(self, path: str) -> int:
        return int(self.fs.size(path))
