"""TOPIS 최신 이벤트 키를 공지의 최신 완전 추출 결과와 맞춘다."""

from __future__ import annotations

from datetime import datetime, timezone

KEY_PREFIX = "traffic:latest:topis-event:"
AUTHORITATIVE_STATUSES = {"VALID", "PARTIAL", "NOT_RELEVANT"}

_DELETE_IF_NOT_NEWER_LUA = """
local current = redis.call('GET', KEYS[1])
if not current then return 0 end
local ok, value = pcall(cjson.decode, current)
if not ok or not value then return 0 end
local current_collected = tostring(value['_latestOrderAt'] or value['collectedAt'] or '')
if current_collected == '' or current_collected > ARGV[1] then return 0 end
return redis.call('DEL', KEYS[1])
"""


def _value(row, name):
    if isinstance(row, dict):
        return row.get(name)
    return getattr(row, name, None)


def _timestamp(value):
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        return parsed if parsed.tzinfo is not None else None
    except (TypeError, ValueError):
        return None


def build_reconciliation_plans(rows, now=None):
    """공지별 가장 최신 추출이 신뢰 가능한 경우에만 유지할 Redis event ID를 계산한다."""
    current = now or datetime.now(timezone.utc)
    grouped = {}
    for row in rows:
        notice_id = str(_value(row, "noticeId") or "").strip()
        collected_at = _timestamp(_value(row, "collectedAt"))
        if not notice_id or collected_at is None:
            continue
        previous = grouped.get(notice_id)
        if previous is None or collected_at > previous[0]:
            grouped[notice_id] = (collected_at, [row])
        elif collected_at == previous[0]:
            previous[1].append(row)

    plans = []
    for notice_id, (collected_at, latest_rows) in grouped.items():
        statuses = {_value(row, "processingStatus") for row in latest_rows}
        if not statuses or not statuses.issubset(AUTHORITATIVE_STATUSES):
            continue
        active = set()
        for row in latest_rows:
            event_id = str(_value(row, "eventId") or "").strip()
            status = _value(row, "processingStatus")
            event_status = _value(row, "eventStatus")
            valid_until = _timestamp(_value(row, "validUntil"))
            if status in {"VALID", "PARTIAL"} and event_id and event_status != "ENDED":
                # 유효시각이 깨진 행은 기존 키를 성급하게 지우지 않고 TTL 정리에 맡긴다.
                if valid_until is None or valid_until > current:
                    active.add(event_id)
        plans.append({"noticeId": notice_id, "collectedAt": collected_at.isoformat(), "activeEventIds": sorted(active)})
    return plans


def _escape_glob(value):
    result = ""
    for char in value:
        result += "\\" + char if char in "*?[]\\" else char
    return result


def reconcile_topis_latest(plans, host, port, password):
    """종료·만료·최신 공지에서 사라진 이벤트를 최신성 비교 후 Redis에서 제거한다."""
    if not plans:
        return 0
    import json

    import redis

    client = redis.Redis(host=host, port=port, password=password, socket_timeout=5, socket_connect_timeout=5)
    deleted = 0
    try:
        for plan in plans:
            active = set(plan["activeEventIds"])
            pattern = KEY_PREFIX + _escape_glob(plan["noticeId"]) + "-*"
            for key in client.scan_iter(match=pattern, count=100):
                raw = client.get(key)
                try:
                    stored = json.loads(raw) if raw else None
                except (TypeError, ValueError):
                    stored = None
                if not isinstance(stored, dict) or str(stored.get("noticeId") or "") != plan["noticeId"]:
                    continue
                if str(stored.get("eventId") or "") in active:
                    continue
                deleted += int(client.eval(_DELETE_IF_NOT_NEWER_LUA, 1, key, plan["collectedAt"]) or 0)
    finally:
        client.close()
    return deleted
