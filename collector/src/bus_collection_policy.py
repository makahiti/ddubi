"""Time-window and route-panel policy for short-term bus training collection."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime


@dataclass(frozen=True)
class BusCollectionPlan:
    targets: list[str]
    interval_seconds: int
    scope: str


def _minutes(value: str) -> int:
    hour, minute = (int(part) for part in value.split(":"))
    if not (0 <= hour <= 23 and 0 <= minute <= 59):
        raise ValueError(f"invalid bus collection time: {value}")
    return hour * 60 + minute


def _windows(raw: str) -> list[tuple[int, int]]:
    result = []
    for item in raw.split(","):
        start, end = (part.strip() for part in item.split("-", 1))
        result.append((_minutes(start), _minutes(end)))
    return result


def _inside(minute: int, windows: list[tuple[int, int]]) -> bool:
    return any(start <= minute < end for start, end in windows)


def _positive(extra: dict[str, str], name: str, default: int) -> int:
    value = int(extra.get(name, str(default)))
    if value <= 0:
        raise ValueError(f"{name} must be positive")
    return value


def plan_bus_collection(name: str, targets: list[str], now: datetime, extra: dict[str, str]) -> BusCollectionPlan:
    """Return the routes due in this cycle and the desired global cadence.

    Arrival keeps broad coverage. Location uses a rotating dense panel during
    weekday peaks so consecutive vehicle observations can form useful labels.
    """

    minute = now.hour * 60 + now.minute
    weekday = now.weekday() < 5
    peak = weekday and _inside(
        minute,
        _windows(extra.get("BUS_WEEKDAY_PEAK_WINDOWS", "06:30-09:30,16:30-20:00")),
    )
    service = _inside(minute, _windows(extra.get("BUS_SERVICE_WINDOWS", "05:30-23:30")))

    if name == "bus_arrival":
        if peak:
            interval = _positive(extra, "BUS_ARRIVAL_PEAK_INTERVAL_SECONDS", 300)
            scope = "weekday-peak-all-routes"
        elif weekday and service:
            interval = _positive(extra, "BUS_ARRIVAL_OFFPEAK_INTERVAL_SECONDS", 900)
            scope = "weekday-offpeak-all-routes"
        elif service:
            interval = _positive(extra, "BUS_ARRIVAL_WEEKEND_INTERVAL_SECONDS", 1800)
            scope = "weekend-all-routes"
        else:
            interval = _positive(extra, "BUS_ARRIVAL_OVERNIGHT_INTERVAL_SECONDS", 1800)
            scope = "overnight-all-routes"
        return BusCollectionPlan(list(targets), interval, scope)

    if name != "bus_location":
        raise ValueError(f"unsupported bus source: {name}")

    if peak:
        dense_interval = _positive(extra, "BUS_LOCATION_DENSE_INTERVAL_SECONDS", 150)
        sparse_interval = _positive(extra, "BUS_LOCATION_SPARSE_INTERVAL_SECONDS", 300)
        panel_size = min(_positive(extra, "BUS_LOCATION_DENSE_ROUTE_COUNT", 50), len(targets))
        first, second = list(targets[:panel_size]), list(targets[panel_size:])
        if not second:
            dense, sparse = first, []
        else:
            dense, sparse = (first, second) if now.date().toordinal() % 2 == 0 else (second, first)
        # The collector wakes at the dense cadence. Include the sparse panel in
        # the first dense bucket of each sparse wall-clock interval.
        seconds_of_day = minute * 60 + now.second
        sparse_due = seconds_of_day % sparse_interval < dense_interval
        selected = dense + (sparse if sparse_due else [])
        return BusCollectionPlan(selected, dense_interval, "weekday-peak-rotating-panel")

    if weekday and service:
        interval = _positive(extra, "BUS_LOCATION_OFFPEAK_INTERVAL_SECONDS", 900)
        scope = "weekday-offpeak-all-routes"
    elif service:
        interval = _positive(extra, "BUS_LOCATION_WEEKEND_INTERVAL_SECONDS", 900)
        scope = "weekend-all-routes"
    else:
        interval = _positive(extra, "BUS_LOCATION_OVERNIGHT_INTERVAL_SECONDS", 3600)
        scope = "overnight-all-routes"
    return BusCollectionPlan(list(targets), interval, scope)
