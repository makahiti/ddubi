"""Kafka 발행. 수집기는 Kafka 에 발행만 하고 다른 저장소는 건드리지 않는다."""

from __future__ import annotations

import json
import logging
import time
from typing import Any

from confluent_kafka import KafkaError, Message, Producer

log = logging.getLogger(__name__)

# 브로커가 없을 때 메시지를 내부 큐에 붙들고 재시도하는 시간. 호스트 재부팅 직후처럼 Kafka 가
# 수집기보다 늦게 뜨는 상황을 견디도록 한 사이클(10분) 만큼 준다. 이 시간을 넘기면 유실로 기록된다
# (2026-09-03 재부팅 때 30초 시한으로 1사이클 14건 유실 → 상향)
MESSAGE_TIMEOUT_MS = 600_000


class Publisher:
    def __init__(self, bootstrap_servers: str, client_id: str, producer: Any | None = None) -> None:
        # producer 인자는 테스트에서 가짜를 주입하기 위한 것. 운영에서는 항상 None
        self._producer = producer or Producer(
            {
                "bootstrap.servers": bootstrap_servers,
                "client.id": client_id,
                "acks": "all",
                "enable.idempotence": True,  # 재시도해도 중복 저장 안 됨
                "compression.type": "lz4",
                "linger.ms": 50,
                "message.timeout.ms": MESSAGE_TIMEOUT_MS,
            }
        )
        self.sent = 0
        self.failed = 0

    def wait_ready(self, timeout_seconds: float, poll_seconds: float = 5.0, sleep=time.sleep) -> bool:
        """브로커 메타데이터를 받을 수 있을 때까지 기다린다. 준비되면 True, 시한을 넘기면 False.

        compose 의 depends_on(kafka healthy) 은 `compose up` 때만 적용되고, 호스트 재부팅 시 도커
        재시작 정책으로 올라오는 컨테이너에는 적용되지 않는다. 그래서 수집기 스스로 확인한다.
        False 라도 발행은 계속한다 — 메시지는 MESSAGE_TIMEOUT_MS 동안 큐에서 재시도된다.
        """
        deadline = time.monotonic() + timeout_seconds
        attempt = 0
        while True:
            attempt += 1
            try:
                self._producer.list_topics(timeout=poll_seconds)
                if attempt > 1:
                    log.info("kafka 준비됨 (시도 %d회)", attempt)
                return True
            except Exception as exc:  # KafkaException 등. 브로커 부재는 정상 시나리오라 삼킨다
                if time.monotonic() >= deadline:
                    log.error("kafka 준비 대기 시한 초과 (%ss): %s — 발행은 큐에서 재시도된다", timeout_seconds, exc)
                    return False
                log.warning("kafka 대기 중 (시도 %d회): %s", attempt, exc)
                sleep(poll_seconds)

    def _on_delivery(self, err: KafkaError | None, msg: Message) -> None:
        if err is not None:
            self.failed += 1
            log.error("발행 실패 topic=%s key=%s err=%s", msg.topic(), msg.key(), err)
        else:
            self.sent += 1

    def publish(self, topic: str, key: str, value: dict[str, Any], on_delivery=None) -> None:
        def delivered(err, msg):
            self._on_delivery(err, msg)
            if on_delivery is not None:
                on_delivery(err, msg)
        self._producer.produce(
            topic=topic,
            key=key.encode("utf-8"),
            value=json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8"),
            on_delivery=delivered,
        )
        self._producer.poll(0)  # 콜백 처리 (블로킹 없이)

    def flush(self, timeout_seconds: float = 30.0) -> int:
        """전송 확인(콜백)을 기다린다. 시한 안에 확인되지 않은 개수(pending)를 돌려준다.

        pending 은 유실이 아니다 — 메시지는 큐에 남아 MESSAGE_TIMEOUT_MS 까지 재시도되고,
        그때 실패로 확정되면 _on_delivery 가 failed 로 센다.
        """
        remaining = self._producer.flush(timeout_seconds)
        if remaining:
            log.warning("%d 건 전송 확인 대기 중 (브로커 응답 없음, 큐에서 재시도)", remaining)
        return remaining
