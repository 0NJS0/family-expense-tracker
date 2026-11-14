"""Exact money handling. Amounts are integer poisha (1 taka = 100 poisha).

Never convert money through float. Decimal input is parsed textually.
"""

import re

POISHA_PER_TAKA = 100
# ৳1,000,000,000.00 per transaction. Far below BIGINT range, so sums of
# millions of transactions cannot overflow.
MAX_TRANSACTION_POISHA = 100_000_000_000
# Opening balances may be negative (e.g. an overdrawn account) within this bound.
MAX_ABS_OPENING_BALANCE_POISHA = 10_000_000_000_000

# Whole part: plain digits, Western grouping (1,250,000) or South Asian
# grouping (12,50,000). "12,50" is rejected so a comma used as a decimal
# separator can never be misread as a thousands separator.
_TAKA_PATTERN = re.compile(
    r"^(?P<whole>\d{1,15}|\d{1,3}(?:,\d{3})+|\d{1,2}(?:,\d{2})*,\d{3})"
    r"(?:\.(?P<frac>\d{1,2}))?$"
)
_BANGLA_DIGITS = str.maketrans("০১২৩৪৫৬৭৮৯", "0123456789")


class InvalidAmountError(ValueError):
    pass


def parse_taka(text: str) -> int:
    """Parse a non-negative taka string such as "120", "120.5", "1,250.75"
    or Bangla digits "১২০.৫০" into integer poisha.

    Rejects more than two decimal places instead of rounding, so the user's
    intended amount is never silently changed.
    """
    cleaned = text.strip().translate(_BANGLA_DIGITS)
    match = _TAKA_PATTERN.fullmatch(cleaned)
    if match is None:
        raise InvalidAmountError("amount must be a number with at most two decimal places")
    whole = int(match.group("whole").replace(",", ""))
    frac = (match.group("frac") or "").ljust(2, "0")
    return whole * POISHA_PER_TAKA + int(frac)


def format_poisha(amount_poisha: int) -> str:
    """Format poisha as a plain taka string with two decimals, e.g. -1250 -> "-12.50"."""
    sign = "-" if amount_poisha < 0 else ""
    whole, frac = divmod(abs(amount_poisha), POISHA_PER_TAKA)
    return f"{sign}{whole}.{frac:02d}"
