"""Excel export: same columns and filters as CSV, exact amounts, no formulas."""

import io
import zipfile
from datetime import date
from decimal import Decimal
from typing import Any

from openpyxl import load_workbook
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import AuditEvent
from app.services.exports import COLUMNS
from tests.conftest import Actor, at


def _xlsx(actor: Actor, **params: Any) -> tuple[Any, bytes]:
    response = actor.get("/transactions/export.xlsx", params=params)
    assert response.status_code == 200, response.text
    assert response.headers["content-type"].startswith(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    )
    assert response.headers["cache-control"] == "no-store"
    assert 'filename="transactions-' in response.headers["content-disposition"]
    return response, response.content


def _sheet_xml(data: bytes) -> str:
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        name = next(n for n in z.namelist() if n.startswith("xl/worksheets/sheet"))
        return z.read(name).decode("utf-8")


def test_columns_amounts_and_bangla_are_exact(admin: Actor) -> None:
    # নগদ is one of the default accounts of every new household.
    cash = next(a["id"] for a in admin.get("/accounts").json() if a["name"] == "নগদ")
    groceries = admin.category_id("Groceries")
    admin.create_tx(
        type="expense",
        amount_poisha=125_075,
        category_id=groceries,
        account_id=cash,
        occurred_at=at("2026-10-15T23:30:00"),
        note="চাল ও ডাল",
    )
    admin.create_tx(
        type="expense",
        amount_poisha=7,
        category_id=groceries,
        account_id=cash,
        occurred_at=at("2026-10-16T08:00:00"),
    )
    _, data = _xlsx(admin)
    sheet = load_workbook(io.BytesIO(data)).active
    rows = list(sheet.iter_rows(values_only=True))
    assert list(rows[0]) == COLUMNS
    first = dict(zip(COLUMNS, rows[1], strict=True))
    assert first["amount_poisha"] == 125_075
    assert Decimal(str(first["amount_taka"])) == Decimal("1250.75")
    assert first["local_date"].date() == date(2026, 10, 15)  # Dhaka date, not UTC
    assert first["account"] == "নগদ" and first["note"] == "চাল ও ডাল"
    assert dict(zip(COLUMNS, rows[2], strict=True))["amount_poisha"] == 7
    xml = _sheet_xml(data)
    # The file stores the exact digits, not a binary float rendering.
    assert "<v>1250.75</v>" in xml and "<v>0.07</v>" in xml
    assert "0.0700000" not in xml  # openpyxl's Decimal-to-float path once wrote this
    assert sheet.cell(row=2, column=COLUMNS.index("amount_taka") + 1).number_format == "#,##0.00"


def test_text_that_looks_like_a_formula_stays_text(admin: Actor) -> None:
    cash = admin.create_account("Cash")
    admin.create_tx(
        type="expense",
        amount_poisha=100,
        category_id=admin.category_id("Groceries"),
        account_id=cash,
        occurred_at=at("2026-10-15T12:00:00"),
        note='=HYPERLINK("http://evil.example","click")',
    )
    _, data = _xlsx(admin)
    xml = _sheet_xml(data)
    assert "<f>" not in xml  # no formula anywhere in the sheet
    sheet = load_workbook(io.BytesIO(data)).active
    note = sheet.cell(row=2, column=COLUMNS.index("note") + 1)
    assert note.value == '=HYPERLINK("http://evil.example","click")'
    assert note.data_type == "s"


def test_filters_privacy_and_audit_match_the_csv(admin: Actor, join: Any, db: Session) -> None:
    abba = join(admin, "abba", "member")
    amma = join(admin, "amma", "member")
    shared = admin.create_account("Shared")
    secret = admin.create_account("Abba wallet", owner_user_id=str(abba.user_id), is_private=True)
    groceries = admin.category_id("Groceries")
    admin.create_tx(
        type="expense",
        amount_poisha=100,
        category_id=groceries,
        account_id=shared,
        occurred_at=at("2026-09-15T12:00:00"),
    )
    admin.create_tx(
        type="expense",
        amount_poisha=200,
        category_id=groceries,
        account_id=shared,
        occurred_at=at("2026-10-15T12:00:00"),
    )
    abba.create_tx(
        type="expense",
        amount_poisha=300,
        category_id=groceries,
        account_id=secret,
        occurred_at=at("2026-10-15T12:00:00"),
    )

    def amounts(actor: Actor, **params: Any) -> list[int]:
        _, data = _xlsx(actor, **params)
        sheet = load_workbook(io.BytesIO(data)).active
        col = COLUMNS.index("amount_poisha")
        return sorted(r[col] for r in list(sheet.iter_rows(values_only=True))[1:])

    assert amounts(admin, date_from="2026-10-01", date_to="2026-10-31") == [200, 300]
    assert amounts(amma) == [100, 200]  # someone else's private account is left out
    event = db.scalars(
        select(AuditEvent).where(AuditEvent.action == "transactions.exported")
    ).all()[-1]
    assert event.details["format"] == "xlsx"


def test_needs_login(admin: Actor) -> None:
    assert admin.client.get("/api/v1/transactions/export.xlsx").status_code == 401
