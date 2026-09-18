"""대기 상한과 단계별 시간 관측만 검증한다. 모델 묶음 없이 돌도록 serving_runtime 만 불러온다."""
import asyncio
from pathlib import Path
import sys
from threading import Event

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import pytest

from serving_runtime import InferenceQueue, QueueBusy, RuntimeSettings


async def until(predicate):
    """시간으로 순서를 추측하지 않고 실제 입장 상태가 될 때까지 제한된 시간 동안 기다린다."""
    async with asyncio.timeout(3):
        while not predicate():
            await asyncio.sleep(0.001)

def test_queue_wait_expires_fast_and_reports_busy():
    """대기 상한을 넘기면 계산을 기다리지 않고 바로 MODEL_BUSY 로 돌려준다.

    운영 대기 상한은 2초다. 호출자(백엔드)의 호출 제한보다 짧아야 밀린 요청이 먼저 실패하고
    호출자가 남은 예산을 과거 평균 계산에 쓸 수 있다. 5초였을 때는 호출자가 먼저 끊어
    양쪽 모두 헛수고였다(2026-09-18).
    """
    async def scenario():
        queue = InferenceQueue(RuntimeSettings(max_waiting=4, wait_seconds=0.2))
        release = Event()
        started = []

        def busy():
            started.append(0)
            assert release.wait(3)

        first = asyncio.create_task(queue.run(busy))
        try:
            await until(lambda: started == [0])
            waited = asyncio.get_running_loop().time()
            with pytest.raises(QueueBusy):
                await queue.run(started.append, 1)
            elapsed = asyncio.get_running_loop().time() - waited
            # 상한 직후에 실패해야 한다. 계산이 끝날 때까지 붙잡고 있으면 안 된다.
            assert 0.2 <= elapsed < 1.0
            assert started == [0]
        finally:
            release.set()
        await first

    asyncio.run(scenario())


def test_predict_records_stage_timings_without_changing_values():
    """관측용 단계 시간이 채워지고 예측값 자체는 달라지지 않는다."""
    class StubPredictor:
        def __init__(self):
            self.lock = __import__('threading').Lock()
            self.calls = 0

        def predict(self, request, timings=None):
            import time as clock
            self.calls += 1
            if timings is not None:
                timings['started_at'] = clock.perf_counter()
                timings['segments'] = len(request['segments'])
                timings['feature_seconds'] = 0.03
                timings['inference_seconds'] = 0.01
            return {'results': [{'segment_key': s} for s in request['segments']]}

    async def scenario():
        queue = InferenceQueue(RuntimeSettings(max_waiting=1, wait_seconds=1))
        stub = StubPredictor()
        timings = {}
        request = {'segments': ['e0', 'e1']}
        response = await queue.run(stub.predict, request, timings)

        assert stub.calls == 1
        assert [r['segment_key'] for r in response['results']] == ['e0', 'e1']
        assert timings['segments'] == 2
        assert timings['feature_seconds'] == 0.03
        assert timings['inference_seconds'] == 0.01
        assert 'started_at' in timings

    asyncio.run(scenario())
