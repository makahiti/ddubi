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
    """produce 는 큐에 쌓기만 하고, flush 때 broker_up 이면 성공 콜백을 부르고 아니면 그대로 남긴다."""

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
        for topic, key, cb in self.queue:
            cb(None, FakeMessage(topic, key))
        self.queue.clear()
        return 0

    def list_topics(self, timeout):
        self.list_topics_calls += 1
        if self.list_topics_calls <= self.list_topics_failures:
            raise RuntimeError("broker down")
        return {}


def test_flush_confirms_sent_when_broker_up():
    fake = FakeProducer(broker_up=True)
    pub = Publisher("kafka:9092", "test", producer=fake)
    pub.publish("city.raw", "k1", {"a": 1})
    pub.publish("city.raw", "k2", {"a": 2})
    assert pub.sent == 0  # 콜백은 flush 전엔 오지 않는다
    assert pub.flush(1.0) == 0
    assert (pub.sent, pub.failed) == (2, 0)


def test_flush_reports_pending_not_failed_when_broker_down():
    fake = FakeProducer(broker_up=False)
    pub = Publisher("kafka:9092", "test", producer=fake)
    pub.publish("city.raw", "k1", {"a": 1})
    assert pub.flush(1.0) == 1  # 확인 대기 1건
    assert (pub.sent, pub.failed) == (0, 0)  # 유실로 세지 않는다 — 큐에서 재시도 중


def test_wait_ready_retries_until_broker_answers():
    fake = FakeProducer(list_topics_failures=2)
    pub = Publisher("kafka:9092", "test", producer=fake)
    slept = []
    assert pub.wait_ready(timeout_seconds=60, poll_seconds=1.0, sleep=slept.append) is True
    assert fake.list_topics_calls == 3
    assert slept == [1.0, 1.0]


def test_wait_ready_gives_up_after_timeout(monkeypatch):
    fake = FakeProducer(list_topics_failures=10_000)
    pub = Publisher("kafka:9092", "test", producer=fake)
    clock = iter([0.0, 100.0, 100.0, 100.0])  # deadline = 0 + 30 → 첫 실패 뒤 확인에서 초과
    monkeypatch.setattr("publisher.time.monotonic", lambda: next(clock))
    assert pub.wait_ready(timeout_seconds=30, poll_seconds=1.0, sleep=lambda _s: None) is False
    assert fake.list_topics_calls == 1


def test_message_timeout_covers_one_cycle():
    assert MESSAGE_TIMEOUT_MS >= 600_000
