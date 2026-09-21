"""모든 수집 소스가 공유하는 타입.

소스 모듈은 `SOURCE = SourceSpec(...)` 하나를 노출한다.
fetch() 는 외부 API 를 호출해 Record 목록을 돌려주고, 이벤트 봉투(eventId, collectedAt 등)는
events.build_event 가 계약(docs/contracts/events)에 맞춰 채운다.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any, Callable, Iterable, List, Literal

import httpx
from config import Settings

# python:slim 이미지에 tzdata 가 없을 수 있어 고정 오프셋으로 둔다 (한국은 DST 없음)
KST = timezone(timedelta(hours=9), name="Asia/Seoul")


def now_kst() -> datetime:
    return datetime.now(tz=KST)


@dataclass(frozen=True)
class Record:
    """소스가 만든 원본 레코드 하나. 토픽 한 메시지가 된다."""

    key: str  # Kafka 메시지 키 (파티션 분배 기준: 노선, 격자, 역 등)
    event_type: str  # 예: weather.observation, bus.position
    occurred_at: datetime  # 원본 발생 시각 (tz-aware)
    payload: dict[str, Any]
    region: str | None = None  # transport 계약에만 존재
    location: tuple[float, float] | None = None  # (latitude, longitude) 원본 좌표
    segment_ids: list[str] | None = None  # 영향받는 뚜비 내부 구간 ID. 수집기가 알 수 있을 때만 채운다


class PollBatch(list):
    """레코드 목록과 poll 완전성 근거를 함께 전달하는 하위 호환 list."""

    def __init__(
        self,
        records: Iterable[Record] = (),
        *,
        status: str = "SUCCESS",
        complete_snapshot: bool = False,
        page_count: int = 0,
        scope_key: str = "configured-scope",
        error_code: str | None = None,
    ) -> None:
        super().__init__(records)
        self.status = status
        self.complete_snapshot = complete_snapshot
        self.page_count = page_count
        self.scope_key = scope_key
        self.error_code = error_code


# Collector 이미지는 Python 3.8도 지원하므로 런타임에 평가되는 타입 별칭은
# PEP 585의 list[...] 대신 typing.List를 사용한다.
FetchFn = Callable[[Settings, httpx.Client], List[Record]]


@dataclass(frozen=True)
class SourceSpec:
    name: str  # 이벤트의 source 필드 값
    topic: str  # docs/contracts/topics.yaml 의 토픽명
    schema: Literal["transport", "city"]  # 어느 이벤트 계약을 따르는지
    default_interval_seconds: int
    fetch: FetchFn
