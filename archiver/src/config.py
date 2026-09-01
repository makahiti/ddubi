"""환경변수 → archiver 설정. 주소·경로는 전부 환경변수로만 받는다."""

from __future__ import annotations

import os
from dataclasses import dataclass


class ConfigError(RuntimeError):
    pass


def _env(name: str, default: str | None = None) -> str | None:
    value = os.environ.get(name)
    if value is None or value.strip() == "":
        return default
    return value.strip()


@dataclass(frozen=True)
class Settings:
    kafka_bootstrap_servers: str
    topics: tuple[str, ...]  # docs/contracts/topics.yaml 에서 archive: true 인 토픽
    archive_uri: str  # file:///opt/ddubi/archive (2단계) → webhdfs://hdfs-namenode:9870/ddubi/archive (3단계)
    flush_seconds: int  # 버퍼의 첫 메시지부터 이 시간이 지나면 파일로 쓴다 (기본 1시간)
    max_bytes: int  # 버퍼가 이 크기를 넘으면 시간과 무관하게 파일로 쓴다 (기본 128MB)
    group_id: str = "archiver"
    hdfs_user: str = "hadoop"  # webhdfs:// 일 때 user.name 으로 보낸다 (권한 검사는 꺼져 있지만 소유자 표기용)

    @classmethod
    def from_env(cls) -> "Settings":
        bootstrap = _env("KAFKA_BOOTSTRAP_SERVERS")
        if not bootstrap:
            raise ConfigError("KAFKA_BOOTSTRAP_SERVERS 가 필요합니다")

        uri = _env("ARCHIVE_URI")
        if not uri:
            local_dir = _env("ARCHIVE_LOCAL_DIR")
            if not local_dir:
                raise ConfigError("ARCHIVE_URI 또는 ARCHIVE_LOCAL_DIR 가 필요합니다")
            uri = f"file://{local_dir}"

        topics = tuple(t.strip() for t in (_env("ARCHIVE_TOPICS", "transport.raw,city.raw") or "").split(",") if t.strip())
        if not topics:
            raise ConfigError("ARCHIVE_TOPICS 가 비어 있습니다")

        # 개발·테스트용 초 단위 지정이 있으면 우선, 없으면 분 단위(기본 60분)
        flush_seconds_raw = _env("ARCHIVE_FLUSH_SECONDS")
        if flush_seconds_raw:
            flush_seconds = int(flush_seconds_raw)
        else:
            flush_seconds = int(_env("ARCHIVE_FLUSH_MINUTES", "60") or "60") * 60
        if flush_seconds <= 0:
            raise ConfigError("flush 주기는 1초 이상이어야 합니다")

        max_bytes = int(_env("ARCHIVE_MAX_BYTES", str(128 * 1024 * 1024)) or 0)
        if max_bytes <= 0:
            raise ConfigError("ARCHIVE_MAX_BYTES 는 1 이상이어야 합니다")

        return cls(
            kafka_bootstrap_servers=bootstrap,
            topics=topics,
            archive_uri=uri.rstrip("/"),
            flush_seconds=flush_seconds,
            max_bytes=max_bytes,
            group_id=_env("ARCHIVE_GROUP_ID", "archiver") or "archiver",
            hdfs_user=_env("ARCHIVE_HDFS_USER", "hadoop") or "hadoop",
        )
