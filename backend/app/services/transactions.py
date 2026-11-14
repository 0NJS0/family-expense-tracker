"""Transaction creation, correction, voiding and listing.

- Creation is idempotent on (household_id, client_id). Replaying the same
  payload returns the existing transaction; reusing a client_id for different
  data is a conflict.
- Edits and voids require the caller's last-seen ``version`` (optimistic
  concurrency), so a stale device can never silently overwrite a newer change.
- Transactions are never deleted, only voided.
- Rows of the accounts involved are locked FOR SHARE so that an opening
  balance cannot be set concurrently with a conflicting backdated transaction.
"""

import hashlib
import json
import uuid
from dataclasses import dataclass
from datetime import UTC, date, datetime, timedelta
from enum import StrEnum
from typing import Any
from zoneinfo import ZoneInfo

from sqlalchemy import ColumnElement, Select, func, or_, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.errors import Conflict, Forbidden, NotFound, ValidationFailed
from app.models import (
    Account,
    Category,
    OpeningBalance,
    Role,
    Transaction,
    TransactionType,
)
from app.schemas.finance import TransactionCreate, TransactionUpdate, TransactionVoid
from app.services import audit, budgets, visibility
from app.services.context import HouseholdContext
from app.services.households import is_active_member
from app.timeutil import local_day_start, local_range_bounds, utcnow

# Occurrence times further ahead than this are almost certainly a wrong
# device clock or typo. A day of slack covers timezone confusion.
MAX_FUTURE_SKEW = timedelta(days=1)
EARLIEST_OCCURRENCE = datetime(2000, 1, 1, tzinfo=UTC)
_IDEMPOTENCY_CONSTRAINT = "uq_transactions_household_id_client_id"
_EDITABLE_FIELDS = (
    "amount_poisha",
    "category_id",
    "account_id",
    "destination_account_id",
    "occurred_at",
    "note",
    "attributed_user_id",
)


class SortOrder(StrEnum):
    NEWEST = "newest"
    LARGEST = "largest"


@dataclass(frozen=True)
class TransactionFilters:
    date_from: date | None = None
    date_to: date | None = None
    type: TransactionType | None = None
    account_id: uuid.UUID | None = None
    category_id: uuid.UUID | None = None
    member_id: uuid.UUID | None = None
    search: str | None = None
    include_voided: bool = False


@dataclass(frozen=True)
class _Fields:
    """The validated financial content of a transaction."""

    type: TransactionType
    amount_poisha: int
    category_id: uuid.UUID | None
    account_id: uuid.UUID
    destination_account_id: uuid.UUID | None
    occurred_at: datetime
    note: str | None
    attributed_user_id: uuid.UUID


def create_transaction(
    db: Session, ctx: HouseholdContext, data: TransactionCreate, *, commit: bool = True
) -> tuple[Transaction, bool]:
    """Returns (transaction, created). ``created`` is False on an idempotent replay.

    With ``commit=False`` the caller commits, together with its own changes
    (e.g. a confirmed recurring occurrence).
    """
    ctx.require(Role.MEMBER)
    if data.amount_poisha is None:  # the schema validator resolves the amount
        raise ValidationFailed("amount is required")
    fields = _Fields(
        type=data.type,
        amount_poisha=data.amount_poisha,
        category_id=data.category_id,
        account_id=data.account_id,
        destination_account_id=data.destination_account_id,
        occurred_at=data.occurred_at.astimezone(UTC),
        note=data.note or None,
        attributed_user_id=data.attributed_user_id or ctx.user_id,
    )
    fingerprint = _fingerprint(ctx.user_id, fields)

    existing = _find_by_client_id(db, ctx, data.client_id)
    if existing is not None:
        return _replay(existing, fingerprint), False

    _validate(db, ctx, fields, existing=None)
    transaction = Transaction(
        household_id=ctx.household_id,
        client_id=data.client_id,
        request_fingerprint=fingerprint,
        created_by_user_id=ctx.user_id,
        type=fields.type.value,
        amount_poisha=fields.amount_poisha,
        category_id=fields.category_id,
        account_id=fields.account_id,
        destination_account_id=fields.destination_account_id,
        occurred_at=fields.occurred_at,
        note=fields.note,
        attributed_user_id=fields.attributed_user_id,
    )
    db.add(transaction)
    try:
        db.flush()
    except IntegrityError as exc:
        db.rollback()
        if _constraint_name(exc) != _IDEMPOTENCY_CONSTRAINT:
            raise
        # A concurrent request with the same client_id won the race.
        winner = _find_by_client_id(db, ctx, data.client_id)
        if winner is None:  # pragma: no cover - the unique violation proves it exists
            raise
        return _replay(winner, fingerprint), False
    audit.record(
        db,
        action="transaction.created",
        entity_type="transaction",
        entity_id=transaction.id,
        household_id=ctx.household_id,
        actor_user_id=ctx.user_id,
        details=_audit_snapshot(transaction),
    )
    _check_budgets(db, ctx, transaction, transaction.occurred_at)
    if commit:
        db.commit()
    return transaction, True


def get_transaction(db: Session, ctx: HouseholdContext, transaction_id: uuid.UUID) -> Transaction:
    transaction = db.scalar(_owned(ctx, transaction_id))
    if transaction is None:
        raise NotFound("Transaction not found")
    return transaction


def update_transaction(
    db: Session, ctx: HouseholdContext, transaction_id: uuid.UUID, data: TransactionUpdate
) -> Transaction:
    transaction = _lock_for_change(db, ctx, transaction_id, data.version)
    current = _fields_of(transaction)
    changes: dict[str, Any] = {}
    for field in _EDITABLE_FIELDS:
        if field not in data.model_fields_set:
            continue
        new = getattr(data, field)
        if field == "occurred_at" and new is not None:
            new = new.astimezone(UTC)
        if field == "note":
            new = new or None
        elif new is None and field not in ("category_id", "destination_account_id"):
            raise ValidationFailed(f"{field} cannot be null")
        if new != getattr(current, field):
            changes[field] = new
    if not changes:
        return transaction

    updated = _Fields(**{**current.__dict__, **changes})
    _validate(db, ctx, updated, existing=transaction)
    audit_changes = {
        field: (
            {"changed": True}
            if field == "note"
            else {"from": _jsonable(getattr(current, field)), "to": _jsonable(value)}
        )
        for field, value in changes.items()
    }
    for field, value in changes.items():
        setattr(transaction, field, value)
    transaction.version += 1
    audit.record(
        db,
        action="transaction.updated",
        entity_type="transaction",
        entity_id=transaction.id,
        household_id=ctx.household_id,
        actor_user_id=ctx.user_id,
        details={"version": transaction.version, "changes": audit_changes},
    )
    _check_budgets(db, ctx, transaction, current.occurred_at, transaction.occurred_at)
    db.commit()
    return transaction


def void_transaction(
    db: Session, ctx: HouseholdContext, transaction_id: uuid.UUID, data: TransactionVoid
) -> Transaction:
    transaction = _lock_for_change(db, ctx, transaction_id, data.version)
    transaction.voided_at = utcnow()
    transaction.voided_by_user_id = ctx.user_id
    transaction.void_reason = data.reason
    transaction.version += 1
    audit.record(
        db,
        action="transaction.voided",
        entity_type="transaction",
        entity_id=transaction.id,
        household_id=ctx.household_id,
        actor_user_id=ctx.user_id,
        # The reason is free text like a note: it stays on the transaction, not in the audit.
        details={"version": transaction.version, "has_reason": bool(data.reason)},
    )
    db.commit()
    return transaction


def filter_conditions(
    ctx: HouseholdContext, filters: TransactionFilters
) -> list[ColumnElement[bool]]:
    """WHERE conditions shared by the transaction list and the CSV export."""
    conditions: list[ColumnElement[bool]] = [
        Transaction.household_id == ctx.household_id,
        visibility.transaction_visible(ctx),
    ]
    if not filters.include_voided:
        conditions.append(Transaction.voided_at.is_(None))
    if filters.date_from is not None:
        conditions.append(
            Transaction.occurred_at >= local_day_start(filters.date_from, ctx.household.timezone)
        )
    if filters.date_to is not None:
        _, end = local_range_bounds(filters.date_to, filters.date_to, ctx.household.timezone)
        conditions.append(Transaction.occurred_at < end)
    if filters.type is not None:
        conditions.append(Transaction.type == filters.type.value)
    if filters.account_id is not None:
        conditions.append(
            or_(
                Transaction.account_id == filters.account_id,
                Transaction.destination_account_id == filters.account_id,
            )
        )
    if filters.category_id is not None:
        conditions.append(Transaction.category_id == filters.category_id)
    if filters.member_id is not None:
        conditions.append(Transaction.attributed_user_id == filters.member_id)
    if filters.search:
        pattern = "%" + _escape_like(filters.search) + "%"
        conditions.append(Transaction.note.ilike(pattern, escape="\\"))
    return conditions


def list_transactions(
    db: Session,
    ctx: HouseholdContext,
    filters: TransactionFilters,
    *,
    limit: int,
    offset: int,
    sort: SortOrder = SortOrder.NEWEST,
) -> tuple[list[Transaction], int]:
    query = select(Transaction).where(*filter_conditions(ctx, filters))
    total = db.scalar(select(func.count()).select_from(query.subquery())) or 0
    newest = (Transaction.occurred_at.desc(), Transaction.created_at.desc(), Transaction.id)
    order = (Transaction.amount_poisha.desc(), *newest) if sort is SortOrder.LARGEST else newest
    rows = db.scalars(query.order_by(*order).limit(limit).offset(offset)).all()
    return list(rows), total


# --- internals --------------------------------------------------------------


def _owned(ctx: HouseholdContext, transaction_id: uuid.UUID) -> Select[Transaction]:
    # A transaction on someone else's private account is "not found", like a foreign one.
    return select(Transaction).where(
        Transaction.id == transaction_id,
        Transaction.household_id == ctx.household_id,
        visibility.transaction_visible(ctx),
    )


def _find_by_client_id(
    db: Session, ctx: HouseholdContext, client_id: uuid.UUID
) -> Transaction | None:
    return db.scalar(
        select(Transaction).where(
            Transaction.household_id == ctx.household_id, Transaction.client_id == client_id
        )
    )


def _replay(existing: Transaction, fingerprint: str) -> Transaction:
    if existing.request_fingerprint != fingerprint:
        raise Conflict(
            "client_id was already used for a different transaction",
            details=[{"transaction_id": str(existing.id)}],
        )
    return existing


def _lock_for_change(
    db: Session, ctx: HouseholdContext, transaction_id: uuid.UUID, expected_version: int
) -> Transaction:
    ctx.require(Role.MEMBER)
    transaction = db.scalar(_owned(ctx, transaction_id).with_for_update())
    if transaction is None:
        raise NotFound("Transaction not found")
    if not ctx.is_admin and transaction.created_by_user_id != ctx.user_id:
        raise Forbidden("Members can only change transactions they entered")
    if transaction.voided_at is not None:
        raise Conflict("Transaction is voided and cannot be changed")
    if transaction.version != expected_version:
        raise Conflict(
            "Transaction was changed by someone else; reload and try again",
            details=[{"current_version": transaction.version}],
        )
    return transaction


def _check_budgets(
    db: Session, ctx: HouseholdContext, transaction: Transaction, *instants: datetime
) -> None:
    """Spending changed in these months: record any budget threshold now crossed.
    (A void only lowers spending, so it can never cross one.)"""
    if transaction.type != TransactionType.EXPENSE.value:
        return
    zone = ZoneInfo(ctx.household.timezone)
    budgets.evaluate_alerts(
        db, ctx.household, {budgets.month_start(i.astimezone(zone).date()) for i in instants}
    )


def _fields_of(transaction: Transaction) -> _Fields:
    return _Fields(
        type=TransactionType(transaction.type),
        amount_poisha=transaction.amount_poisha,
        category_id=transaction.category_id,
        account_id=transaction.account_id,
        destination_account_id=transaction.destination_account_id,
        occurred_at=transaction.occurred_at,
        note=transaction.note,
        attributed_user_id=transaction.attributed_user_id,
    )


def _validate(
    db: Session, ctx: HouseholdContext, fields: _Fields, *, existing: Transaction | None
) -> None:
    """Service-level checks with clear messages. The database constraints are
    the backstop for anything that slips through."""
    now = utcnow()
    if fields.occurred_at > now + MAX_FUTURE_SKEW:
        raise ValidationFailed("Transaction date is too far in the future")
    if fields.occurred_at < EARLIEST_OCCURRENCE:
        raise ValidationFailed("Transaction date is too far in the past")

    if fields.type is TransactionType.TRANSFER:
        if fields.destination_account_id is None:
            raise ValidationFailed("A transfer needs a destination account")
        if fields.destination_account_id == fields.account_id:
            raise ValidationFailed("Source and destination accounts must be different")
        if fields.category_id is not None:
            raise ValidationFailed("Transfers do not have a category")
    else:
        if fields.destination_account_id is not None:
            raise ValidationFailed("Only transfers have a destination account")
        if fields.category_id is None:
            raise ValidationFailed(f"An {fields.type.value} needs a category")
        category = db.scalar(
            select(Category).where(
                Category.id == fields.category_id, Category.household_id == ctx.household_id
            )
        )
        if category is None:
            raise ValidationFailed("Category not found")
        if category.kind != fields.type.value:
            raise ValidationFailed(f"Category is not an {fields.type.value} category")
        unchanged = existing is not None and existing.category_id == category.id
        if category.is_archived and not unchanged:
            raise ValidationFailed("Category is archived")

    account_ids = [fields.account_id]
    if fields.destination_account_id is not None:
        account_ids.append(fields.destination_account_id)
    accounts = {
        account.id: account
        for account in db.scalars(
            select(Account)
            .where(
                Account.household_id == ctx.household_id,
                Account.id.in_(account_ids),
                visibility.account_visible(ctx),
            )
            .order_by(Account.id)  # consistent lock order avoids deadlocks
            .with_for_update(read=True)
        )
    }
    occurred_local = fields.occurred_at.astimezone(ZoneInfo(ctx.household.timezone)).date()
    for account_id in account_ids:
        account = accounts.get(account_id)
        if account is None:
            raise ValidationFailed("Account not found")
        previously_used = existing is not None and account_id in (
            existing.account_id,
            existing.destination_account_id,
        )
        if account.is_archived and not previously_used:
            raise ValidationFailed(f"Account '{account.name}' is archived")
        opening = db.get(OpeningBalance, account_id)
        if opening is not None and occurred_local < opening.as_of_date:
            raise ValidationFailed(
                f"Date is before the opening balance date of '{account.name}' "
                f"({opening.as_of_date.isoformat()})"
            )

    # Keeping an existing attribution to a since-deactivated member is allowed,
    # so their historical entries remain correctable.
    attribution_unchanged = (
        existing is not None and existing.attributed_user_id == fields.attributed_user_id
    )
    if not attribution_unchanged and not is_active_member(
        db, ctx.household_id, fields.attributed_user_id
    ):
        raise ValidationFailed("Attributed person must be an active member of this household")


def _fingerprint(creator_id: uuid.UUID, fields: _Fields) -> str:
    canonical = {
        "creator": str(creator_id),
        "type": fields.type.value,
        "amount_poisha": fields.amount_poisha,
        "category_id": str(fields.category_id) if fields.category_id else None,
        "account_id": str(fields.account_id),
        "destination_account_id": (
            str(fields.destination_account_id) if fields.destination_account_id else None
        ),
        "occurred_at": fields.occurred_at.astimezone(UTC).isoformat(),
        "note": fields.note,
        "attributed_user_id": str(fields.attributed_user_id),
    }
    return hashlib.sha256(json.dumps(canonical, sort_keys=True).encode()).hexdigest()


def _audit_snapshot(transaction: Transaction) -> dict[str, Any]:
    """Financial fields for the audit trail. The note is private free text and
    is deliberately not copied into audit records."""
    return {
        "client_id": str(transaction.client_id),
        "type": transaction.type,
        "amount_poisha": transaction.amount_poisha,
        "category_id": _jsonable(transaction.category_id),
        "account_id": _jsonable(transaction.account_id),
        "destination_account_id": _jsonable(transaction.destination_account_id),
        "occurred_at": _jsonable(transaction.occurred_at),
        "attributed_user_id": _jsonable(transaction.attributed_user_id),
    }


def _jsonable(value: object) -> object:
    if isinstance(value, uuid.UUID):
        return str(value)
    if isinstance(value, datetime):
        return value.astimezone(UTC).isoformat()
    return value


def _escape_like(text: str) -> str:
    return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")


def _constraint_name(exc: IntegrityError) -> str | None:
    diag = getattr(exc.orig, "diag", None)
    return getattr(diag, "constraint_name", None)
