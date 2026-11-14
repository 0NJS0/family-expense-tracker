"""Monthly budgets: exact actuals, a history that never changes, alerts exactly once."""

from datetime import date, datetime, timedelta
from typing import Any
from zoneinfo import ZoneInfo

import pytest
from sqlalchemy import text
from sqlalchemy.orm import Session

from tests.conftest import Actor

DHAKA = ZoneInfo("Asia/Dhaka")
NOW = datetime.now(DHAKA) - timedelta(minutes=1)
THIS_MONTH = NOW.date().replace(day=1)
NEXT_MONTH = date(THIS_MONTH.year + (THIS_MONTH.month == 12), THIS_MONTH.month % 12 + 1, 1)
LAST_MONTH = (THIS_MONTH - timedelta(days=1)).replace(day=1)


def _ym(month: date) -> str:
    return month.strftime("%Y-%m")


@pytest.fixture
def home(admin: Actor) -> dict[str, Any]:
    return {
        "cash": admin.create_account("Cash"),
        "bank": admin.create_account("Bank", "bank"),
        "groceries": admin.category_id("Groceries"),
        "transport": admin.category_id("Transport"),
        "salary": admin.category_id("Salary"),
    }


def _spend(
    actor: Actor, h: dict[str, Any], amount: int, category: str = "groceries", when: datetime = NOW
) -> dict[str, Any]:
    return actor.create_tx(
        type="expense",
        amount_poisha=amount,
        category_id=h[category],
        account_id=h["cash"],
        occurred_at=when.isoformat(),
    )


def _set(actor: Actor, expect: int = 200, **body: Any) -> dict[str, Any]:
    response = actor.client.put("/api/v1/budgets", headers=actor.headers, json=body)
    assert response.status_code == expect, response.text
    result: dict[str, Any] = response.json()
    return result


def _status(actor: Actor, month: date | None = None) -> dict[str, Any]:
    params = {"month": _ym(month)} if month else {}
    response = actor.get("/budgets", params=params)
    assert response.status_code == 200, response.text
    result: dict[str, Any] = response.json()
    return result


def _item(status: dict[str, Any], category_id: str | None = None) -> dict[str, Any]:
    return next(i for i in status["items"] if i["category_id"] == category_id)


def _alerts(actor: Actor) -> list[tuple[str | None, int]]:
    response = actor.get("/budgets/alerts")
    assert response.status_code == 200, response.text
    return sorted((a["category_id"] or "", a["threshold_percent"]) for a in response.json())


def test_actuals_count_only_expenses_exactly(admin: Actor, home: dict[str, Any]) -> None:
    _set(admin, amount_poisha=100_000)  # overall ৳1,000
    _set(admin, category_id=home["groceries"], amount="300.00")
    _spend(admin, home, 12_050)
    _spend(admin, home, 33_333, "transport")
    admin.create_tx(
        type="income",
        amount_poisha=999_999,
        category_id=home["salary"],
        account_id=home["cash"],
        occurred_at=NOW.isoformat(),
    )
    admin.create_tx(
        type="transfer",
        amount_poisha=50_000,
        account_id=home["cash"],
        destination_account_id=home["bank"],
        occurred_at=NOW.isoformat(),
    )
    voided = _spend(admin, home, 40_000)
    admin.post(f"/transactions/{voided['id']}/void", {"version": 1, "reason": "twice"})

    status = _status(admin)
    assert status["month"] == THIS_MONTH.isoformat() and status["is_partial"] is True
    overall = _item(status)
    assert overall["spent_poisha"] == 45_383
    assert overall["remaining_poisha"] == 54_617
    assert overall["used_basis_points"] == 4538  # 45.383% rounded down
    assert overall["state"] == "ok"
    groceries = _item(status, home["groceries"])
    assert (groceries["budget_poisha"], groceries["spent_poisha"], groceries["name_en"]) == (
        30_000,
        12_050,
        "Groceries",
    )
    assert status["items"][0]["category_id"] is None  # overall first


def test_states_and_over_budget(admin: Actor, home: dict[str, Any]) -> None:
    _set(admin, category_id=home["groceries"], amount_poisha=10_000, warn_percent=75)
    _spend(admin, home, 7_499)
    assert _item(_status(admin), home["groceries"])["state"] == "ok"
    _spend(admin, home, 1)
    assert _item(_status(admin), home["groceries"])["state"] == "warning"  # exactly 75%
    _spend(admin, home, 2_500)  # exactly 100%: reached, not over
    assert _item(_status(admin), home["groceries"])["state"] == "warning"
    _spend(admin, home, 1)
    item = _item(_status(admin), home["groceries"])
    assert (item["state"], item["remaining_poisha"]) == ("over", -1)


def test_a_change_applies_from_its_month_and_never_rewrites_the_past(
    admin: Actor, home: dict[str, Any], db: Session
) -> None:
    _set(admin, amount_poisha=100_000)
    _set(admin, amount_poisha=150_000, from_month=_ym(NEXT_MONTH))
    assert _item(_status(admin))["budget_poisha"] == 100_000
    assert _item(_status(admin, NEXT_MONTH))["budget_poisha"] == 150_000
    # Last month had no budget at all.
    assert _status(admin, LAST_MONTH)["items"] == []
    assert _status(admin, LAST_MONTH)["is_partial"] is False
    # Past months cannot be changed through the API.
    _set(admin, 422, amount_poisha=1, from_month=_ym(LAST_MONTH))

    # Removing from next month leaves this month alone.
    _set(admin, remove=True, from_month=_ym(NEXT_MONTH))
    assert _item(_status(admin))["budget_poisha"] == 100_000
    assert _status(admin, NEXT_MONTH)["items"] == []
    count = db.execute(
        text("SELECT count(*) FROM audit_events WHERE action = 'budget.set'")
    ).scalar_one()
    assert count == 3


def test_alerts_fire_once_per_threshold_and_month(admin: Actor, home: dict[str, Any]) -> None:
    _set(admin, category_id=home["groceries"], amount_poisha=10_000, warn_percent=80)
    _spend(admin, home, 7_000)
    assert _alerts(admin) == []
    _spend(admin, home, 1_000)  # 80%
    assert _alerts(admin) == [(home["groceries"], 80)]
    last = _spend(admin, home, 500)
    assert _alerts(admin) == [(home["groceries"], 80)]  # still once
    admin.post(f"/transactions/{last['id']}/void", {"version": 1, "reason": "x"})
    _spend(admin, home, 500)  # crossed "again": no repeat
    assert _alerts(admin) == [(home["groceries"], 80)]
    _spend(admin, home, 1_500)  # past 100%
    assert _alerts(admin) == [(home["groceries"], 80), (home["groceries"], 100)]


def test_lowering_a_budget_below_spending_alerts_at_once(
    admin: Actor, home: dict[str, Any]
) -> None:
    _spend(admin, home, 50_000)
    _set(admin, amount_poisha=40_000)
    assert _alerts(admin) == [("", 80), ("", 100)]


def test_a_correction_into_another_month_is_checked_there(
    admin: Actor, home: dict[str, Any]
) -> None:
    _set(admin, amount_poisha=10_000, from_month=_ym(THIS_MONTH))
    early = _spend(
        admin,
        home,
        9_000,
        when=datetime.combine(LAST_MONTH, datetime.min.time(), DHAKA) + timedelta(hours=12),
    )
    assert _alerts(admin) == []  # last month had no budget
    moved = admin.patch(
        f"/transactions/{early['id']}", {"version": 1, "occurred_at": NOW.isoformat()}
    )
    assert moved.status_code == 200, moved.text
    assert _alerts(admin) == [("", 80)]


def test_budget_rules(admin: Actor, home: dict[str, Any], make_actor: Any) -> None:
    _set(admin, 422, category_id=home["salary"], amount_poisha=100)  # income category
    other = make_actor("neighbour", "Next door")
    _set(admin, 422, category_id=other.category_id("Groceries"), amount_poisha=100)  # foreign
    _set(admin, 422, amount_poisha=100, amount="1.00")  # both forms
    _set(admin, 422)  # neither amount nor remove
    _set(admin, 422, amount_poisha=0)
    _set(admin, 422, amount_poisha=100, warn_percent=100)
    _set(admin, 404, remove=True)  # nothing to remove
    admin.patch(f"/categories/{home['transport']}", {"is_archived": True})
    _set(admin, 422, category_id=home["transport"], amount_poisha=100)


def test_members_see_budgets_but_only_admins_set_them_or_read_alerts(
    admin: Actor, home: dict[str, Any], join: Any
) -> None:
    _set(admin, amount_poisha=10_000)
    member = join(admin, "amma", "member")
    viewer = join(admin, "nana", "viewer")
    assert _item(_status(member))["budget_poisha"] == 10_000
    assert _item(_status(viewer))["budget_poisha"] == 10_000
    _set(member, 403, amount_poisha=1)
    assert member.get("/budgets/alerts").status_code == 403


def test_private_spending_is_hidden_from_members_but_counts_for_the_household(
    admin: Actor, home: dict[str, Any], join: Any
) -> None:
    abba = join(admin, "abba", "member")
    amma = join(admin, "amma", "member")
    secret = admin.create_account(
        "Abba's bKash", "mobile_wallet", owner_user_id=str(abba.user_id), is_private=True
    )
    _set(admin, amount_poisha=10_000)
    abba.create_tx(
        type="expense",
        amount_poisha=9_000,
        category_id=home["groceries"],
        account_id=secret,
        occurred_at=NOW.isoformat(),
    )
    assert _item(_status(amma))["spent_poisha"] == 0
    assert _item(_status(abba))["spent_poisha"] == 9_000
    assert _item(_status(admin))["spent_poisha"] == 9_000
    assert _alerts(admin) == [("", 80)]
