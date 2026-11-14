"""Correcting a wrong opening balance: audited, concurrency-safe, no double counting."""

from typing import Any

from sqlalchemy import text
from sqlalchemy.orm import Session

from tests.conftest import Actor, at


def _account(admin: Actor) -> str:
    return admin.create_account(
        "Bank", "bank", opening_balance={"amount_poisha": 100_000, "as_of_date": "2026-01-01"}
    )


def _correct(admin: Actor, account: str, expect: int = 200, **overrides: Any) -> Any:
    body = {
        "previous_amount_poisha": 100_000,
        "previous_as_of_date": "2026-01-01",
        "amount_poisha": -25_050,
        "as_of_date": "2026-01-01",
        "reason": "Typed the wrong figure from the passbook",
        **overrides,
    }
    response = admin.post(f"/accounts/{account}/opening-balance/correct", body)
    assert response.status_code == expect, response.text
    return response.json()


def test_correction_changes_the_balance_exactly_and_is_audited(admin: Actor, db: Session) -> None:
    account = _account(admin)
    admin.create_tx(
        type="income",
        amount_poisha=5_000,
        category_id=admin.category_id("Salary"),
        account_id=account,
        occurred_at=at("2026-02-01T12:00:00"),
    )
    assert admin.balance(account) == 105_000
    out = _correct(admin, account)
    assert out["opening_balance"]["amount_poisha"] == -25_050
    assert admin.balance(account) == -20_050
    # The opening balance is still not income.
    summary = admin.get(
        "/reports/summary", params={"date_from": "2026-01-01", "date_to": "2026-12-31"}
    ).json()
    assert summary["income_poisha"] == 5_000
    details = db.execute(
        text("SELECT details FROM audit_events WHERE action = 'account.opening_balance_corrected'")
    ).scalar_one()
    assert details["from"] == {"amount_poisha": 100_000, "as_of_date": "2026-01-01"}
    assert details["to"] == {"amount_poisha": -25_050, "as_of_date": "2026-01-01"}
    assert details["reason"] == "Typed the wrong figure from the passbook"


def test_stale_values_are_refused(admin: Actor) -> None:
    account = _account(admin)
    _correct(admin, account)
    # A second admin still looking at the old figure cannot overwrite the first correction.
    _correct(admin, account, 409, amount_poisha=1)
    assert admin.balance(account) == -25_050


def test_moving_the_date_later_than_a_transaction_is_refused(admin: Actor) -> None:
    account = _account(admin)
    admin.create_tx(
        type="expense",
        amount_poisha=100,
        category_id=admin.category_id("Groceries"),
        account_id=account,
        occurred_at=at("2026-03-01T12:00:00"),
    )
    _correct(admin, account, 409, amount_poisha=100_000, as_of_date="2026-04-01")
    _correct(admin, account, amount_poisha=100_000, as_of_date="2025-12-01")  # earlier is fine


def test_rules(admin: Actor, join: Any) -> None:
    account = _account(admin)
    _correct(admin, account, 422, amount_poisha=100_000)  # nothing changes
    _correct(admin, account, 422, reason="  ")
    no_opening = admin.create_account("Cash")
    _correct(admin, no_opening, 404)
    member = join(admin, "amma", "member")
    assert (
        member.post(
            f"/accounts/{account}/opening-balance/correct",
            {
                "previous_amount_poisha": 100_000,
                "previous_as_of_date": "2026-01-01",
                "amount_poisha": 1,
                "as_of_date": "2026-01-01",
                "reason": "x",
            },
        ).status_code
        == 403
    )
