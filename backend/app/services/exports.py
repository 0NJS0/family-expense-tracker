"""CSV and Excel (XLSX) export of transactions.

Amounts are written twice: as exact integer poisha, and as taka formatted with
integer arithmetic (never floats). In CSV, text a spreadsheet would treat as a
formula is prefixed with a quote; in XLSX, every text cell is stored as text,
so it can never become a formula.
"""

import csv
import io
from datetime import date, datetime
from typing import Any
from zoneinfo import ZoneInfo

from openpyxl import Workbook
from openpyxl.cell import WriteOnlyCell
from openpyxl.cell.cell import Cell
from openpyxl.styles import Font
from sqlalchemy import func, select
from sqlalchemy.orm import Session, aliased

from app.errors import ValidationFailed
from app.models import Account, Category, Transaction, User
from app.money import format_poisha
from app.services import audit
from app.services.context import HouseholdContext
from app.services.transactions import TransactionFilters, filter_conditions

MAX_EXPORT_ROWS = 100_000
_FORMULA_PREFIXES = ("=", "+", "-", "@", "\t", "\r")

COLUMNS = [
    "transaction_id",
    "local_date",
    "occurred_at_local",
    "type",
    "amount_taka",
    "amount_poisha",
    "category_en",
    "category_bn",
    "account",
    "destination_account",
    "attributed_to",
    "entered_by",
    "note",
    "voided_at",
    "void_reason",
    "created_at",
]


def _rows(db: Session, ctx: HouseholdContext, filters: TransactionFilters) -> list[Any]:
    conditions = filter_conditions(ctx, filters)
    count = db.scalar(select(func.count()).select_from(Transaction).where(*conditions)) or 0
    if count > MAX_EXPORT_ROWS:
        raise ValidationFailed(
            f"Export has {count} rows (limit {MAX_EXPORT_ROWS}); narrow the date range"
        )

    source = aliased(Account)
    destination = aliased(Account)
    attributed = aliased(User)
    creator = aliased(User)
    rows = db.execute(
        select(
            Transaction,
            Category.name_en,
            Category.name_bn,
            source.name,
            destination.name,
            attributed.display_name,
            creator.display_name,
        )
        .join(source, source.id == Transaction.account_id)
        .outerjoin(destination, destination.id == Transaction.destination_account_id)
        .outerjoin(Category, Category.id == Transaction.category_id)
        .join(attributed, attributed.id == Transaction.attributed_user_id)
        .join(creator, creator.id == Transaction.created_by_user_id)
        .where(*conditions)
        .order_by(Transaction.occurred_at, Transaction.created_at, Transaction.id)
    ).all()
    return list(rows)


def _record_export(
    db: Session, ctx: HouseholdContext, filters: TransactionFilters, count: int, kind: str
) -> None:
    # Exporting the household's books is sensitive; keep a record of who did it.
    audit.record(
        db,
        action="transactions.exported",
        entity_type="household",
        entity_id=ctx.household_id,
        household_id=ctx.household_id,
        actor_user_id=ctx.user_id,
        details={
            "format": kind,
            "row_count": count,
            "date_from": filters.date_from.isoformat() if filters.date_from else None,
            "date_to": filters.date_to.isoformat() if filters.date_to else None,
            "include_voided": filters.include_voided,
        },
    )
    db.commit()


def export_transactions_csv(db: Session, ctx: HouseholdContext, filters: TransactionFilters) -> str:
    rows = _rows(db, ctx, filters)
    tz = ZoneInfo(ctx.household.timezone)
    buffer = io.StringIO()
    # BOM so Excel opens UTF-8 (Bangla) correctly.
    buffer.write("﻿")
    writer = csv.writer(buffer, lineterminator="\r\n")
    writer.writerow(COLUMNS)
    for tx, cat_en, cat_bn, src_name, dst_name, attributed_name, creator_name in rows:
        local = tx.occurred_at.astimezone(tz)
        writer.writerow(
            [
                str(tx.id),
                local.date().isoformat(),
                local.isoformat(),
                tx.type,
                format_poisha(tx.amount_poisha),
                tx.amount_poisha,
                _safe(cat_en),
                _safe(cat_bn),
                _safe(src_name),
                _safe(dst_name),
                _safe(attributed_name),
                _safe(creator_name),
                _safe(tx.note),
                _iso(tx.voided_at, tz),
                _safe(tx.void_reason),
                _iso(tx.created_at, tz),
            ]
        )

    _record_export(db, ctx, filters, len(rows), "csv")
    return buffer.getvalue()


def export_transactions_xlsx(
    db: Session, ctx: HouseholdContext, filters: TransactionFilters
) -> bytes:
    """The same columns as the CSV, as an Excel workbook.

    amount_taka is a number whose exact digits are written into the file (e.g.
    "0.07"), shown with two decimals and grouping; amount_poisha is an exact
    integer. Dates are real dates, so Excel can filter and sort them.
    """
    rows = _rows(db, ctx, filters)
    tz = ZoneInfo(ctx.household.timezone)
    workbook = Workbook(write_only=True)
    sheet = workbook.create_sheet("Transactions")
    sheet.freeze_panes = "A2"

    def text(value: str | None) -> Cell:
        cell = WriteOnlyCell(sheet, value=value or "")
        cell.data_type = "s"  # never a formula, even if it starts with "="
        return cell

    def taka(poisha: int) -> Cell:
        # openpyxl turns a Decimal into a float (0.07 became 0.07000000000000001),
        # so the exact digits are written as a numeric cell directly.
        cell = WriteOnlyCell(sheet, value=format_poisha(poisha))
        cell.data_type = "n"
        cell.number_format = "#,##0.00"
        return cell

    def whole(value: int) -> Cell:
        cell = WriteOnlyCell(sheet, value=value)
        cell.number_format = "0"
        return cell

    def day(value: date) -> Cell:
        cell = WriteOnlyCell(sheet)
        cell.value = value
        cell.number_format = "yyyy-mm-dd"
        return cell

    header = [text(name) for name in COLUMNS]
    for cell in header:
        cell.font = Font(bold=True)
    sheet.append(header)
    for tx, cat_en, cat_bn, src_name, dst_name, attributed_name, creator_name in rows:
        local = tx.occurred_at.astimezone(tz)
        sheet.append(
            [
                text(str(tx.id)),
                day(local.date()),
                text(local.isoformat()),
                text(tx.type),
                taka(tx.amount_poisha),
                whole(tx.amount_poisha),
                text(cat_en),
                text(cat_bn),
                text(src_name),
                text(dst_name),
                text(attributed_name),
                text(creator_name),
                text(tx.note),
                text(_iso(tx.voided_at, tz)),
                text(tx.void_reason),
                text(_iso(tx.created_at, tz)),
            ]
        )
    out = io.BytesIO()
    workbook.save(out)
    _record_export(db, ctx, filters, len(rows), "xlsx")
    return out.getvalue()


def _safe(value: str | None) -> str:
    """Prefix text that a spreadsheet would evaluate as a formula (CSV injection)."""
    if not value:
        return ""
    return "'" + value if value.startswith(_FORMULA_PREFIXES) else value


def _iso(value: datetime | None, tz: ZoneInfo) -> str:
    return value.astimezone(tz).isoformat() if value else ""
