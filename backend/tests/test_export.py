import csv
import io
from typing import Any

import pytest
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import AuditEvent
from tests.conftest import Actor, at


@pytest.fixture
def book(admin: Actor) -> dict[str, str]:
    return {
        "cash": admin.create_account("Cash"),
        "bank": admin.create_account("Bank", "bank"),
        "groceries": admin.category_id("Groceries"),
    }


def _export(actor: Actor, **params: Any) -> tuple[Any, list[dict[str, str]]]:
    response = actor.get("/transactions/export.csv", params=params)
    assert response.status_code == 200, response.text
    text = response.content.decode("utf-8")
    assert text.startswith("﻿")
    return response, list(csv.DictReader(io.StringIO(text.removeprefix("﻿"))))


def _expense(actor: Actor, book: dict[str, str], amount: int, when: str, note: str | None = None):  # type: ignore[no-untyped-def]
    return actor.create_tx(
        type="expense",
        amount_poisha=amount,
        category_id=book["groceries"],
        account_id=book["cash"],
        occurred_at=at(when),
        note=note,
    )


def test_export_contents(admin: Actor, book: dict[str, str]) -> None:
    _expense(admin, book, 120_50, "2026-03-02T23:30:00", note='rice, dal and "oil"\nsecond line')
    admin.create_tx(
        type="transfer",
        amount_poisha=1_000_00,
        account_id=book["cash"],
        destination_account_id=book["bank"],
        occurred_at=at("2026-03-01T09:00:00"),
    )
    response, rows = _export(admin)
    assert response.headers["content-type"].startswith("text/csv")
    assert "attachment" in response.headers["content-disposition"]
    assert response.headers["cache-control"] == "no-store"

    assert [r["type"] for r in rows] == ["transfer", "expense"]  # chronological
    transfer, expense = rows
    assert expense["amount_taka"] == "120.50"
    assert expense["amount_poisha"] == "12050"
    assert expense["category_en"] == "Groceries" and expense["category_bn"] == "বাজার"
    assert expense["account"] == "Cash" and expense["destination_account"] == ""
    # Times are in Dhaka: 23:30 local stays on 2 March even though it is 17:30 UTC.
    assert expense["local_date"] == "2026-03-02"
    assert expense["occurred_at_local"].startswith("2026-03-02T23:30:00+06:00")
    assert expense["note"] == 'rice, dal and "oil"\nsecond line'  # quoting round-trips
    assert expense["attributed_to"] == expense["entered_by"] == "Admin"
    assert transfer["destination_account"] == "Bank" and transfer["category_en"] == ""


def test_export_neutralises_spreadsheet_formulas(admin: Actor, book: dict[str, str]) -> None:
    for i, note in enumerate(
        ['=HYPERLINK("http://evil","click")', "+1+1", "-2", "@SUM(A1)", "plain"]
    ):
        _expense(admin, book, 100, f"2026-03-0{i + 1}T09:00:00", note=note)
    _, rows = _export(admin)
    notes = [r["note"] for r in rows]
    assert notes == [
        '\'=HYPERLINK("http://evil","click")',
        "'+1+1",
        "'-2",
        "'@SUM(A1)",
        "plain",
    ]


def test_export_applies_filters_and_excludes_voided(admin: Actor, book: dict[str, str]) -> None:
    keep = _expense(admin, book, 100, "2026-03-05T09:00:00")
    _expense(admin, book, 200, "2026-04-05T09:00:00")
    gone = _expense(admin, book, 300, "2026-03-06T09:00:00")
    admin.post(f"/transactions/{gone['id']}/void", {"version": 1, "reason": "dup"})

    _, march = _export(admin, date_from="2026-03-01", date_to="2026-03-31")
    assert [r["transaction_id"] for r in march] == [keep["id"]]
    _, with_voided = _export(
        admin, date_from="2026-03-01", date_to="2026-03-31", include_voided=True
    )
    voided = next(r for r in with_voided if r["transaction_id"] == gone["id"])
    assert voided["voided_at"] and voided["void_reason"] == "dup"


def test_export_is_audited_and_household_scoped(
    admin: Actor,
    book: dict[str, str],
    make_actor,
    db: Session,  # type: ignore[no-untyped-def]
) -> None:
    _expense(admin, book, 100, "2026-03-05T09:00:00", note="private")
    other: Actor = make_actor("neighbour", "Neighbours")
    _, rows = _export(other)
    assert rows == []
    _export(admin, date_from="2026-03-01")
    event = db.scalars(
        select(AuditEvent).where(
            AuditEvent.action == "transactions.exported",
            AuditEvent.household_id == admin.household_id,
        )
    ).one()
    assert event.actor_user_id == admin.user_id
    assert event.details["row_count"] == 1 and event.details["date_from"] == "2026-03-01"


def test_viewer_can_export_but_anonymous_cannot(admin: Actor, book: dict[str, str], join) -> None:  # type: ignore[no-untyped-def]
    _expense(admin, book, 100, "2026-03-05T09:00:00")
    viewer: Actor = join(admin, "nana", "viewer")
    _, rows = _export(viewer)
    assert len(rows) == 1
    assert admin.client.get("/api/v1/transactions/export.csv").status_code == 401
