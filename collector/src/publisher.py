"""Kafka 발행. 수집기는 Kafka 에 발행만 하고 다른 저장소는 건드리지 않는다."""

from __future__ import annotations

import json
import logging
from typing import Any

from confluent_kafka import KafkaError, Message, Producer

log = logging.getLogger(__name__)


class Publisher:
    def __init__(self, bootstrap_servers: str, client_id: str) -> None:
        self._producer = Producer(
            {
                "bootstrap.servers": bootstrap_servers,
                "client.id": client_id,
                "acks": "all",
                "enable.idempotence": True,  # 재시도해도 중복 저장 안 됨
                "compression.type": "lz4",
                "linger.ms": 50,
                "message.timeout.ms": 30000,
            }
        )
        self.sent = 0
        self.failed = 0

    def _on_delivery(self, err: KafkaError | None, msg: Message) -> None:
        if err is not None:
            self.failed += 1
            log.error("발행 실패 topic=%s key=%s err=%s", msg.topic(), msg.key(), err)
        else:
            self.sent += 1

    def publish(self, topic: str, key: str, value: dict[str, Any]) -> None:
        self._producer.produce(
            topic=topic,
            key=key.encode("utf-8"),
            value=json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8"),
            on_delivery=self._on_delivery,
        )
        self._producer.poll(0)  # 콜백 처리 (블로킹 없이)

    def flush(self, timeout_seconds: float = 30.0) -> int:
        """남은 메시지를 모두 보낸다. 미전송 개수를 돌려준다."""
        remaining = self._producer.flush(timeout_seconds)
        if remaining:
            log.error("flush 후에도 %d 건 미전송", remaining)
        return remaining
