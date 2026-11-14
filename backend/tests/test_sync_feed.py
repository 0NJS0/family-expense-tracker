"""The device change feed: every change reaches every phone, exactly once per version."""

import uuid
from datetime import timedelta
from typing import Any

from sqlalchemy import text
from sqlalchemy.orm import Session

from app.services.sync import OVERLAP, Cursor
from tests.conftest import Actor, at

WHEN = at("2026-10-15T12:00:00")


def _feed(actor: Actor, cursor: str | None = None, limit: int = 200) -> dict[str, Any]:
    params: dict[str, Any] = {"limit": limit}
    if cursor:
        params["cursor"] = cursor
    response = actor.get("/sync/changes", params=params)
    assert response.status_code == 200, response.text
    result: dict[str, Any] = response.json()
    return result


def _drain(
    actor: Actor, cursor: str | None = None, limit: int = 200
) -> tuple[list[dict[str, Any]], str]:
    """Follow the feed to its end, as the phone does."""
    items: list[dict[str, Any]] = []
    while True:
        page = _feed(actor, cursor, limit)
        items += page["items"]
        cursor = page["next_cursor"]
        if not page["has_more"]:
            return items, cursor


def _expense(actor: Actor, cash: str, amount: int = 1000) -> dict[str, Any]:
    return actor.create_tx(
        type="expense",
        amount_poisha=amount,
        category_id=actor.category_id("Groceries"),
        account_id=cash,
        occurred_at=WHEN,
    )


def _age(db: Session, tx_id: str, minutes: int) -> None:
    """Pretend a row changed ``minutes`` ago (tests cannot wait for the overlap window)."""
    db.execute(
        text(
            "UPDATE transactions SET updated_at = now() - make_interval(mins => :m) WHERE id = :id"
        ),
        {"m": minutes, "id": tx_id},
    )
    db.commit()


def test_feed_pages_through_everything_once_in_change_order(admin: Actor) -> None:
    cash = admin.create_account("Cash")
    created = [_expense(admin, cash, 100 + i)["id"] for i in range(7)]
    items, _ = _drain(admin, limit=3)
    assert [t["id"] for t in items] == created
    assert all(t["created_by_user_id"] == str(admin.user_id) for t in items)


def test_edits_and_voids_made_elsewhere_reach_the_phone(admin: Actor, db: Session) -> None:
    cash = admin.create_account("Cash")
    a = _expense(admin, cash)
    b = _expense(admin, cash)
    _age(db, a["id"], 30)
    _age(db, b["id"], 30)
    _, cursor = _drain(admin)
    # Nothing new: an empty page that keeps the cursor.
    page = _feed(admin, cursor)
    assert page["items"] == [] and page["has_more"] is False and page["next_cursor"] == cursor

    edited = admin.patch(f"/transactions/{a['id']}", {"version": 1, "amount_poisha": 5000}).json()
    voided = admin.post(f"/transactions/{b['id']}/void", {"version": 1, "reason": "twice"}).json()
    items, _ = _drain(admin, cursor)
    by_id = {t["id"]: t for t in items}
    assert (
        by_id[a["id"]]["amount_poisha"] == 5000
        and by_id[a["id"]]["version"] == edited["version"] == 2
    )
    assert (
        by_id[b["id"]]["voided_at"] is not None
        and by_id[b["id"]]["version"] == voided["version"] == 2
    )


def test_end_cursor_steps_back_so_late_commits_are_not_missed(admin: Actor, db: Session) -> None:
    cash = admin.create_account("Cash")
    seen = _expense(admin, cash)
    _, cursor = _drain(admin)
    # The row is recent, so the cursor stays at the settled boundary, before it.
    boundary = _updated_at(db, seen["id"]) - OVERLAP + timedelta(seconds=5)
    assert Cursor.decode(cursor).updated_at <= boundary

    # A transaction that started earlier commits only now, with an older updated_at.
    late = _expense(admin, cash)
    db.execute(
        text("UPDATE transactions SET updated_at = :t WHERE id = :id"),
        {"t": _updated_at(db, seen["id"]) - timedelta(seconds=2), "id": late["id"]},
    )
    db.commit()
    items, _ = _drain(admin, cursor)
    assert late["id"] in [t["id"] for t in items]


def _updated_at(db: Session, tx_id: str) -> Any:
    return db.execute(
        text("SELECT updated_at FROM transactions WHERE id = :id"), {"id": tx_id}
    ).scalar_one()


def test_recent_changes_are_sent_again_harmlessly_then_settle(admin: Actor, db: Session) -> None:
    cash = admin.create_account("Cash")
    tx = _expense(admin, cash)
    _, cursor = _drain(admin)
    again, cursor = _drain(admin, cursor)
    assert [t["id"] for t in again] == [tx["id"]]  # same version: the phone's merge ignores it
    _age(db, tx["id"], 10)
    _, cursor = _drain(admin, cursor)
    assert _feed(admin, cursor)["items"] == []


def test_feed_is_household_scoped(admin: Actor, make_actor: Any) -> None:
    other = make_actor("neighbour", "Next door")
    _expense(other, other.create_account("Their cash"))
    items, _ = _drain(admin)
    assert items == []


def test_members_and_viewers_can_read_the_feed(admin: Actor, join: Any) -> None:
    _expense(admin, admin.create_account("Cash"))
    for role in ("member", "viewer"):
        actor = join(admin, f"{role}-user", role)
        items, _ = _drain(actor)
        assert len(items) == 1


def test_bad_cursor_is_rejected(admin: Actor) -> None:
    for bad in (
        "nonsense",
        "djI6MTox",
        Cursor(Cursor.decode(_feed(admin)["next_cursor"]).updated_at, uuid.uuid4()).encode() + "!!",
    ):
        assert admin.get("/sync/changes", params={"cursor": bad}).status_code == 422


def test_feed_requires_login(client: Any) -> None:
    assert client.get("/api/v1/sync/changes").status_code == 401
