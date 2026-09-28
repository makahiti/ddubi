"""Shared credential pool for Seoul bus arrival and location APIs."""

from __future__ import annotations

from urllib.parse import unquote

from config import Settings

POOL_KEY_NAMES = ("BUS_API_KEY_A", "BUS_API_KEY_B")

LEGACY_KEY_NAMES = {
    "bus_arrival": "BUS_ARRIVAL_API_KEY",
    "bus_location": "BUS_LOCATION_API_KEY",
}


def configured_bus_pool(settings: Settings, source: str) -> list[tuple[str, str]]:
    """Return distinct A/B credentials shared by both Seoul bus datasets."""

    if source not in LEGACY_KEY_NAMES:
        raise ValueError(f"unsupported bus source: {source}")

    credentials = []
    seen = set()
    for slot, name in zip(("A", "B"), POOL_KEY_NAMES, strict=True):
        key = settings.api_keys.get(name)
        # Encoding/Decoding 표기는 같은 인증키다. URL decode 후 비교해야
        # 같은 키를 A/B 두 개로 잘못 세어 고빈도 정책을 켜지 않는다.
        normalized = unquote(key.strip()) if key else ""
        if normalized and normalized not in seen:
            credentials.append((slot, key))
            seen.add(normalized)
    return credentials


def bus_key_candidates(settings: Settings, source: str) -> list[tuple[str, str]]:
    """Use a source pool when present, otherwise its legacy single credential."""

    credentials = configured_bus_pool(settings, source)
    if credentials:
        return credentials
    return [("legacy", settings.api_key(LEGACY_KEY_NAMES[source]))]


def bus_dense_policy_enabled(settings: Settings, source: str) -> bool:
    """Dense collection requires two distinct keys approved for both datasets."""

    return len(configured_bus_pool(settings, source)) == 2
