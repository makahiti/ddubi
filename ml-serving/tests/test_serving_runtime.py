"""혼잡·취소·실패 시 계산 중첩과 슬롯 누수를 막고, health 응답이 멈추지 않는지 검증한다."""

import asyncio
import sys
from pathlib import Path
from threading import Event

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import httpx
import pytest
from app import create_app
from serving_runtime import InferenceQueue, QueueBusy, RuntimeSettings


async def until(predicate):
    """시간으로 순서를 추측하지 않고 실제 입장 상태가 될 때까지 제한된 시간 동안 기다린다."""
    async with asyncio.timeout(3):
        while not predicate():
            await asyncio.sleep(0.001)


def test_fifo_full_and_release_after_failure():
    async def scenario():
        queue = InferenceQueue(RuntimeSettings(max_waiting=2, wait_seconds=2))
        release = Event()
        order = []

        def first():
            order.append(0)
            assert release.wait(3)
            raise ValueError("test failure")

        first_task = asyncio.create_task(queue.run(first))
        try:
            await until(lambda: order == [0])
            second = asyncio.create_task(queue.run(order.append, 1))
            await until(lambda: len(queue.waiters) == 1)
            third = asyncio.create_task(queue.run(order.append, 2))
            await until(lambda: len(queue.waiters) == 2)
            with pytest.raises(QueueBusy):
                await queue.run(order.append, 3)
        finally:
            release.set()
        with pytest.raises(ValueError):
            await first_task
        await asyncio.gather(second, third)
        assert order == [0, 1, 2]
        assert not queue.active
        await queue.close()

    asyncio.run(scenario())


def test_wait_timeout_never_runs_expired_work():
    async def scenario():
        queue = InferenceQueue(RuntimeSettings(wait_seconds=0.02))
        release = Event()
        called = []
        first = asyncio.create_task(queue.run(release.wait, 3))
        try:
            await until(lambda: queue.active)
            with pytest.raises(QueueBusy):
                await queue.run(called.append, "expired")
            assert not queue.waiters
        finally:
            release.set()
        await first
        assert called == []
        await queue.run(called.append, "next")
        assert called == ["next"]

    asyncio.run(scenario())


@pytest.mark.parametrize("max_waiting,wait_seconds", [(0, 5), (4, 0)])
def test_disabled_wait_rejects_only_when_active(max_waiting, wait_seconds):
    async def scenario():
        queue = InferenceQueue(RuntimeSettings(max_waiting=max_waiting, wait_seconds=wait_seconds))
        release = Event()
        first = asyncio.create_task(queue.run(release.wait, 3))
        try:
            await until(lambda: queue.active)
            with pytest.raises(QueueBusy):
                await queue.run(lambda: 1)
        finally:
            release.set()
        await first
        assert await queue.run(lambda: 1) == 1

    asyncio.run(scenario())


def test_cancellation_does_not_overlap_active_inference_or_leak_waiter():
    async def scenario():
        queue = InferenceQueue(RuntimeSettings())
        release = Event()
        first = asyncio.create_task(queue.run(release.wait, 3))
        try:
            await until(lambda: queue.active)
            waiting = asyncio.create_task(queue.run(lambda: "cancelled"))
            await until(lambda: len(queue.waiters) == 1)
            waiting.cancel()
            with pytest.raises(asyncio.CancelledError):
                await waiting
            assert not queue.waiters
            first.cancel()
            with pytest.raises(asyncio.CancelledError):
                await first
            assert queue.active  # 요청 task가 사라져도 실제 계산이 끝나기 전에는 점유한다.
            next_task = asyncio.create_task(queue.run(lambda: "next"))
            await until(lambda: len(queue.waiters) == 1)
            assert not next_task.done()
        finally:
            release.set()
        assert await next_task == "next"
        await queue.close()
        assert not queue.active

    asyncio.run(scenario())


def test_shutdown_rejects_waiters_and_waits_for_active_work():
    async def scenario():
        queue = InferenceQueue(RuntimeSettings())
        release = Event()
        first = asyncio.create_task(queue.run(release.wait, 3))
        try:
            await until(lambda: queue.active)
            waiting = asyncio.create_task(queue.run(lambda: 2))
            await until(lambda: len(queue.waiters) == 1)
            closing = asyncio.create_task(queue.close())
            with pytest.raises(QueueBusy):
                await waiting
            assert not closing.done()
            with pytest.raises(QueueBusy):
                await queue.run(lambda: 3)
        finally:
            release.set()
        await asyncio.gather(first, closing)
        assert not queue.active

    asyncio.run(scenario())


def test_cancellation_immediately_after_slot_handoff_returns_slot():
    async def scenario():
        queue = InferenceQueue(RuntimeSettings())
        queue.active = True
        task = asyncio.create_task(queue.run(lambda: pytest.fail("cancelled work ran")))
        await until(lambda: len(queue.waiters) == 1)
        queue._release()
        task.cancel()  # 양도 직후, 대기 task가 재개되기 전에 취소한다.
        with pytest.raises(asyncio.CancelledError):
            await task
        assert not queue.active and not queue.waiters
        assert await queue.run(lambda: "recovered") == "recovered"

    asyncio.run(scenario())


def test_http_queue_limit_and_health_while_model_runs():
    async def scenario():
        release = Event()

        class Model:
            bundle = {"missing_files": []}

            def metadata(self):
                return {"supported_from": "2000-01-01", "supported_until": "2100-01-01"}

            def predict(self, request, timings=None):
                release.wait(3)
                raise RuntimeError("not exposed")

        app = create_app(Model(), RuntimeSettings(max_waiting=1))
        body = {
            "request_id": "queue-test",
            "segments": [
                {
                    "segment_key": "1",
                    "provider_route_id": "001",
                    "provider_from_stop_id": "002",
                    "provider_to_stop_id": "003",
                    "from_stop_sequence": 1,
                    "service_date": "2026-09-10",
                    "hour_slot": 9,
                }
            ],
        }
        async with app.router.lifespan_context(app):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
                first = asyncio.create_task(client.post("/v1/bus/predictions:batch", json=body))
                try:
                    await until(lambda: app.state.inference_queue.active)
                    second = asyncio.create_task(client.post("/v1/bus/predictions:batch", json=body))
                    await until(lambda: len(app.state.inference_queue.waiters) == 1)
                    overflow = await client.post("/v1/bus/predictions:batch", json=body)
                    assert overflow.status_code == 503
                    assert overflow.json() == {"detail": "MODEL_BUSY"}
                    for path in ["/health/live", "/health/ready"]:
                        response = await asyncio.wait_for(client.get(path), 1)
                        assert response.status_code == 200
                finally:
                    release.set()
                results = await asyncio.gather(first, second)
                assert all(r.json() == {"detail": "MODEL_UNAVAILABLE"} for r in results)

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "env",
    [
        {"ML_MAX_WAITING_REQUESTS": "-1"},
        {"ML_MAX_WAITING_REQUESTS": "33"},
        {"ML_QUEUE_WAIT_SECONDS": "nan"},
        {"ML_QUEUE_WAIT_SECONDS": "inf"},
        {"ML_QUEUE_WAIT_SECONDS": "-1"},
        {"ML_REFERENCE_CACHE_MIB": "-1"},
        {"ML_REFERENCE_CACHE_MIB": "4097"},
        {"ML_MAX_WAITING_REQUESTS": "1.2"},
    ],
)
def test_invalid_runtime_settings_fail_at_startup(env):
    with pytest.raises(ValueError):
        RuntimeSettings.from_env(env)


def test_request_logging_is_enabled_at_info_level():
    """모듈 로거가 기본 WARNING 이면 단계별 시간 기록이 어디에도 출력되지 않는다.

    2026-09-18 배포에서 실제로 한 줄도 남지 않아, 기동 시 INFO 로 올리고 핸들러를 보장한다.
    """
    import importlib
    import logging

    module = importlib.import_module("app")
    module._enable_request_logging()

    logger = logging.getLogger(module.__name__)
    assert logger.level <= logging.INFO
    assert logger.isEnabledFor(logging.INFO)
    assert logger.handlers or logging.getLogger().handlers
