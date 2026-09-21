"""Parquet 파일 저장. fsspec 으로 file:// 와 webhdfs:// 를 같은 코드로 다룬다.

- 2단계(지금): ARCHIVE_URI=file:///opt/ddubi/archive  → 컨테이너 로컬 볼륨
- 3단계(HDFS):  ARCHIVE_URI=webhdfs://hdfs-namenode:9870/ddubi/archive → NameNode WebHDFS
파일은 .tmp 로 먼저 쓰고 이름을 바꿔서, 읽는 쪽(Spark)이 반쯤 쓰인 파일을 보지 않게 한다.
"""

from __future__ import annotations

import logging
import time
from collections.abc import Callable, Iterable
from dataclasses import dataclass
from typing import TypeVar

import fsspec
import pyarrow as pa
import pyarrow.parquet as pq
import requests

log = logging.getLogger(__name__)

T = TypeVar("T")

# 짧은 HDFS 장애를 넘기기 위한 재시도 (2026-09-09).
# 네임노드 재시작 직후 세이프 모드(1~2분, 쓰기 거부 403)나 데이터노드 재시작(리다이렉트 연결 거부)은
# 수십 초~2분 안에 풀린다. 재시도 없이 예외가 나면 아카이버 프로세스가 죽고 컨테이너가 재시작하는데,
# 커밋 전이라 유실은 없지만 그때마다 버퍼를 다시 채우는 낭비가 생긴다. 2·4·8초 세 번이면 약 14초를 버틴다.
RETRY_ATTEMPTS = 3
RETRY_BACKOFF_SECONDS = 2.0
RETRY_MAX_WAIT_SECONDS = 30.0
# 기동 시(폴더 생성)는 네임노드 세이프 모드 1~2분을 통째로 기다려야 하므로 더 길게: 2+4+8+16+30+30+30 = 120초
STARTUP_RETRY_ATTEMPTS = 8
_RETRIABLE = (OSError, requests.exceptions.RequestException)
_NO_RETRY_STATUS = {400, 401, 404}  # 요청 자체가 잘못된 것 — 다시 보내도 같다
# fsspec webhdfs 는 서버가 준 RemoteException 중 IllegalArgument→ValueError, AccessControl→PermissionError,
# FileNotFound→FileNotFoundError 로 바꾸고 "나머지"는 RuntimeError 로 던진다. 세이프 모드(SafeModeException)·
# StandbyException·RetriableException 이 그 나머지에 들어간다 (2026-09-09 배포 때 세이프 모드 33초 동안 9번 재시작한 원인).
# RuntimeError 전부를 재시도하면 진짜 버그도 삼키므로, 메시지에 아래 표식이 있을 때만 재시도한다.
_RETRIABLE_RUNTIME_MARKERS = ("safe mode", "safemode", "retriable", "standby")
_sleep = time.sleep  # 테스트가 바꿔 끼운다


def _response_status(exc: BaseException) -> int | None:
    """예외나 response 구현이 비표준이어도 재시도 판단 중 새 오류를 만들지 않는다."""
    try:
        response = getattr(exc, "response", None)
        return getattr(response, "status_code", None)
    except Exception:  # 커스텀 property getter 자체가 실패하는 경우까지 방어한다.
        return None


def _is_retriable(exc: BaseException) -> bool:
    if isinstance(exc, _RETRIABLE):
        status = _response_status(exc)
        return status not in _NO_RETRY_STATUS
    if isinstance(exc, RuntimeError):
        msg = str(exc).lower()
        return any(m in msg for m in _RETRIABLE_RUNTIME_MARKERS)
    return False


def with_retry(
    fn: Callable[[], T],
    what: str,
    attempts: int = RETRY_ATTEMPTS,
    backoff: float = RETRY_BACKOFF_SECONDS,
    max_wait: float = RETRY_MAX_WAIT_SECONDS,
) -> T:
    """fn 을 실행하고 짧은 장애(연결 오류·HTTP 5xx·세이프 모드)면 backoff 배수(상한 max_wait)로 기다린 뒤 다시 시도한다."""
    for attempt in range(1, attempts + 1):
        try:
            return fn()
        except Exception as exc:  # noqa: BLE001 — 아래에서 재시도 대상만 골라내고 나머지는 그대로 올린다
            if not _is_retriable(exc) or attempt == attempts:
                raise
            wait = min(backoff * (2 ** (attempt - 1)), max_wait)
            status = _response_status(exc)
            log.warning(
                "%s 실패 (%s%s: %s), %.0f초 뒤 재시도 %d/%d",
                what,
                exc.__class__.__name__,
                f" {status}" if status else "",
                str(exc)[:80],
                wait,
                attempt + 1,
                attempts,
            )
            _sleep(wait)
    raise RuntimeError("unreachable")


@dataclass
class Storage:
    fs: fsspec.AbstractFileSystem
    root: str

    @classmethod
    def from_uri(cls, uri: str, hdfs_user: str | None = None) -> Storage:
        options = {}
        if uri.startswith("webhdfs://") and hdfs_user:
            options["user"] = hdfs_user
        fs, root = fsspec.core.url_to_fs(uri, **options)
        # 기동 직후 네임노드가 세이프 모드면 MKDIRS 가 거절된다. 여기서 죽으면 컨테이너가 재시작을 반복하므로 길게 기다린다
        with_retry(lambda: fs.makedirs(root, exist_ok=True), f"init {uri}", attempts=STARTUP_RETRY_ATTEMPTS)
        return cls(fs=fs, root=root.rstrip("/"))

    def write_parquet(self, relative_path: str, table: pa.Table) -> str:
        """table 을 root/relative_path 에 원자적으로 쓴다. 최종 경로를 돌려준다. 짧은 HDFS 장애는 재시도로 넘긴다."""
        final = f"{self.root}/{relative_path}"
        tmp = f"{final}.tmp"
        parent = final.rsplit("/", 1)[0]

        def _write_once() -> str:
            if self.fs.exists(final):  # 앞 시도가 mv 까지 끝난 뒤 응답만 못 받은 경우
                return final
            self.fs.makedirs(parent, exist_ok=True)
            with self.fs.open(tmp, "wb") as f:  # 재시도면 .tmp 를 덮어쓴다
                pq.write_table(table, f, compression="snappy")
            self.fs.mv(tmp, final)
            return final

        return with_retry(_write_once, f"write {relative_path}")

    def read_parquet(self, path: str, columns: Iterable[str] | None = None) -> pa.Table:
        """Parquet 파일을 읽는다 (replay 용). 데이터노드 재시작 중 리다이렉트 실패를 재시도로 넘긴다."""
        cols = list(columns) if columns is not None else None

        def _read_once() -> pa.Table:
            with self.fs.open(path, "rb") as f:
                return pq.read_table(f, columns=cols)

        return with_retry(_read_once, f"read {path}")

    def size(self, path: str) -> int:
        return int(self.fs.size(path))
