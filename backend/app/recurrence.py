"""Pure schedule arithmetic for recurring rules (no database).

Occurrence ``n`` (0-based) of a rule is computed from its start date, never
from the previous occurrence, so month-end days do not drift: a rule starting
on 31 January falls on 28/29 February, then on 31 March again.
"""

import calendar
from datetime import date, timedelta
from enum import StrEnum


class Frequency(StrEnum):
    WEEKLY = "weekly"
    MONTHLY = "monthly"
    YEARLY = "yearly"


def _clamped(year: int, month: int, day: int) -> date:
    return date(year, month, min(day, calendar.monthrange(year, month)[1]))


def occurrence(start: date, frequency: Frequency, interval: int, n: int) -> date:
    """The date of occurrence ``n`` (0 = the start date)."""
    if n < 0 or interval < 1:
        raise ValueError("n must be >= 0 and interval >= 1")
    steps = n * interval
    if frequency is Frequency.WEEKLY:
        return start + timedelta(weeks=steps)
    if frequency is Frequency.MONTHLY:
        months = start.month - 1 + steps
        return _clamped(start.year + months // 12, months % 12 + 1, start.day)
    return _clamped(start.year + steps, start.month, start.day)
