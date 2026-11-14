"""Receipt text parsing: only confident totals, exact amounts, sensible dates."""

from datetime import date

import pytest

from app.ocr import find_date, find_total

TODAY = date(2026, 10, 17)


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("Rice 2kg 180.00\nOil 1L 210.50\nTotal 390.50\nPhone 01712345678", 39050),
        ("Sub Total 1,000.00\nVAT 150.00\nGrand Total 1,150.00", 115000),
        ("চাল ১৮০\nতেল ২১০.৫০\nমোট ৩৯০.৫০", 39050),
        ("সর্বমোট: ১২,৫০০ টাকা", 1250000),
        ("Total 300\nDiscount 20\nNet Payable 280", 28000),
        ("Invoice 12345678\nItem 50", None),  # no total line: no guess
        ("Sub total 500", None),
        ("TOTAL Tk. 1,25,000.00", 12500000),  # South Asian grouping
        ("Total 12,50", None),  # ambiguous comma: never misread
    ],
)
def test_total(text: str, expected: int | None) -> None:
    assert find_total(text) == expected


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("Date: 15/10/2026", date(2026, 10, 15)),
        ("তারিখ: ১৫-১০-২৬", date(2026, 10, 15)),
        ("2026-10-01 10:30", date(2026, 10, 1)),
        ("Date 31/02/2026 then 01.10.2026", date(2026, 10, 1)),  # invalid one skipped
        ("Date 20/12/2026", None),  # future
        ("no date here 12345", None),
    ],
)
def test_date(text: str, expected: date | None) -> None:
    assert find_date(text, TODAY) == expected
