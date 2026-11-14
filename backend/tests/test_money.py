import pytest

from app.money import InvalidAmountError, format_poisha, parse_taka


@pytest.mark.parametrize(
    ("text", "poisha"),
    [
        ("0", 0),
        ("1", 100),
        ("120", 12000),
        ("120.5", 12050),
        ("120.50", 12050),
        ("0.01", 1),
        ("0.1", 10),
        ("  75.25 ", 7525),
        ("1,250.75", 125075),
        ("1,250,000", 125000000),
        ("12,50,000", 125000000),  # South Asian lakh grouping
        ("১২০.৫০", 12050),  # Bangla digits
        ("999999999999999.99", 99999999999999999),
    ],
)
def test_parse_taka_exact(text: str, poisha: int) -> None:
    assert parse_taka(text) == poisha


@pytest.mark.parametrize(
    "text",
    [
        "",
        "abc",
        "-5",
        "+5",
        "1.234",  # more than two decimals is rejected, never rounded
        "1e3",
        "12,50",  # comma used as a decimal separator must not become 1250 taka
        "1,2,3",
        "1,25",
        ".5",
        "5.",
        "NaN",
        "Infinity",
        "1 000",
        "0x10",
    ],
)
def test_parse_taka_rejects(text: str) -> None:
    with pytest.raises(InvalidAmountError):
        parse_taka(text)


def test_float_artifacts_cannot_arise() -> None:
    # 0.1 + 0.2 != 0.3 in binary floating point; poisha integers are exact.
    assert parse_taka("0.10") + parse_taka("0.20") == parse_taka("0.30")


@pytest.mark.parametrize(
    ("poisha", "text"),
    [
        (0, "0.00"),
        (5, "0.05"),
        (12050, "120.50"),
        (-1250, "-12.50"),
        (100000000000, "1000000000.00"),
    ],
)
def test_format_poisha(poisha: int, text: str) -> None:
    assert format_poisha(poisha) == text
