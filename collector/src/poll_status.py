"""수집 사이클 결과를 정상 데이터와 분리해 발행하는 계약."""

from __future__ import annotations

import hashlib
import json
from datetime import datetime
from typing import Any
from uuid import uuid4

import pydantic
from pydantic import BaseModel, ConfigDict, Field

from sources.base import KST


if int(pydantic.VERSION.split(".", 1)[0]) >= 2:
    class StrictModel(BaseModel):
        model_config = ConfigDict(extra="forbid")
else:
    class StrictModel(BaseModel):
        class Config:
            extra = "forbid"


class PollStatusEvent(StrictModel):

    pollId: str
    source: str = Field(min_length=1)
    scopeKey: str = Field(min_length=1)
    startedAt: datetime
    finishedAt: datetime
    status: str
    completeSnapshot: bool
    pageCount: int = Field(ge=0)
    rowCount: int = Field(ge=0)
    errorCode: str | None = None
    nextAllowedAt: datetime | None = None
    contentHash: str = Field(pattern=r"^[a-f0-9]{64}$")


def build_poll_status(
    *,
    source: str,
    scope_key: str,
    started_at: datetime,
    finished_at: datetime,
    status: str,
    row_count: int,
    complete_snapshot: bool = False,
    page_count: int = 0,
    error_code: str | None = None,
    next_allowed_at: datetime | None = None,
) -> dict[str, Any]:
    if started_at.tzinfo is None or finished_at.tzinfo is None:
        raise ValueError("poll status timestamps must be timezone-aware")
    stable = {
        "source": source,
        "scopeKey": scope_key,
        "startedAt": started_at.astimezone(KST).isoformat(),
        "finishedAt": finished_at.astimezone(KST).isoformat(),
        "status": status,
        "completeSnapshot": complete_snapshot,
        "pageCount": page_count,
        "rowCount": row_count,
        "errorCode": error_code,
        "nextAllowedAt": next_allowed_at.astimezone(KST).isoformat() if next_allowed_at else None,
    }
    digest = hashlib.sha256(
        json.dumps(stable, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()
    event = PollStatusEvent(pollId=str(uuid4()), contentHash=digest, **stable)
    if hasattr(event, "model_dump"):
        return event.model_dump(mode="json", exclude_none=True)
    return json.loads(event.json(exclude_none=True))
