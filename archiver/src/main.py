"""Kafka → Parquet archiver.

archive 대상 토픽을 읽어 (토픽, 시간) 버퍼에 모으고, 5분 또는 16MB 가 차면 Parquet 파일 하나로 쓴다.
(2026-09-25 까지는 1시간·128MB 였다. 버스 자료가 늘자 파일로 바꾸는 순간 메모리가 512m 을 넘어 OOM 이 반복됐다.)
파일을 다 쓴 뒤에만 오프셋을 커밋하므로 archiver 가 죽어도 유실은 없다 (마지막 커밋 이후 구간은 재시작 때
다시 읽혀 중복될 수 있다 → 읽는 쪽은 eventId 로 중복 제거).
"""

from __future__ import annotations

import logging
import signal
import sys
import threading

from buffer import Batch, Row, hour_of
from config import ConfigError, Settings
from confluent_kafka import Consumer, KafkaError, TopicPartition
from storage import Storage

log = logging.getLogger("archiver")


class Archiver:
    def __init__(self, settings: Settings, consumer: Consumer, storage: Storage) -> None:
        self.settings = settings
        self.consumer = consumer
        self.storage = storage
        self.batches: dict[tuple[str, object], Batch] = {}  # (topic, hour) → Batch
        self.files_written = 0
        self.rows_written = 0

    # ---- 버퍼 ----
    def add(self, topic: str, row: Row, timestamp_ms: int) -> None:
        key = (topic, hour_of(timestamp_ms))
        batch = self.batches.get(key)
        if batch is None:
            batch = self.batches[key] = Batch(topic=topic, hour=key[1])
        batch.add(row)

    def flush_due(self) -> None:
        """기한이 된 배치가 있는 토픽은 그 토픽의 배치를 전부 쓴다.

        같은 토픽의 이전 시간 배치가 남아 있는데 새 시간 배치만 먼저 커밋하면, 그 사이 죽었을 때
        이전 시간 데이터를 잃는다. 그래서 토픽 단위로 모두 쓰고 나서 커밋한다.
        """
        due_topics = {
            b.topic for b in self.batches.values() if b.is_due(self.settings.flush_seconds, self.settings.max_bytes)
        }
        for topic in due_topics:
            self.flush_topic(topic)

    def flush_topic(self, topic: str) -> None:
        keys = sorted((k for k in self.batches if k[0] == topic), key=lambda k: k[1])
        if not keys:
            return
        commit: dict[int, int] = {}
        for key in keys:
            batch = self.batches.pop(key)
            if not batch.rows:
                continue
            path = self.storage.write_parquet(batch.relative_path(), batch.to_table())
            self.files_written += 1
            self.rows_written += len(batch.rows)
            log.info("wrote %s rows=%d bytes=%d", path, len(batch.rows), self.storage.size(path))
            for partition, nxt in batch.next_offsets.items():
                commit[partition] = max(commit.get(partition, -1), nxt)
        if commit:
            self.consumer.commit(offsets=[TopicPartition(topic, p, o) for p, o in commit.items()], asynchronous=False)
            log.info("committed %s %s", topic, commit)

    def flush_all(self) -> None:
        for topic in {k[0] for k in self.batches}:
            self.flush_topic(topic)

    # ---- 루프 ----
    def run(self, stop: threading.Event) -> None:
        self.consumer.subscribe(list(self.settings.topics), on_revoke=self._on_revoke)
        log.info(
            "start topics=%s archive=%s flush=%ss max=%dB",
            self.settings.topics,
            self.settings.archive_uri,
            self.settings.flush_seconds,
            self.settings.max_bytes,
        )
        while not stop.is_set():
            msg = self.consumer.poll(1.0)
            if msg is not None:
                if msg.error():
                    if msg.error().code() != KafkaError._PARTITION_EOF:
                        log.error("kafka error: %s", msg.error())
                else:
                    _, ts = msg.timestamp()
                    row = Row.from_message(msg.key(), msg.value(), msg.partition(), msg.offset(), ts)
                    self.add(msg.topic(), row, ts)
            self.flush_due()
        log.info("stopping: flushing %d batches", len(self.batches))
        self.flush_all()
        self.consumer.close()
        log.info("stop files=%d rows=%d", self.files_written, self.rows_written)

    def _on_revoke(self, consumer: Consumer, partitions) -> None:
        # 리밸런스로 파티션을 잃기 전에 들고 있던 것을 써서 커밋해 둔다
        log.info("partitions revoked: %s", [(p.topic, p.partition) for p in partitions])
        self.flush_all()


def build_consumer(settings: Settings) -> Consumer:
    return Consumer(
        {
            "bootstrap.servers": settings.kafka_bootstrap_servers,
            "group.id": settings.group_id,
            "client.id": "archiver",
            "enable.auto.commit": False,  # 파일을 쓴 뒤 직접 커밋
            "auto.offset.reset": "earliest",  # 처음 붙을 때 보존된 전부를 아카이브
            "max.poll.interval.ms": 600000,  # 큰 파일을 쓰는 동안 그룹에서 쫓겨나지 않게
            "session.timeout.ms": 45000,
        }
    )


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s", stream=sys.stdout)
    try:
        settings = Settings.from_env()
    except ConfigError as exc:
        log.error("%s", exc)
        return 2

    stop = threading.Event()
    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, lambda *_: stop.set())

    storage = Storage.from_uri(settings.archive_uri, settings.hdfs_user)
    archiver = Archiver(settings, build_consumer(settings), storage)
    try:
        archiver.run(stop)
    except Exception:
        log.exception("archiver failed")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
