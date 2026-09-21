"""Kafka 메시지를 (토픽, 시간) 단위로 모아 Parquet 테이블로 만든다.

- 원본 value(JSON 문자열)는 그대로 보존한다. 봉투 필드(eventId 등)는 조회 편의를 위해 컬럼으로 뽑되,
  파싱에 실패해도 원본은 버리지 않는다 (아카이브는 "전부 남기는 것" 이 목적).
- 파일 경로: {topic}/date=YYYY-MM-DD/hour=HH/{topic}-{YYYYMMDDTHH}-{첫 오프셋}-{uuid8}.parquet
  Spark 가 date=, hour= 를 파티션 컬럼으로 인식한다.
- 시간 기준은 Kafka 메시지 timestamp(브로커 도착 시각, KST). 발생 시각(occurredAt)은 늦게 도착할 수 있어 파일 배치 기준으로 쓰지 않는다.
"""

from __future__ import annotations

import json
import logging
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone

import pyarrow as pa

log = logging.getLogger(__name__)

KST = timezone(timedelta(hours=9), name="Asia/Seoul")

SCHEMA = pa.schema(
    [
        pa.field("kafka_key", pa.string()),
        pa.field("value", pa.string()),  # 원본 JSON 그대로
        pa.field("event_id", pa.string()),
        pa.field("event_type", pa.string()),
        pa.field("source", pa.string()),
        pa.field("occurred_at", pa.timestamp("us", tz="Asia/Seoul")),
        pa.field("collected_at", pa.timestamp("us", tz="Asia/Seoul")),
        pa.field("kafka_partition", pa.int32()),
        pa.field("kafka_offset", pa.int64()),
        pa.field("kafka_timestamp", pa.timestamp("ms", tz="Asia/Seoul")),
    ]
)


def _parse_dt(value: object) -> datetime | None:
    if not isinstance(value, str):
        return None
    try:
        dt = datetime.fromisoformat(value)
    except ValueError:
        return None
    return dt if dt.tzinfo else dt.replace(tzinfo=KST)


def hour_of(kafka_timestamp_ms: int) -> datetime:
    """Kafka timestamp(ms epoch) → KST 정시."""
    return datetime.fromtimestamp(kafka_timestamp_ms / 1000, tz=KST).replace(minute=0, second=0, microsecond=0)


@dataclass
class Row:
    kafka_key: str | None
    value: str
    kafka_partition: int
    kafka_offset: int
    kafka_timestamp: datetime
    event_id: str | None = None
    event_type: str | None = None
    source: str | None = None
    occurred_at: datetime | None = None
    collected_at: datetime | None = None

    @classmethod
    def from_message(cls, key: bytes | None, value: bytes, partition: int, offset: int, timestamp_ms: int) -> Row:
        text = value.decode("utf-8", errors="replace")
        row = cls(
            kafka_key=key.decode("utf-8", errors="replace") if key is not None else None,
            value=text,
            kafka_partition=partition,
            kafka_offset=offset,
            kafka_timestamp=datetime.fromtimestamp(timestamp_ms / 1000, tz=KST),
        )
        try:
            event = json.loads(text)
        except ValueError:
            return row  # 원본만 남긴다
        if isinstance(event, dict):
            row.event_id = event.get("eventId") if isinstance(event.get("eventId"), str) else None
            row.event_type = event.get("eventType") if isinstance(event.get("eventType"), str) else None
            row.source = event.get("source") if isinstance(event.get("source"), str) else None
            row.occurred_at = _parse_dt(event.get("occurredAt"))
            row.collected_at = _parse_dt(event.get("collectedAt"))
        return row

    @property
    def size_bytes(self) -> int:
        return len(self.value.encode("utf-8")) + (len(self.kafka_key.encode("utf-8")) if self.kafka_key else 0)


@dataclass
class Batch:
    """(topic, hour) 하나에 대한 버퍼."""

    topic: str
    hour: datetime  # KST 정시
    rows: list[Row] = field(default_factory=list)
    size_bytes: int = 0
    opened_at: float = field(default_factory=time.monotonic)
    next_offsets: dict[int, int] = field(default_factory=dict)  # partition → 커밋할 오프셋(마지막+1)

    def add(self, row: Row) -> None:
        self.rows.append(row)
        self.size_bytes += row.size_bytes
        nxt = row.kafka_offset + 1
        if nxt > self.next_offsets.get(row.kafka_partition, -1):
            self.next_offsets[row.kafka_partition] = nxt

    @property
    def age_seconds(self) -> float:
        return time.monotonic() - self.opened_at

    def is_due(self, flush_seconds: int, max_bytes: int) -> bool:
        return bool(self.rows) and (self.size_bytes >= max_bytes or self.age_seconds >= flush_seconds)

    def relative_path(self) -> str:
        first_offset = min(r.kafka_offset for r in self.rows)
        name = f"{self.topic}-{self.hour:%Y%m%dT%H}-{first_offset:012d}-{uuid.uuid4().hex[:8]}.parquet"
        return f"{self.topic}/date={self.hour:%Y-%m-%d}/hour={self.hour:%H}/{name}"

    def to_table(self) -> pa.Table:
        columns = {
            "kafka_key": [r.kafka_key for r in self.rows],
            "value": [r.value for r in self.rows],
            "event_id": [r.event_id for r in self.rows],
            "event_type": [r.event_type for r in self.rows],
            "source": [r.source for r in self.rows],
            "occurred_at": [r.occurred_at for r in self.rows],
            "collected_at": [r.collected_at for r in self.rows],
            "kafka_partition": [r.kafka_partition for r in self.rows],
            "kafka_offset": [r.kafka_offset for r in self.rows],
            "kafka_timestamp": [r.kafka_timestamp for r in self.rows],
        }
        return pa.table(
            {name: pa.array(values, type=SCHEMA.field(name).type) for name, values in columns.items()}, schema=SCHEMA
        )
