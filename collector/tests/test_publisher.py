"""Publisher 의 전송 확인·브로커 대기 동작. 실제 Kafka 없이 가짜 프로듀서로 검사한다."""

from __future__ import annotations

from publisher import MESSAGE_TIMEOUT_MS, Publisher


class FakeMessage:
    def __init__(self, topic: str, key: bytes) -> None:
        self._topic, self._key = topic, key

    def topic(self) -> str:
        return self._topic

    def key(self) -> bytes:
        return self._key


class FakeProducer:
    """produce 는 큐에 쌓고, flush 때 broker_up 이면 성공 콜백을 호출한다."""

    def __init__(self, broker_up: bool = True, list_topics_failures: int = 0) -> None:
        self.broker_up = broker_up
        self.queue = []
        self.list_topics_failures = list_topics_failures
        self.list_topics_calls = 0

    def produce(self, topic, key, value, on_delivery):
        self.queue.append((topic, key, on_delivery))

    def poll(self, timeout):
        return 0

    def flush(self, timeout):
        if not self.broker_up:
            return len(self.queue)
        for topic, key, callback in self.queue:
            callback(None, FakeMessage(topic, key))
        self.queue.clear()
        return 0

    def list_topics(self, timeout):
        self.list_topics_calls += 1
        if self.list_topics_calls <= self.list_topics_failures:
            raise RuntimeError("broker down")
        return {}


def test_flush_confirms_sent_when_broker_up():
    fake = FakeProducer(broker_up=True)
    publisher = Publisher("kafka:9092", "test", producer=fake)
    publisher.publish("city.raw", "k1", {"a": 1})
    publisher.publish("city.raw", "k2", {"a": 2})
    assert publisher.sent == 0
    assert publisher.flush(1.0) == 0
    assert (publisher.sent, publisher.failed) == (2, 0)


def test_flush_reports_pending_not_failed_when_broker_down():
    fake = FakeProducer(broker_up=False)
    publisher = Publisher("kafka:9092", "test", producer=fake)
    publisher.publish("city.raw", "k1", {"a": 1})
    assert publisher.flush(1.0) == 1
    assert (publisher.sent, publisher.failed) == (0, 0)


def test_wait_ready_retries_until_broker_answers():
    fake = FakeProducer(list_topics_failures=2)
    publisher = Publisher("kafka:9092", "test", producer=fake)
    slept = []
    assert publisher.wait_ready(timeout_seconds=60, poll_seconds=1.0, sleep=slept.append) is True
    assert fake.list_topics_calls == 3
    assert slept == [1.0, 1.0]


def test_wait_ready_gives_up_after_timeout(monkeypatch):
    fake = FakeProducer(list_topics_failures=10_000)
    publisher = Publisher("kafka:9092", "test", producer=fake)
    clock = iter([0.0, 100.0, 100.0, 100.0])
    monkeypatch.setattr("publisher.time.monotonic", lambda: next(clock))
    assert publisher.wait_ready(
        timeout_seconds=30,
        poll_seconds=1.0,
        sleep=lambda _seconds: None,
    ) is False
    assert fake.list_topics_calls == 1


def test_message_timeout_covers_one_cycle():
    assert MESSAGE_TIMEOUT_MS >= 600_000
