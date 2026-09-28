"""Time-window policy for Seoul traffic incident polling."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime


@dataclass(frozen=True)
class TrafficIncidentPlan:
    interval_seconds: int
    scope: str


def _minutes(value: str, *, allow_end_of_day: bool = False) -> int:
    hour, minute = (int(part) for part in value.split(":"))
    if allow_end_of_day and hour == 24 and minute == 0:
        return 24 * 60
    if not (0 <= hour <= 23 and 0 <= minute <= 59):
        raise ValueError(f"invalid traffic incident collection time: {value}")
    return hour * 60 + minute


def _windows(raw: str) -> list[tuple[int, int]]:
    result = []
    for item in raw.split(","):
        start, end = (part.strip() for part in item.split("-", 1))
        result.append((_minutes(start), _minutes(end, allow_end_of_day=True)))
    return result


def _inside(minute: int, windows: list[tuple[int, int]]) -> bool:
    return any(start <= minute < end if start < end else minute >= start or minute < end for start, end in windows)


def _positive(extra: dict[str, str], name: str, default: int) -> int:
    value = int(extra.get(name, str(default)))
    if value <= 0:
        raise ValueError(f"{name} must be positive")
    return value


def plan_traffic_incident(now: datetime, extra: dict[str, str]) -> TrafficIncidentPlan:
    """Return the polling cadence for the current KST time window."""

    minute = now.hour * 60 + now.minute
    weekday = now.weekday() < 5
    active = _inside(
        minute,
        _windows(extra.get("TRAFFIC_INCIDENT_ACTIVE_WINDOWS", "05:30-23:30")),
    )
    peak = weekday and _inside(
        minute,
        _windows(extra.get("TRAFFIC_INCIDENT_WEEKDAY_PEAK_WINDOWS", "06:30-09:30,16:30-20:00")),
    )

    if peak:
        return TrafficIncidentPlan(
            _positive(extra, "TRAFFIC_INCIDENT_PEAK_INTERVAL_SECONDS", 300),
            "weekday-peak-all-current",
        )
    if active:
        return TrafficIncidentPlan(
            _positive(extra, "TRAFFIC_INCIDENT_OFFPEAK_INTERVAL_SECONDS", 600),
            "active-offpeak-all-current" if weekday else "weekend-all-current",
        )
    return TrafficIncidentPlan(
        _positive(extra, "TRAFFIC_INCIDENT_OVERNIGHT_INTERVAL_SECONDS", 900),
        "overnight-all-current",
    )
