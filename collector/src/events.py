"""Kafka 이벤트 봉투. docs/contracts/events/*.schema.json 과 필드가 1:1 로 대응한다.

- additionalProperties: false 에 맞춰 extra="forbid"
- 시간은 ISO 8601 + Asia/Seoul 오프셋(+09:00)
- 위치는 원본 좌표(location)와 뚜비 내부 구간(segmentIds) 두 형태. 수집기가 변환할 수 있으면
  segmentIds 를 채우고, 아니면 Spark 전처리가 채운다. 둘 다 선택 필드.
- 계약 파일과 어긋나지 않는지는 tests/test_events.py 가 검사한다
"""

from __future__ import annotations

from datetime import datetime
import hashlib
import json
import re
from typing import Any, Dict, List, Optional
from uuid import uuid4

import pydantic
from pydantic import BaseModel, ConfigDict, Field

from sources.base import KST, Record, SourceSpec


if int(pydantic.VERSION.split(".", 1)[0]) >= 2:
    class StrictModel(BaseModel):
        model_config = ConfigDict(extra="forbid")
else:
    class StrictModel(BaseModel):
        class Config:
            extra = "forbid"


class Location(StrictModel):

    latitude: float
    longitude: float


class TransportEvent(StrictModel):
    """docs/contracts/events/transport-event.schema.json"""

    eventId: str
    eventType: str = Field(min_length=1)
    source: str = Field(min_length=1)
    region: Optional[str] = None
    occurredAt: datetime
    collectedAt: datetime
    sourceGeneratedAt: Optional[datetime] = None
    timestampBasis: str
    providerNamespace: Optional[str] = None
    providerIds: Dict[str, str] = Field(default_factory=dict)
    mappingVersion: Optional[str] = None
    masterVersion: Optional[str] = None
    validUntil: Optional[datetime] = None
    contentHash: str = Field(pattern=r"^[a-f0-9]{64}$")
    qualityFlags: List[str] = Field(default_factory=list)
    missingFields: List[str] = Field(default_factory=list)
    rejectionReasons: List[str] = Field(default_factory=list)
    location: Optional[Location] = None
    segmentIds: Optional[List[str]] = None
    payload: Dict[str, Any]


class CityEvent(StrictModel):
    """docs/contracts/events/city-event.schema.json"""

    eventId: str
    eventType: str = Field(min_length=1)
    source: str = Field(min_length=1)
    occurredAt: datetime
    collectedAt: datetime
    sourceGeneratedAt: Optional[datetime] = None
    timestampBasis: str
    providerNamespace: Optional[str] = None
    providerIds: Dict[str, str] = Field(default_factory=dict)
    mappingVersion: Optional[str] = None
    masterVersion: Optional[str] = None
    validUntil: Optional[datetime] = None
    contentHash: str = Field(pattern=r"^[a-f0-9]{64}$")
    qualityFlags: List[str] = Field(default_factory=list)
    missingFields: List[str] = Field(default_factory=list)
    rejectionReasons: List[str] = Field(default_factory=list)
    location: Optional[Location] = None
    segmentIds: Optional[List[str]] = None
    payload: Dict[str, Any]


SCHEMA_MODELS = {"transport": TransportEvent, "city": CityEvent}


def _model_json(model: BaseModel) -> Dict[str, Any]:
    """Collector 이미지의 Pydantic v1과 개발환경 v2를 모두 지원한다."""
    if hasattr(model, "model_dump"):
        return model.model_dump(mode="json", exclude_none=True)
    return json.loads(model.json(exclude_none=True))


def _to_kst(dt: datetime) -> datetime:
    if dt.tzinfo is None:
        raise ValueError("occurredAt/collectedAt 은 tz-aware 여야 합니다")
    return dt.astimezone(KST)


def _metadata(record: Record, collected_at: datetime) -> Dict[str, Any]:
    """기존 payload를 보존하면서 모든 source에 공통 소비 메타데이터를 만든다."""
    payload = record.payload
    legacy_basis = str(payload.get("timestampBasis") or "").lower()
    if legacy_basis == "received":
        timestamp_basis = "RECEIVED_ONLY"
        generated_at = None
    elif record.event_type in {"weather.observation", "traffic.incident", "topis.notice", "subway.alert"}:
        timestamp_basis = "PROVIDER_OCCURRED"
        generated_at = record.occurred_at
    else:
        timestamp_basis = "PROVIDER_GENERATED"
        generated_at = record.occurred_at

    provider_ids = {
        key: str(value)
        for key, value in payload.items()
        if key.startswith("provider")
        and key not in {"providerNamespace", "providerLinkNamespace", "providerFields"}
        and key.endswith("Id")
        and value not in (None, "")
    }
    provider_namespace = payload.get("providerNamespace") or payload.get("providerLinkNamespace")
    mapping = payload.get("canonicalMapping") if isinstance(payload.get("canonicalMapping"), dict) else {}
    content_hash = payload.get("contentHash")
    if not isinstance(content_hash, str) or re.fullmatch(r"[a-f0-9]{64}", content_hash) is None:
        canonical = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"), default=str)
        content_hash = hashlib.sha256(canonical.encode("utf-8")).hexdigest()

    result: Dict[str, Any] = {
        "sourceGeneratedAt": _to_kst(generated_at) if generated_at else None,
        "timestampBasis": timestamp_basis,
        "providerNamespace": str(provider_namespace) if provider_namespace else None,
        "providerIds": provider_ids,
        "mappingVersion": mapping.get("mappingVersion") or mapping.get("crosswalkSha256"),
        "masterVersion": mapping.get("masterVersion") or payload.get("masterVersion"),
        "contentHash": content_hash,
        "qualityFlags": sorted(set(payload.get("qualityFlags") or [])),
        "missingFields": sorted(set(payload.get("missingFields") or [])),
        "rejectionReasons": sorted(set(payload.get("rejectionReasons") or [])),
    }
    valid_until = payload.get("validUntil")
    if valid_until:
        try:
            parsed = datetime.fromisoformat(str(valid_until).replace("Z", "+00:00"))
            result["validUntil"] = _to_kst(parsed)
        except (TypeError, ValueError):
            result["qualityFlags"].append("INVALID_VALID_UNTIL")
    return result


def build_event(spec: SourceSpec, record: Record, collected_at: datetime) -> Dict[str, Any]:
    """Record 를 계약에 맞는 이벤트 dict 로 만든다 (JSON 직렬화 가능한 형태)."""
    location = Location(latitude=record.location[0], longitude=record.location[1]) if record.location else None
    segment_ids = sorted(set(record.segment_ids)) if record.segment_ids else None  # uniqueItems
    common = {
        "eventId": str(uuid4()),
        "eventType": record.event_type,
        "source": spec.name,
        "occurredAt": _to_kst(record.occurred_at),
        "collectedAt": _to_kst(collected_at),
        "location": location,
        "segmentIds": segment_ids,
        "payload": record.payload,
        **_metadata(record, collected_at),
    }
    if spec.schema == "transport":
        event = TransportEvent(**common, region=record.region)
    else:
        event = CityEvent(**common)
    return _model_json(event)
