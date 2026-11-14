from datetime import date

import pytest

from app.recurrence import Frequency, occurrence


def dates(start: date, freq: Frequency, interval: int, count: int) -> list[str]:
    return [occurrence(start, freq, interval, n).isoformat() for n in range(count)]


def test_month_end_clamps_without_drifting() -> None:
    assert dates(date(2026, 1, 31), Frequency.MONTHLY, 1, 4) == [
        "2026-01-31",
        "2026-02-28",
        "2026-03-31",
        "2026-04-30",
    ]


def test_leap_years_and_every_two_months() -> None:
    assert dates(date(2028, 2, 29), Frequency.YEARLY, 1, 2) == ["2028-02-29", "2029-02-28"]
    assert dates(date(2026, 11, 15), Frequency.MONTHLY, 2, 3) == [
        "2026-11-15",
        "2027-01-15",
        "2027-03-15",
    ]


def test_weekly() -> None:
    assert dates(date(2026, 10, 16), Frequency.WEEKLY, 2, 3) == [
        "2026-10-16",
        "2026-10-30",
        "2026-11-13",
    ]


def test_rejects_nonsense() -> None:
    with pytest.raises(ValueError):
        occurrence(date(2026, 1, 1), Frequency.MONTHLY, 0, 1)
