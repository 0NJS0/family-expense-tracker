"""Recurring rules: reminders that never move money unless confirmed (or auto_post)."""

from datetime import date, datetime, timedelta
from typing import Any
from zoneinfo import ZoneInfo

import pytest
from fastapi import FastAPI
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.models import RecurringOccurrence, Transaction
from app.services import recurring
from tests.conftest import Actor
from tests.test_transactions import _context, _run_concurrently

DHAKA = ZoneInfo("Asia/Dhaka")
TODAY = datetime.now(DHAKA).date()


@pytest.fixture
def home(admin: Actor) -> dict[str, Any]:
    return {
        "cash": admin.create_account("Cash"),
        "bank": admin.create_account("Bank", "bank"),
        "rent": admin.category_id("Rent"),
        "salary": admin.category_id("Salary"),
    }


def _rule(actor: Actor, h: dict[str, Any], expect: int = 201, **overrides: Any) -> dict[str, Any]:
    body = {
        "name": "Rent",
        "type": "expense",
        "amount_poisha": 1_500_000,
        "category_id": h["rent"],
        "account_id": h["bank"],
        "frequency": "weekly",
        "start_date": (TODAY - timedelta(days=14)).isoformat(),
        **overrides,
    }
    response = actor.post("/recurring-rules", body)
    assert response.status_code == expect, response.text
    result: dict[str, Any] = response.json()
    return result


def _due(actor: Actor, status: str = "pending") -> list[dict[str, Any]]:
    response = actor.get("/recurring/occurrences", params={"status": status})
    assert response.status_code == 200, response.text
    result: list[dict[str, Any]] = response.json()
    return result


def _count(db: Session, model: Any) -> int:
    return int(db.scalar(select(func.count()).select_from(model)) or 0)


def test_due_dates_are_generated_once_and_nothing_is_posted(
    admin: Actor, home: dict[str, Any], db: Session
) -> None:
    rule = _rule(admin, home)
    first = _due(admin)
    assert sorted(o["due_date"] for o in first) == [
        (TODAY - timedelta(days=14)).isoformat(),
        (TODAY - timedelta(days=7)).isoformat(),
        TODAY.isoformat(),
    ]
    assert _due(admin) == first  # asking again generates nothing new
    assert _count(db, Transaction) == 0  # a due bill is not a payment
    assert admin.balance(home["bank"]) == 0
    rules = admin.get("/recurring-rules").json()
    assert rules[0]["next_due_date"] == (TODAY + timedelta(days=7)).isoformat()
    assert rules[0]["last_processed_date"] == TODAY.isoformat()
    assert rule["auto_post"] is False


def test_concurrent_requests_never_duplicate(
    app: FastAPI, admin: Actor, home: dict[str, Any]
) -> None:
    _rule(admin, home, auto_post=True)

    def generate(_: int) -> None:
        with app.state.sessionmaker() as s:
            ctx = _context(s, admin.user_id, admin.household_id)
            recurring.generate_due(s, ctx.household, TODAY)
            s.commit()

    results = _run_concurrently(6, generate)
    assert all(r is None for r in results), results
    with app.state.sessionmaker() as s:
        assert _count(s, RecurringOccurrence) == 3
        assert _count(s, Transaction) == 3


def test_confirming_posts_exactly_once_with_the_real_amount(
    admin: Actor, home: dict[str, Any], db: Session
) -> None:
    _rule(admin, home)
    occ = sorted(_due(admin), key=lambda o: o["due_date"])[0]
    path = f"/recurring/occurrences/{occ['id']}/confirm"
    done = admin.post(path, {"amount": "15,250.50"})
    assert done.status_code == 200, done.text
    assert done.json()["status"] == "posted"
    again = admin.post(path, {"amount_poisha": 1})  # a double tap
    assert again.json()["transaction_id"] == done.json()["transaction_id"]
    assert _count(db, Transaction) == 1
    tx = admin.get(f"/transactions/{done.json()['transaction_id']}").json()
    assert tx["amount_poisha"] == 1_525_050
    local = datetime.fromisoformat(tx["occurred_at"]).astimezone(DHAKA)
    assert (local.date().isoformat(), local.hour) == (occ["due_date"], 12)
    assert admin.balance(home["bank"]) == -1_525_050
    assert admin.post(f"/recurring/occurrences/{occ['id']}/skip").status_code == 409
    assert len(_due(admin)) == 2


def test_skipping_posts_nothing_and_cannot_be_confirmed_later(
    admin: Actor, home: dict[str, Any], db: Session
) -> None:
    _rule(admin, home)
    occ = _due(admin)[0]
    assert admin.post(f"/recurring/occurrences/{occ['id']}/skip").json()["status"] == "skipped"
    assert (
        admin.post(f"/recurring/occurrences/{occ['id']}/skip").status_code == 200
    )  # harmless repeat
    assert admin.post(f"/recurring/occurrences/{occ['id']}/confirm", {}).status_code == 409
    assert _count(db, Transaction) == 0
    assert [o["id"] for o in _due(admin, "skipped")] == [occ["id"]]


def test_auto_post_creates_each_transaction_once(
    admin: Actor, home: dict[str, Any], db: Session
) -> None:
    _rule(admin, home, type="income", category_id=home["salary"], name="Salary", auto_post=True)
    assert _due(admin) == []
    assert len(_due(admin, "posted")) == 3
    _due(admin)
    assert _count(db, Transaction) == 3
    assert admin.balance(home["bank"]) == 3 * 1_500_000


def test_auto_post_that_cannot_post_waits_for_a_person(
    admin: Actor, home: dict[str, Any], db: Session
) -> None:
    _rule(admin, home, auto_post=True, start_date=TODAY.isoformat())
    admin.patch(f"/accounts/{home['bank']}", {"is_archived": True})
    # Generation happens on read; the archived account refuses the posting.
    pending = _due(admin)
    assert len(pending) == 1
    assert _count(db, Transaction) == 0


def test_pause_stops_reminders_and_resuming_does_not_catch_up(
    admin: Actor, home: dict[str, Any]
) -> None:
    rule = _rule(admin, home, start_date=(TODAY - timedelta(days=21)).isoformat())
    paused = admin.patch(f"/recurring-rules/{rule['id']}", {"version": 1, "paused": True}).json()
    assert paused["paused"] is True
    assert _due(admin) == []
    resumed = admin.patch(f"/recurring-rules/{rule['id']}", {"version": 2, "paused": False}).json()
    assert resumed["next_due_date"] == TODAY.isoformat()
    assert [o["due_date"] for o in _due(admin)] == [TODAY.isoformat()]


def test_end_date_and_future_start(admin: Actor, home: dict[str, Any]) -> None:
    ended = _rule(admin, home, end_date=(TODAY - timedelta(days=10)).isoformat())
    _rule(admin, home, name="Later", start_date=(TODAY + timedelta(days=3)).isoformat())
    due = _due(admin)
    assert [o["due_date"] for o in due] == [(TODAY - timedelta(days=14)).isoformat()]
    rules = {r["id"]: r for r in admin.get("/recurring-rules").json()}
    assert rules[ended["id"]]["next_due_date"] is None


def test_rule_validation_and_versions(admin: Actor, home: dict[str, Any]) -> None:
    _rule(admin, home, 422, category_id=home["salary"])  # income category on an expense
    _rule(admin, home, 422, type="transfer", category_id=None)  # no destination
    _rule(admin, home, 422, end_date=(TODAY - timedelta(days=30)).isoformat())
    _rule(admin, home, 422, interval=13)
    rule = _rule(
        admin, home, type="transfer", category_id=None, destination_account_id=home["cash"]
    )
    assert (
        admin.patch(f"/recurring-rules/{rule['id']}", {"version": 1, "amount": "100.00"}).json()[
            "amount_poisha"
        ]
        == 10_000
    )
    stale = admin.patch(f"/recurring-rules/{rule['id']}", {"version": 1, "amount_poisha": 5})
    assert stale.status_code == 409


def test_roles(admin: Actor, home: dict[str, Any], join: Any) -> None:
    member = join(admin, "amma", "member")
    viewer = join(admin, "nana", "viewer")
    _rule(member, home, 403)
    _rule(admin, home)
    occ = _due(viewer)[0]
    assert viewer.post(f"/recurring/occurrences/{occ['id']}/confirm", {}).status_code == 403
    assert member.post(f"/recurring/occurrences/{occ['id']}/confirm", {}).status_code == 200


def test_rules_on_a_private_account_are_hidden_from_others(
    admin: Actor, home: dict[str, Any], join: Any
) -> None:
    abba = join(admin, "abba", "member")
    amma = join(admin, "amma", "member")
    secret = admin.create_account(
        "Abba's bKash", "mobile_wallet", owner_user_id=str(abba.user_id), is_private=True
    )
    _rule(admin, home, account_id=secret, name="Abba's phone bill")
    assert amma.get("/recurring-rules").json() == []
    assert _due(amma) == []
    hidden = _due(admin)[0]
    assert amma.post(f"/recurring/occurrences/{hidden['id']}/confirm", {}).status_code == 404
    assert len(_due(abba)) == 3


def test_month_end_rule_lands_on_month_ends(admin: Actor, home: dict[str, Any]) -> None:
    rule = _rule(
        admin,
        home,
        frequency="monthly",
        start_date=date(2026, 1, 31).isoformat(),
        end_date=date(2026, 4, 30).isoformat(),
    )
    dates = sorted(o["due_date"] for o in _due(admin) if o["rule_id"] == rule["id"])
    expected = [
        d
        for d in ("2026-01-31", "2026-02-28", "2026-03-31", "2026-04-30")
        if d <= TODAY.isoformat()
    ]
    assert dates == expected
