"""실시간·백필이 공유하는 교통 돌발 신선도와 거리 정책."""

from dataclasses import dataclass
from typing import Mapping


class TrafficIncidentPolicyError(ValueError):
    """잡을 시작할 수 없는 교통 돌발 정책 설정 오류."""


@dataclass(frozen=True)
class TrafficIncidentPolicy:
    """검토된 정책 값. 이름 없는 튜플의 위치에 의존하지 않는다."""

    stale_seconds: int
    direct_tolerance_meters: int
    nearby_max_meters: int


def _integer(env: Mapping[str, str], name: str, default: int) -> int:
    raw = env.get(name, str(default))
    try:
        return int(str(raw).strip())
    except (TypeError, ValueError) as exc:
        raise TrafficIncidentPolicyError(f"{name} must be an integer") from exc


def policy(env: Mapping[str, str]) -> TrafficIncidentPolicy:
    """환경변수를 이름 있는 정책으로 검증한다. 오류는 잡 시작 전에 호출자에게 전달한다."""
    result = TrafficIncidentPolicy(
        stale_seconds=_integer(env, "TRAFFIC_INCIDENT_STALE_SECONDS", 2700),
        direct_tolerance_meters=_integer(env, "TRAFFIC_LINK_COORD_TOLERANCE_METERS", 200),
        nearby_max_meters=_integer(env, "TRAFFIC_NEARBY_MAX_METERS", 100),
    )
    ranges = (
        ("TRAFFIC_INCIDENT_STALE_SECONDS", result.stale_seconds, 900, 86400),
        (
            "TRAFFIC_LINK_COORD_TOLERANCE_METERS",
            result.direct_tolerance_meters,
            10,
            2000,
        ),
        ("TRAFFIC_NEARBY_MAX_METERS", result.nearby_max_meters, 10, 1000),
    )
    for name, value, minimum, maximum in ranges:
        if not minimum <= value <= maximum:
            raise TrafficIncidentPolicyError(f"{name} must be between {minimum} and {maximum}")
    return result
