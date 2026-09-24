"""Source-specific credential pools for Seoul bus APIs."""

from __future__ import annotations

from config import Settings

POOL_KEY_NAMES = {
    "bus_arrival": ("BUS_ARRIVAL_API_KEY_A", "BUS_ARRIVAL_API_KEY_B"),
    "bus_location": ("BUS_LOCATION_API_KEY_A", "BUS_LOCATION_API_KEY_B"),
}

LEGACY_KEY_NAMES = {
    "bus_arrival": "BUS_ARRIVAL_API_KEY",
    "bus_location": "BUS_LOCATION_API_KEY",
}


def configured_bus_pool(settings: Settings, source: str) -> list[tuple[str, str]]:
    """Return distinct A/B credentials configured for one bus dataset only."""

    try:
        names = POOL_KEY_NAMES[source]
    except KeyError as exc:
        raise ValueError(f"unsupported bus source: {source}") from exc

    credentials = []
    seen = set()
    for slot, name in zip(("A", "B"), names, strict=True):
        key = settings.api_keys.get(name)
        normalized = key.strip() if key else ""
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
    """Dense collection requires two distinct approved keys for that dataset."""

    return len(configured_bus_pool(settings, source)) == 2
