"""Calendar helpers. Reporting dates are local calendar dates in the
household's timezone; storage is always timezone-aware UTC instants."""

from datetime import UTC, date, datetime, time, timedelta
from zoneinfo import ZoneInfo


def utcnow() -> datetime:
    return datetime.now(UTC)


def local_today(tz_name: str) -> date:
    return datetime.now(ZoneInfo(tz_name)).date()


def local_day_start(day: date, tz_name: str) -> datetime:
    """The instant local midnight begins on ``day`` in ``tz_name``."""
    return datetime.combine(day, time.min, tzinfo=ZoneInfo(tz_name))


def local_range_bounds(start: date, end: date, tz_name: str) -> tuple[datetime, datetime]:
    """Half-open instant range [start 00:00, (end + 1 day) 00:00) for an
    inclusive local date range."""
    return local_day_start(start, tz_name), local_day_start(end + timedelta(days=1), tz_name)
