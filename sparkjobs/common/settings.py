"""환경변수 → 잡 설정. 주소·비밀번호는 전부 환경변수로만 받는다 (compose 가 주입)."""

from __future__ import annotations

import os
from dataclasses import dataclass


def _env(name: str, default: str | None = None) -> str | None:
    value = os.environ.get(name)
    if value is None or value.strip() == "":
        return default
    return value.strip()


def _require(name: str) -> str:
    value = _env(name)
    if value is None:
        raise RuntimeError(f"환경변수 {name} 가 필요합니다")
    return value


@dataclass(frozen=True)
class Settings:
    kafka_bootstrap_servers: str
    redis_host: str
    redis_port: int
    redis_password: str | None
    checkpoint_dir: str  # 1단계: 로컬 볼륨, 3단계: hdfs://... (값만 바뀐다)
    starting_offsets: str = "latest"  # 체크포인트가 없을 때만 적용된다
    max_offsets_per_trigger: int = 10000  # 트리거(10s)당 최대 소비 건수 = 처리량 상한. 부하 실험 때 올린다

    @classmethod
    def from_env(cls) -> Settings:
        return cls(
            kafka_bootstrap_servers=_require("KAFKA_BOOTSTRAP_SERVERS"),
            redis_host=_require("REDIS_HOST"),
            redis_port=int(_env("REDIS_PORT", "6379") or "6379"),
            redis_password=_env("REDIS_PASSWORD"),
            checkpoint_dir=_require("SPARK_CHECKPOINT_DIR").rstrip("/"),
            starting_offsets=_env("SPARK_STARTING_OFFSETS", "latest") or "latest",
            max_offsets_per_trigger=int(_env("SPARK_MAX_OFFSETS_PER_TRIGGER", "10000") or "10000"),
        )

    def checkpoint_for(self, job_name: str) -> str:
        """잡마다 체크포인트 디렉터리를 분리한다 (세션 기본값만 쓰면 재시작 때 랜덤 경로가 되어 상태를 잃는다)."""
        return f"{self.checkpoint_dir}/{job_name}"
