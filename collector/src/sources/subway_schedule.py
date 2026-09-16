"""Configurable polling pause; not proof that every subway line is closed."""
import re
from datetime import date, timedelta, timezone
from config import ConfigError

KST = timezone(timedelta(hours=9))


class SkippedNonService(RuntimeError):
    def __init__(self, resume_at, now):
        self.resume_at = resume_at
        self.wait_seconds = max(1, (resume_at - now).total_seconds())
        super().__init__("SKIPPED_NON_SERVICE")


def pause_until(source, extra, now):
    if source not in ("subway_location", "subway_arrival"):
        return None
    enabled = extra.get("SUBWAY_SKIP_ENABLED", "true").strip().lower()
    if enabled not in ("true", "false"):
        raise ConfigError("SUBWAY_SKIP_ENABLED must be true or false")
    if now.tzinfo is None:
        raise ConfigError("subway schedule requires a timezone-aware clock")
    now = now.astimezone(KST)

    def minute(name, default):
        value = extra.get(name, default).strip()
        if not re.fullmatch(r"(?:[01][0-9]|2[0-3]):[0-5][0-9]", value):
            raise ConfigError(name + " must be HH:MM")
        hour, minutes = map(int, value.split(":"))
        return hour * 60 + minutes

    start = minute("SUBWAY_SKIP_START", "03:30")
    end = minute("SUBWAY_SKIP_END", "04:30")
    if start >= end:
        raise ConfigError("SUBWAY_SKIP_START must be earlier than SUBWAY_SKIP_END within the same KST day")
    overrides = set()
    for value in extra.get("SUBWAY_SKIP_OVERRIDE_DATES", "").split(","):
        if value.strip():
            try:
                overrides.add(date.fromisoformat(value.strip()))
            except ValueError:
                raise ConfigError("SUBWAY_SKIP_OVERRIDE_DATES must contain comma-separated YYYY-MM-DD dates") from None
    if enabled == "false" or now.date() in overrides:
        return None
    if start <= now.hour * 60 + now.minute < end:
        return now.replace(hour=end // 60, minute=end % 60, second=0, microsecond=0)
    return None
