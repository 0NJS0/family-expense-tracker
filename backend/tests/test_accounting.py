"""Executable form of docs/accounting-rules.md. Rule numbers refer to that document."""

from typing import Any

import pytest

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


def income(a: Actor, b: dict[str, str], amount: int, when: str, **kw: Any) -> dict[str, Any]:
    return a.create_tx(
        type="income",
        amount_poisha=amount,
        category_id=b["salary"],
        account_id=kw.pop("account", b["bank"]),
        occurred_at=at(when),
        **kw,
    )


def expense(a: Actor, b: dict[str, str], amount: int, when: str, **kw: Any) -> dict[str, Any]:
    return a.create_tx(
        type="expense",
        amount_poisha=amount,
        category_id=kw.pop("category", b["groceries"]),
        account_id=kw.pop("account", b["cash"]),
        occurred_at=at(when),
        **kw,
    )


def transfer(a: Actor, b: dict[str, str], amount: int, when: str) -> dict[str, Any]:
    return a.create_tx(
        type="transfer",
        amount_poisha=amount,
        account_id=b["cash"],
        destination_account_id=b["bank"],
        occurred_at=at(when),
    )


def summary(a: Actor, date_from: str, date_to: str, **params: Any) -> dict[str, Any]:
    r = a.get("/reports/summary", params={"date_from": date_from, "date_to": date_to, **params})
    assert r.status_code == 200, r.text
    result: dict[str, Any] = r.json()
    return result


def series(a: Actor, granularity: str, date_from: str, date_to: str) -> list[dict[str, Any]]:
    r = a.get(
        "/reports/timeseries",
        params={"granularity": granularity, "date_from": date_from, "date_to": date_to},
    )
    assert r.status_code == 200, r.text
    buckets: list[dict[str, Any]] = r.json()["buckets"]
    return buckets


# --- R1-R3: account movements -------------------------------------------------


def test_income_increases_expense_decreases(admin: Actor, book: dict[str, str]) -> None:
    income(admin, book, 1_000_00, "2026-03-01T09:00:00", account=book["cash"])
    expense(admin, book, 250_50, "2026-03-02T09:00:00")
    assert admin.balance(book["cash"]) == 1_000_00 - 250_50


def test_transfer_moves_money_between_accounts(admin: Actor, book: dict[str, str]) -> None:
    income(admin, book, 2_000_00, "2026-03-01T09:00:00", account=book["cash"])
    transfer(admin, book, 500_00, "2026-03-02T09:00:00")
    assert admin.balance(book["cash"]) == 1_500_00
    assert admin.balance(book["bank"]) == 500_00
    balance = admin.get(f"/accounts/{book['bank']}/balance").json()
    assert balance["inflow_poisha"] == 500_00 and balance["outflow_poisha"] == 0


# --- R4-R5, R8: transfers are not income/expense; net cash flow -----------------


def test_transfer_is_not_spending(admin: Actor, book: dict[str, str]) -> None:
    """The headline example: a ৳500 cash→bank transfer is not ৳500 of spending."""
    transfer(admin, book, 500_00, "2026-03-02T09:00:00")
    s = summary(admin, "2026-03-01", "2026-03-31")
    assert s["expense_poisha"] == 0
    assert s["income_poisha"] == 0
    assert s["net_poisha"] == 0
    assert s["transfer_poisha"] == 500_00
    assert s["expense_by_category"] == [] and s["income_by_category"] == []
    assert all(b["expense_poisha"] == 0 for b in series(admin, "day", "2026-03-01", "2026-03-31"))


def test_net_cash_flow_and_category_breakdown(admin: Actor, book: dict[str, str]) -> None:
    income(admin, book, 50_000_00, "2026-03-01T09:00:00")
    expense(admin, book, 1_200_00, "2026-03-02T09:00:00")
    expense(admin, book, 800_00, "2026-03-03T09:00:00")
    expense(admin, book, 300_00, "2026-03-03T10:00:00", category=book["transport"])
    transfer(admin, book, 10_000_00, "2026-03-04T09:00:00")
    s = summary(admin, "2026-03-01", "2026-03-31")
    assert s["income_poisha"] == 50_000_00
    assert s["expense_poisha"] == 2_300_00
    assert s["net_poisha"] == 50_000_00 - 2_300_00
    assert s["transaction_count"] == 5
    breakdown = {
        c["name_en"]: (c["total_poisha"], c["transaction_count"]) for c in s["expense_by_category"]
    }
    assert breakdown == {"Groceries": (2_000_00, 2), "Transport": (300_00, 1)}
    assert sum(c["total_poisha"] for c in s["expense_by_category"]) == s["expense_poisha"]
    assert s["expense_by_category"][0]["name_bn"] == "বাজার"  # sorted largest first


# --- R6: opening balances -------------------------------------------------------


def test_opening_balance_counts_in_balance_not_in_income(
    admin: Actor, book: dict[str, str]
) -> None:
    r = admin.post(
        f"/accounts/{book['bank']}/opening-balance",
        {"amount_poisha": 75_000_00, "as_of_date": "2026-01-01"},
    )
    assert r.status_code == 201
    income(admin, book, 10_000_00, "2026-01-05T09:00:00")
    assert admin.balance(book["bank"]) == 85_000_00
    assert summary(admin, "2026-01-01", "2026-01-31")["income_poisha"] == 10_000_00


def test_opening_balance_only_once(admin: Actor, book: dict[str, str]) -> None:
    path = f"/accounts/{book['cash']}/opening-balance"
    assert admin.post(path, {"amount_poisha": 100, "as_of_date": "2026-01-01"}).status_code == 201
    second = admin.post(path, {"amount_poisha": 999, "as_of_date": "2026-01-01"})
    assert second.status_code == 409
    assert admin.get(f"/accounts/{book['cash']}").json()["opening_balance"]["amount_poisha"] == 100


def test_opening_balance_at_account_creation(admin: Actor) -> None:
    r = admin.post(
        "/accounts",
        {
            "name": "bKash",
            "kind": "mobile_wallet",
            "institution": "bKash",
            "opening_balance": {"amount_poisha": 3_456_78, "as_of_date": "2026-02-01"},
        },
    )
    assert r.status_code == 201
    assert admin.balance(r.json()["id"]) == 3_456_78


def test_opening_balance_may_be_negative_within_bounds(admin: Actor, book: dict[str, str]) -> None:
    path = f"/accounts/{book['bank']}/opening-balance"
    assert (
        admin.post(path, {"amount_poisha": 10**13 + 1, "as_of_date": "2026-01-01"}).status_code
        == 422
    )
    assert admin.post(path, {"amount_poisha": -5_00, "as_of_date": "2026-01-01"}).status_code == 201
    assert admin.balance(book["bank"]) == -5_00


def test_transactions_before_opening_date_rejected(admin: Actor, book: dict[str, str]) -> None:
    admin.post(
        f"/accounts/{book['cash']}/opening-balance",
        {"amount_poisha": 1_000_00, "as_of_date": "2026-03-01"},
    )
    # 23:59 on 28 Feb in Dhaka is before the opening date even though it is 17:59 UTC.
    expense(admin, book, 100, "2026-02-28T23:59:00", expect=422)
    expense(admin, book, 100, "2026-03-01T00:00:00")
    # Transfers into the account are checked too.
    admin.create_tx(
        expect=422,
        type="transfer",
        amount_poisha=100,
        account_id=book["bank"],
        destination_account_id=book["cash"],
        occurred_at=at("2026-02-15T09:00:00"),
    )


def test_opening_balance_cannot_be_dated_after_existing_transactions(
    admin: Actor, book: dict[str, str]
) -> None:
    expense(admin, book, 100, "2026-02-10T09:00:00")
    late = admin.post(
        f"/accounts/{book['cash']}/opening-balance",
        {"amount_poisha": 1_000_00, "as_of_date": "2026-03-01"},
    )
    assert late.status_code == 409
    ok = admin.post(
        f"/accounts/{book['cash']}/opening-balance",
        {"amount_poisha": 1_000_00, "as_of_date": "2026-02-10"},
    )
    assert ok.status_code == 201
    assert admin.balance(book["cash"]) == 1_000_00 - 100


def test_balance_as_of_date(admin: Actor, book: dict[str, str]) -> None:
    admin.post(
        f"/accounts/{book['cash']}/opening-balance",
        {"amount_poisha": 1_000_00, "as_of_date": "2026-03-01"},
    )
    expense(admin, book, 100_00, "2026-03-05T23:30:00")  # still 5 March in Dhaka
    expense(admin, book, 200_00, "2026-03-06T00:30:00")
    assert admin.balance(book["cash"], as_of="2026-02-28") == 0
    assert admin.balance(book["cash"], as_of="2026-03-04") == 1_000_00
    assert admin.balance(book["cash"], as_of="2026-03-05") == 900_00
    assert admin.balance(book["cash"], as_of="2026-03-06") == 700_00
    assert admin.balance(book["cash"]) == 700_00


# --- R7: voided transactions ------------------------------------------------------


def test_voided_transactions_excluded_everywhere(admin: Actor, book: dict[str, str]) -> None:
    keep = expense(admin, book, 100_00, "2026-03-02T09:00:00")
    drop = expense(admin, book, 999_00, "2026-03-02T10:00:00")
    gone = transfer(admin, book, 50_00, "2026-03-02T11:00:00")
    for tx in (drop, gone):
        assert (
            admin.post(
                f"/transactions/{tx['id']}/void", {"version": 1, "reason": "mistake"}
            ).status_code
            == 200
        )
    assert admin.balance(book["cash"]) == -100_00
    assert admin.balance(book["bank"]) == 0
    s = summary(admin, "2026-03-01", "2026-03-31")
    assert s["expense_poisha"] == 100_00 and s["transfer_poisha"] == 0
    assert s["transaction_count"] == 1
    day = next(
        b for b in series(admin, "day", "2026-03-01", "2026-03-31") if b["start"] == "2026-03-02"
    )
    assert day["expense_poisha"] == keep["amount_poisha"]


def test_edit_changes_balances_of_both_old_and_new_account(
    admin: Actor, book: dict[str, str]
) -> None:
    tx = expense(admin, book, 300_00, "2026-03-02T09:00:00")
    admin.patch(f"/transactions/{tx['id']}", {"version": 1, "account_id": book["bank"]})
    assert admin.balance(book["cash"]) == 0
    assert admin.balance(book["bank"]) == -300_00


# --- R9-R10: attribution, no double counting --------------------------------------


def test_individual_reports_partition_household_total(
    admin: Actor,
    book: dict[str, str],
    join,  # type: ignore[no-untyped-def]
) -> None:
    amma: Actor = join(admin, "amma", "member")
    expense(admin, book, 100_00, "2026-03-02T09:00:00")
    # Entered by admin, spent by Amma: attributed to Amma.
    expense(admin, book, 250_00, "2026-03-02T10:00:00", attributed_user_id=str(amma.user_id))
    # Entered by Amma herself.
    expense(amma, book, 40_00, "2026-03-03T10:00:00")
    household = summary(admin, "2026-03-01", "2026-03-31")
    mine = summary(admin, "2026-03-01", "2026-03-31", member_id=str(admin.user_id))
    hers = summary(admin, "2026-03-01", "2026-03-31", member_id=str(amma.user_id))
    assert household["expense_poisha"] == 390_00
    assert mine["expense_poisha"] == 100_00
    assert hers["expense_poisha"] == 290_00
    assert mine["expense_poisha"] + hers["expense_poisha"] == household["expense_poisha"]


def test_shared_account_spending_counted_once(admin: Actor, book: dict[str, str], join) -> None:  # type: ignore[no-untyped-def]
    amma: Actor = join(admin, "amma", "member")
    personal = admin.create_account("Amma's purse", owner_user_id=str(amma.user_id))
    expense(admin, book, 70_00, "2026-03-02T09:00:00", account=personal)
    expense(amma, book, 30_00, "2026-03-02T10:00:00")  # shared cash
    s = summary(admin, "2026-03-01", "2026-03-31")
    assert s["expense_poisha"] == 100_00
    assert s["transaction_count"] == 2


# --- reporting periods, timezone and week boundaries ---------------------------------


def test_dhaka_midnight_boundary(admin: Actor, book: dict[str, str]) -> None:
    expense(admin, book, 1_00, "2026-01-31T23:59:59")  # 17:59:59 UTC on 31 Jan
    expense(admin, book, 2_00, "2026-02-01T00:00:00")  # 18:00:00 UTC on 31 Jan!
    assert summary(admin, "2026-01-01", "2026-01-31")["expense_poisha"] == 1_00
    assert summary(admin, "2026-02-01", "2026-02-28")["expense_poisha"] == 2_00
    months = series(admin, "month", "2026-01-01", "2026-02-28")
    assert [(m["start"], m["expense_poisha"]) for m in months] == [
        ("2026-01-01", 1_00),
        ("2026-02-01", 2_00),
    ]


def test_timestamps_in_other_offsets_bucket_by_dhaka_date(
    admin: Actor, book: dict[str, str]
) -> None:
    admin.create_tx(
        type="expense",
        amount_poisha=5_00,
        category_id=book["groceries"],
        account_id=book["cash"],
        occurred_at="2026-03-09T19:30:00Z",
    )  # 10 Mar 01:30 Dhaka
    day = {
        b["start"]: b["expense_poisha"] for b in series(admin, "day", "2026-03-09", "2026-03-10")
    }
    assert day == {"2026-03-09": 0, "2026-03-10": 5_00}


def test_daily_series_zero_filled(admin: Actor, book: dict[str, str]) -> None:
    expense(admin, book, 10_00, "2026-03-03T09:00:00")
    income(admin, book, 50_00, "2026-03-05T09:00:00")
    days = series(admin, "day", "2026-03-01", "2026-03-07")
    assert [d["start"] for d in days] == [f"2026-03-0{i}" for i in range(1, 8)]
    assert [d["expense_poisha"] for d in days] == [0, 0, 10_00, 0, 0, 0, 0]
    assert [d["net_poisha"] for d in days] == [0, 0, -10_00, 0, 50_00, 0, 0]
    assert not any(d["is_partial"] for d in days)


def test_weeks_start_on_saturday_by_default(admin: Actor, book: dict[str, str]) -> None:
    # 2026-03-07 is a Saturday, 2026-03-13 a Friday.
    expense(admin, book, 1_00, "2026-03-06T09:00:00")  # Friday: previous week
    expense(admin, book, 2_00, "2026-03-07T09:00:00")  # Saturday: new week
    expense(admin, book, 3_00, "2026-03-13T22:00:00")  # Friday night: same week
    weeks = series(admin, "week", "2026-02-28", "2026-03-20")
    assert [(w["start"], w["end"], w["expense_poisha"]) for w in weeks] == [
        ("2026-02-28", "2026-03-06", 1_00),
        ("2026-03-07", "2026-03-13", 5_00),
        ("2026-03-14", "2026-03-20", 0),
    ]


def test_week_start_is_configurable(make_actor) -> None:  # type: ignore[no-untyped-def]
    monday_home: Actor = make_actor("iso", "ISO home", week_start=0)
    weeks = monday_home.get(
        "/reports/timeseries",
        params={"granularity": "week", "date_from": "2026-03-02", "date_to": "2026-03-15"},
    ).json()["buckets"]
    assert [w["start"] for w in weeks] == ["2026-03-02", "2026-03-09"]  # Mondays


def test_partial_periods_flagged(admin: Actor, book: dict[str, str]) -> None:
    # Range cuts February and the year: both buckets are partial.
    months = series(admin, "month", "2026-01-15", "2026-02-10")
    assert [(m["start"], m["end"], m["is_partial"]) for m in months] == [
        ("2026-01-15", "2026-01-31", True),
        ("2026-02-01", "2026-02-10", True),
    ]
    full = series(admin, "month", "2026-01-01", "2026-01-31")
    assert full[0]["is_partial"] is False
    years = series(admin, "year", "2025-01-01", "2026-12-31")
    assert [(y["start"], y["is_partial"]) for y in years] == [
        ("2025-01-01", False),
        ("2026-01-01", True),
    ]
    assert summary(admin, "2026-01-01", "2026-01-31")["is_partial"] is False


def test_current_period_is_partial(admin: Actor) -> None:
    s = admin.get("/reports/summary").json()  # defaults to current month to date
    assert s["is_partial"] is True
    assert s["date_from"].endswith("-01")


def test_report_range_validation(admin: Actor) -> None:
    bad = admin.get("/reports/summary", params={"date_from": "2026-03-10", "date_to": "2026-03-01"})
    assert bad.status_code == 422
    too_many = admin.get(
        "/reports/timeseries",
        params={"granularity": "day", "date_from": "2020-01-01", "date_to": "2026-01-01"},
    )
    assert too_many.status_code == 422
    assert admin.get("/reports/timeseries", params={"granularity": "hour"}).status_code == 422


def test_large_sums_are_exact(admin: Actor, book: dict[str, str]) -> None:
    for i in range(3):
        income(admin, book, 100_000_000_000, f"2026-03-0{i + 1}T09:00:00")
    expense(admin, book, 1, "2026-03-04T09:00:00")
    s = summary(admin, "2026-03-01", "2026-03-31")
    assert s["income_poisha"] == 300_000_000_000
    assert s["net_poisha"] == 299_999_999_999
