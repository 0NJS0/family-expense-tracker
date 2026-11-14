from datetime import date
from typing import Any

import pytest

from app.schemas.reports import CompareTo, ComparisonMode
from app.services.reports import _basis_points, previous_period
from tests.conftest import Actor, at


@pytest.fixture
def book(admin: Actor) -> dict[str, str]:
    return {
        "cash": admin.create_account("Cash"),
        "bank": admin.create_account("Bank", "bank"),
        "groceries": admin.category_id("Groceries"),
        "transport": admin.category_id("Transport"),
        "salary": admin.category_id("Salary"),
    }


def _tx(a: Actor, b: dict[str, str], kind: str, amount: int, when: str, **kw: Any) -> None:
    fields: dict[str, Any] = {"type": kind, "amount_poisha": amount, "occurred_at": at(when)}
    if kind == "income":
        fields |= {"category_id": b["salary"], "account_id": b["bank"]}
    elif kind == "expense":
        fields |= {"category_id": kw.pop("category", b["groceries"]), "account_id": b["cash"]}
    else:
        fields |= {"account_id": b["cash"], "destination_account_id": b["bank"]}
    a.create_tx(**fields, **kw)


# --- all balances in one call -------------------------------------------------


def test_all_balances_match_individual_endpoint(admin: Actor, book: dict[str, str]) -> None:
    admin.post(
        f"/accounts/{book['bank']}/opening-balance",
        {"amount_poisha": 10_000_00, "as_of_date": "2026-01-01"},
    )
    _tx(admin, book, "income", 5_000_00, "2026-03-01T09:00:00")
    _tx(admin, book, "expense", 120_50, "2026-03-02T09:00:00")
    _tx(admin, book, "transfer", 1_000_00, "2026-03-03T09:00:00")

    body = admin.get("/accounts/balances").json()
    rows = {row["name"]: row for row in body["accounts"]}
    assert rows["Bank"]["balance_poisha"] == 10_000_00 + 5_000_00 + 1_000_00
    assert rows["Cash"]["balance_poisha"] == -120_50 - 1_000_00
    for row in body["accounts"]:
        assert row["balance_poisha"] == admin.balance(row["account_id"])
    # Transfers between listed accounts net to zero in the total.
    assert body["total_poisha"] == 10_000_00 + 5_000_00 - 120_50
    assert rows["Cash"]["kind"] == "cash" and rows["Cash"]["is_shared"] is True


def test_all_balances_as_of_and_archived(admin: Actor, book: dict[str, str]) -> None:
    _tx(admin, book, "expense", 100_00, "2026-03-01T09:00:00")
    _tx(admin, book, "expense", 200_00, "2026-03-10T09:00:00")
    early = admin.get("/accounts/balances", params={"as_of": "2026-03-05"}).json()
    assert early["as_of_date"] == "2026-03-05"
    assert {r["name"]: r["balance_poisha"] for r in early["accounts"]}["Cash"] == -100_00

    admin.patch(f"/accounts/{book['cash']}", {"is_archived": True})
    names = [r["name"] for r in admin.get("/accounts/balances").json()["accounts"]]
    assert "Cash" not in names and set(names) == {"Bank"} | {"নগদ", "বিকাশ", "ব্যাংক"}
    with_archived = admin.get("/accounts/balances", params={"include_archived": True}).json()
    assert with_archived["total_poisha"] == -300_00


def test_all_balances_new_household(admin: Actor) -> None:
    body = admin.get("/accounts/balances").json()
    assert body["total_poisha"] == 0
    assert {r["name"] for r in body["accounts"]} == {"নগদ", "বিকাশ", "ব্যাংক"}
    assert all(r["balance_poisha"] == 0 for r in body["accounts"])


def test_all_balances_isolated_per_household(admin: Actor, make_actor) -> None:  # type: ignore[no-untyped-def]
    admin.create_account("Cash")
    other: Actor = make_actor("neighbour", "Neighbours")
    mine = {r["account_id"] for r in admin.get("/accounts/balances").json()["accounts"]}
    theirs = {r["account_id"] for r in other.get("/accounts/balances").json()["accounts"]}
    assert mine.isdisjoint(theirs)
    assert "Cash" not in {r["name"] for r in other.get("/accounts/balances").json()["accounts"]}


# --- previous-period comparison ---------------------------------------------------


@pytest.mark.parametrize(
    ("current", "expected", "mode"),
    [
        # Month to date: same days of the previous month.
        (("2026-10-01", "2026-10-15"), ("2026-09-01", "2026-09-15"), "calendar_months"),
        # Full months compare with full months of differing lengths.
        (("2026-02-01", "2026-02-28"), ("2026-01-01", "2026-01-31"), "calendar_months"),
        (("2026-03-01", "2026-03-31"), ("2026-02-01", "2026-02-28"), "calendar_months"),
        (("2024-03-01", "2024-03-31"), ("2024-02-01", "2024-02-29"), "calendar_months"),
        # Day clamped to the shorter month.
        (("2026-03-01", "2026-03-30"), ("2026-02-01", "2026-02-28"), "calendar_months"),
        # A quarter crossing a year boundary.
        (("2026-01-01", "2026-03-31"), ("2025-10-01", "2025-12-31"), "calendar_months"),
        # Arbitrary range: the equal-length window just before it.
        (("2026-03-05", "2026-03-11"), ("2026-02-26", "2026-03-04"), "preceding_days"),
        (("2026-03-10", "2026-03-10"), ("2026-03-09", "2026-03-09"), "preceding_days"),
    ],
)
def test_previous_period_rules(
    current: tuple[str, str], expected: tuple[str, str], mode: str
) -> None:
    prev_from, prev_to, got_mode = previous_period(
        date.fromisoformat(current[0]), date.fromisoformat(current[1])
    )
    assert (prev_from.isoformat(), prev_to.isoformat()) == expected
    assert got_mode is ComparisonMode(mode)


@pytest.mark.parametrize(
    ("current", "expected"),
    [
        # Year to date vs the same dates last year (not the 10 months before).
        (("2026-01-01", "2026-10-15"), ("2025-01-01", "2025-10-15")),
        (("2026-03-05", "2026-03-11"), ("2025-03-05", "2025-03-11")),
        # Leap day clamps to 28 Feb.
        (("2024-02-01", "2024-02-29"), ("2023-02-01", "2023-02-28")),
    ],
)
def test_previous_year_comparison(current: tuple[str, str], expected: tuple[str, str]) -> None:
    prev_from, prev_to, mode = previous_period(
        date.fromisoformat(current[0]), date.fromisoformat(current[1]), CompareTo.PREVIOUS_YEAR
    )
    assert (prev_from.isoformat(), prev_to.isoformat()) == expected
    assert mode is ComparisonMode.PREVIOUS_YEAR


def test_summary_compare_to_previous_year(admin: Actor, book: dict[str, str]) -> None:
    _tx(admin, book, "expense", 700_00, "2025-03-10T09:00:00")
    _tx(admin, book, "expense", 999_00, "2026-02-10T09:00:00")  # previous *period*, not year
    _tx(admin, book, "expense", 500_00, "2026-03-10T09:00:00")
    params = {"date_from": "2026-03-01", "date_to": "2026-03-31"}
    default = admin.get("/reports/summary", params=params).json()["comparison"]
    assert default["expense_poisha"] == 999_00
    yoy = admin.get("/reports/summary", params={**params, "compare_to": "previous_year"}).json()
    c = yoy["comparison"]
    assert (c["mode"], c["date_from"], c["date_to"]) == (
        "previous_year",
        "2025-03-01",
        "2025-03-31",
    )
    assert c["expense_poisha"] == 700_00 and c["expense_change_poisha"] == 500_00 - 700_00
    bad = admin.get("/reports/summary", params={**params, "compare_to": "last_decade"})
    assert bad.status_code == 422


@pytest.mark.parametrize(
    ("current", "previous", "expected"),
    [
        (1250, 1000, 2500),
        (750, 1000, -2500),
        (1000, 1000, 0),
        (4, 3, 3333),  # 3333.33…
        (1, 3, -6667),  # -6666.67…
        (1, 0, None),
        (0, 0, None),
    ],
)
def test_basis_points_exact(current: int, previous: int, expected: int | None) -> None:
    assert _basis_points(current, previous) == expected


def test_summary_comparison(admin: Actor, book: dict[str, str]) -> None:
    # February (previous) and March (current).
    _tx(admin, book, "income", 40_000_00, "2026-02-01T09:00:00")
    _tx(admin, book, "expense", 1_000_00, "2026-02-10T09:00:00")
    _tx(admin, book, "expense", 300_00, "2026-02-11T09:00:00", category=book["transport"])
    _tx(admin, book, "transfer", 9_999_00, "2026-02-12T09:00:00")
    _tx(admin, book, "income", 40_000_00, "2026-03-01T09:00:00")
    _tx(admin, book, "expense", 1_250_00, "2026-03-10T09:00:00")

    s = admin.get(
        "/reports/summary", params={"date_from": "2026-03-01", "date_to": "2026-03-31"}
    ).json()
    c = s["comparison"]
    assert (c["mode"], c["date_from"], c["date_to"]) == (
        "calendar_months",
        "2026-02-01",
        "2026-02-28",
    )
    assert c["expense_poisha"] == 1_300_00  # transfer excluded
    assert c["transfer_poisha"] == 9_999_00
    assert c["expense_change_poisha"] == 1_250_00 - 1_300_00
    assert c["expense_change_basis_points"] == round((1_250_00 - 1_300_00) * 10_000 / 1_300_00)
    assert c["income_change_poisha"] == 0 and c["income_change_basis_points"] == 0
    assert c["net_change_poisha"] == (40_000_00 - 1_250_00) - (40_000_00 - 1_300_00)

    changes = {ch["name_en"]: ch for ch in c["category_changes"]}
    # Transport was used only in the previous period but still appears.
    assert changes["Transport"] == {
        "category_id": book["transport"],
        "kind": "expense",
        "name_en": "Transport",
        "name_bn": "যাতায়াত",
        "current_poisha": 0,
        "previous_poisha": 300_00,
        "change_poisha": -300_00,
    }
    assert changes["Groceries"]["change_poisha"] == 250_00
    assert changes["Salary"]["change_poisha"] == 0
    # Sorted by size of change.
    assert [ch["name_en"] for ch in c["category_changes"]] == ["Transport", "Groceries", "Salary"]


def test_comparison_without_previous_data(admin: Actor, book: dict[str, str]) -> None:
    _tx(admin, book, "expense", 500_00, "2026-03-10T09:00:00")
    c = admin.get(
        "/reports/summary", params={"date_from": "2026-03-01", "date_to": "2026-03-31"}
    ).json()["comparison"]
    assert c["expense_poisha"] == 0
    assert c["expense_change_poisha"] == 500_00
    assert c["expense_change_basis_points"] is None


def test_comparison_respects_member_filter(admin: Actor, book: dict[str, str], join) -> None:  # type: ignore[no-untyped-def]
    amma: Actor = join(admin, "amma", "member")
    _tx(admin, book, "expense", 700_00, "2026-02-10T09:00:00")
    _tx(admin, book, "expense", 200_00, "2026-02-11T09:00:00", attributed_user_id=str(amma.user_id))
    c = admin.get(
        "/reports/summary",
        params={"date_from": "2026-03-01", "date_to": "2026-03-31", "member_id": str(amma.user_id)},
    ).json()["comparison"]
    assert c["expense_poisha"] == 200_00


# --- per-person breakdown and largest-first listing ---------------------------------


def test_summary_by_member_partitions_totals(admin: Actor, book: dict[str, str], join) -> None:  # type: ignore[no-untyped-def]
    amma: Actor = join(admin, "amma", "member")
    _tx(admin, book, "expense", 100_00, "2026-03-02T09:00:00")
    _tx(admin, book, "expense", 250_00, "2026-03-02T10:00:00", attributed_user_id=str(amma.user_id))
    _tx(amma, book, "expense", 40_00, "2026-03-03T10:00:00")
    _tx(admin, book, "income", 1_000_00, "2026-03-04T10:00:00")
    _tx(admin, book, "transfer", 9_999_00, "2026-03-05T10:00:00")
    s = admin.get(
        "/reports/summary", params={"date_from": "2026-03-01", "date_to": "2026-03-31"}
    ).json()
    rows = {r["display_name"]: r for r in s["by_member"]}
    assert rows["amma"]["expense_poisha"] == 290_00  # largest spender listed first
    assert s["by_member"][0]["display_name"] == "amma"
    assert rows["Admin"]["expense_poisha"] == 100_00
    assert rows["Admin"]["income_poisha"] == 1_000_00
    # Transfers excluded; every transaction counted exactly once.
    assert sum(r["expense_poisha"] for r in s["by_member"]) == s["expense_poisha"]
    assert sum(r["income_poisha"] for r in s["by_member"]) == s["income_poisha"]
    assert sum(r["transaction_count"] for r in s["by_member"]) == 4


def test_transactions_largest_first(admin: Actor, book: dict[str, str]) -> None:
    for amount, day in ((5_00, "01"), (900_00, "02"), (50_00, "03")):
        _tx(admin, book, "expense", amount, f"2026-03-{day}T09:00:00")
    largest = admin.get("/transactions", params={"sort": "largest", "limit": 2}).json()["items"]
    assert [t["amount_poisha"] for t in largest] == [900_00, 50_00]
    newest = admin.get("/transactions", params={"limit": 1}).json()["items"]
    assert newest[0]["amount_poisha"] == 50_00
    assert admin.get("/transactions", params={"sort": "random"}).status_code == 422
