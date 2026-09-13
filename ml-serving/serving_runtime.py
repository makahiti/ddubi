"""HTTP 추론 자원을 제한한다. 대기는 이벤트 루프, 실제 계산은 별도 스레드가 맡는다.

프로세스당 계산 한 건과 제한된 FIFO 대기만 허용한다. 대기 timeout은 계산 취소가
아니며, 호출자가 연결을 끊어도 실행 중인 계산이 끝날 때까지 자리를 점유한다.
요청 본문·ID를 로그나 장기 상태에 저장하지 않는다. 재시도는 호출자의 책임이다.
"""
import asyncio
from collections import deque
from dataclasses import dataclass
import math


class QueueBusy(RuntimeError):
    """대기열 초과·기한 만료·종료 중 상태를 외부 MODEL_BUSY로 변환한다."""


@dataclass(frozen=True)
class RuntimeSettings:
    """초기 자원 정책. 시간은 초, 캐시는 MiB이며 운영 SLA나 RSS 상한이 아니다."""

    max_waiting: int = 4
    wait_seconds: float = 5.0
    cache_mib: int = 256

    def __post_init__(self):
        # 설정 오류를 무제한 대기/메모리 할당으로 해석하지 않고 시작 단계에서 거부한다.
        if type(self.max_waiting) is not int or not 0 <= self.max_waiting <= 32:
            raise ValueError('ML_MAX_WAITING_REQUESTS must be 0..32')
        if not math.isfinite(self.wait_seconds) or not 0 <= self.wait_seconds <= 30:
            raise ValueError('ML_QUEUE_WAIT_SECONDS must be 0..30')
        if type(self.cache_mib) is not int or not 0 <= self.cache_mib <= 4096:
            raise ValueError('ML_REFERENCE_CACHE_MIB must be 0..4096')

    @classmethod
    def from_env(cls, env):
        """환경변수를 시작 시 한 번 읽는다. 0은 각각 대기 또는 캐시 비활성화다."""
        return cls(int(env.get('ML_MAX_WAITING_REQUESTS', '4')),
                   float(env.get('ML_QUEUE_WAIT_SECONDS', '5')),
                   int(env.get('ML_REFERENCE_CACHE_MIB', '256')))


class InferenceQueue:
    """한 이벤트 루프가 소유하는 FIFO. 모델에는 동시에 한 스레드만 진입한다.

HTTP 스레드풀에 들어가기 전에 입장을 제한해 숨은 스레드 대기를 만들지 않는다.
대기 중 취소는 큐에서 제거하고, 실행 중 취소는 계산 완료 시 슬롯을 반환한다.
"""

    def __init__(self, settings):
        self.settings = settings
        self.waiters = deque()
        self.active = False
        self.closed = False
        self.tasks = set()

    async def _acquire(self):
        if self.closed:
            raise QueueBusy()
        if not self.active:
            self.active = True
            return
        if len(self.waiters) >= self.settings.max_waiting or self.settings.wait_seconds == 0:
            raise QueueBusy()
        ticket = asyncio.get_running_loop().create_future()
        self.waiters.append(ticket)
        try:
            await asyncio.wait_for(asyncio.shield(ticket), self.settings.wait_seconds)
        except BaseException as error:
            # 양도 직후 timeout/취소가 겹쳐도 슬롯이 영구 점유되지 않도록 돌려준다.
            if ticket in self.waiters:
                self.waiters.remove(ticket)
            elif ticket.done() and not ticket.cancelled() and ticket.exception() is None:
                self._release()
            if isinstance(error, asyncio.TimeoutError):
                raise QueueBusy() from None
            raise

    def _release(self):
        if self.waiters:
            self.waiters.popleft().set_result(None)
        else:
            self.active = False

    async def run(self, function, *args):
        """대기 기한 내 순서가 온 요청만 계산한다. 계산 자체의 강제 종료는 제공하지 않는다."""
        await self._acquire()

        async def execute():
            try:
                return await asyncio.to_thread(function, *args)
            finally:
                self._release()

        # shield와 강한 참조로 HTTP task 취소가 실행 중인 모델 슬롯을 먼저 풀지 않게 한다.
        task = asyncio.create_task(execute())
        self.tasks.add(task)

        def completed(done):
            self.tasks.discard(done)
            if not done.cancelled():
                done.exception()  # 연결 종료 후 실패한 task도 원본 예외를 로그에 유출하지 않는다.

        task.add_done_callback(completed)
        return await asyncio.shield(task)

    async def close(self):
        """종료 시 새 요청·대기를 거절하고 이미 실행된 계산의 자원 반환을 기다린다."""
        self.closed = True
        while self.waiters:
            self.waiters.popleft().set_exception(QueueBusy())
        if self.tasks:
            await asyncio.gather(*self.tasks, return_exceptions=True)
