"""Monthly budgets: actual spending against a budget, and threshold alerts.

Only expenses count (never transfers or income). Amounts are integer poisha and
the share used is integer basis points, rounded down. Months are calendar
months in the household's timezone.

Alerts are deterministic: whenever spending in a month changes (a transaction
is created, corrected or voided) or a budget is set, each budget's thresholds
(its warning level and 100%) are checked, and a crossed threshold is recorded
once. The unique index on budget_alerts makes a repeat impossible, so a
threshold crossed, un-crossed by a void and crossed again does not alert twice.
Alerts use the whole household's spending; private accounts are not hidden
from the household's own budget.
"""

import uuid
from dataclasses import dataclass
from datetime import date

from sqlalchemy import ColumnElement, func, literal_column, select, true
from sqlalchemy.dialects.postgresql import UUID, distinct_on, insert
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.errors import Conflict, NotFound, ValidationFailed
from app.models import (
    Budget,
    BudgetAlert,
    Category,
    CategoryKind,
    Household,
    Role,
    Transaction,
    TransactionType,
)
from app.schemas.budgets import BudgetSet, BudgetState, BudgetStatusItem, BudgetStatusOut
from app.services import audit, visibility
from app.services.context import HouseholdContext
from app.timeutil import local_day_start, local_today

_NIL = uuid.UUID(int=0)


def month_start(day: date) -> date:
    return day.replace(day=1)


def parse_month(text: str) -> date:
    year, month = text.split("-")
    return date(int(year), int(month), 1)


def next_month(month: date) -> date:
    return date(month.year + (month.month == 12), month.month % 12 + 1, 1)


# A literal, not a bound parameter: DISTINCT ON and ORDER BY must render the
# same expression, and two placeholders would differ (see AGENTS.md, reports).
_NIL_SQL = literal_column("'00000000-0000-0000-0000-000000000000'::uuid", UUID(as_uuid=True))


def _scope(column: ColumnElement[uuid.UUID] | None = None) -> ColumnElement[uuid.UUID]:
    return func.coalesce(Budget.category_id if column is None else column, _NIL_SQL)


@dataclass(frozen=True)
class _Active:
    category_id: uuid.UUID | None
    amount_poisha: int
    warn_percent: int
    effective_month: date


def _active_budgets(db: Session, household_id: uuid.UUID, month: date) -> list[_Active]:
    """The budget in force for each scope in ``month`` (latest setting at or before it)."""
    rows = db.scalars(
        select(Budget)
        .where(Budget.household_id == household_id, Budget.effective_month <= month)
        .ext(distinct_on(_scope()))
        .order_by(_scope(), Budget.effective_month.desc())
    ).all()
    return [
        _Active(row.category_id, row.amount_poisha, row.warn_percent, row.effective_month)
        for row in rows
        if row.amount_poisha is not None
    ]


def _spent(
    db: Session, household: Household, month: date, visible: ColumnElement[bool]
) -> tuple[int, dict[uuid.UUID, int]]:
    """(total expenses, expenses per category) in the month, excluding voided."""
    start = local_day_start(month, household.timezone)
    end = local_day_start(next_month(month), household.timezone)
    rows = db.execute(
        select(Transaction.category_id, func.sum(Transaction.amount_poisha))
        .where(
            Transaction.household_id == household.id,
            visible,
            Transaction.voided_at.is_(None),
            Transaction.type == TransactionType.EXPENSE.value,
            Transaction.occurred_at >= start,
            Transaction.occurred_at < end,
        )
        .group_by(Transaction.category_id)
    ).all()
    # SUM(bigint) is NUMERIC; int() of an integral Decimal is exact.
    per_category = {
        category_id: int(total) for category_id, total in rows if category_id is not None
    }
    return sum(per_category.values()), per_category


def _state(spent: int, budget: int, warn_percent: int) -> BudgetState:
    if spent > budget:
        return BudgetState.OVER
    if spent * 100 >= budget * warn_percent:
        return BudgetState.WARNING
    return BudgetState.OK


def status(db: Session, ctx: HouseholdContext, month: date | None) -> BudgetStatusOut:
    """Budget against actual spending for one month, as the caller may see it."""
    today = local_today(ctx.household.timezone)
    current = month_start(today)
    month = month or current
    active = _active_budgets(db, ctx.household_id, month)
    total, per_category = _spent(db, ctx.household, month, visibility.transaction_visible(ctx))
    names = {
        c.id: c
        for c in db.scalars(
            select(Category).where(
                Category.household_id == ctx.household_id,
                Category.id.in_([a.category_id for a in active if a.category_id is not None]),
            )
        )
    }
    items = []
    for a in active:
        spent = total if a.category_id is None else per_category.get(a.category_id, 0)
        category = names.get(a.category_id) if a.category_id is not None else None
        items.append(
            BudgetStatusItem(
                category_id=a.category_id,
                name_en=category.name_en if category else None,
                name_bn=category.name_bn if category else None,
                budget_poisha=a.amount_poisha,
                warn_percent=a.warn_percent,
                effective_month=a.effective_month,
                spent_poisha=spent,
                remaining_poisha=a.amount_poisha - spent,
                used_basis_points=spent * 10_000 // a.amount_poisha,
                state=_state(spent, a.amount_poisha, a.warn_percent),
            )
        )
    items.sort(key=lambda i: (i.category_id is not None, (i.name_en or "").lower()))
    return BudgetStatusOut(month=month, is_partial=month >= current, items=items)


def set_budget(db: Session, ctx: HouseholdContext, data: BudgetSet) -> BudgetStatusOut:
    """Set or remove a budget from a month on. Past months keep their budget."""
    ctx.require(Role.ADMIN)
    current = month_start(local_today(ctx.household.timezone))
    month = parse_month(data.from_month) if data.from_month else current
    if month < current:
        raise ValidationFailed("Budgets of past months cannot be changed")
    if data.category_id is not None:
        category = db.scalar(
            select(Category).where(
                Category.id == data.category_id, Category.household_id == ctx.household_id
            )
        )
        if category is None:
            raise ValidationFailed("Category not found")
        if category.kind != CategoryKind.EXPENSE.value:
            raise ValidationFailed("Budgets are for expense categories")
        if category.is_archived and not data.remove:
            raise ValidationFailed("Category is archived")
    if data.remove and not any(
        a.category_id == data.category_id for a in _active_budgets(db, ctx.household_id, month)
    ):
        raise NotFound("There is no budget to remove")

    row = db.scalar(
        select(Budget)
        .where(
            Budget.household_id == ctx.household_id,
            _scope() == (data.category_id or _NIL),
            Budget.effective_month == month,
        )
        .with_for_update()
    )
    if row is None:
        row = Budget(
            household_id=ctx.household_id,
            category_id=data.category_id,
            effective_month=month,
            created_by_user_id=ctx.user_id,
        )
        db.add(row)
    row.amount_poisha = None if data.remove else data.amount_poisha
    row.warn_percent = data.warn_percent
    try:
        db.flush()
    except IntegrityError as exc:  # a concurrent insert of the same month
        db.rollback()
        raise Conflict("The budget was changed at the same time; try again") from exc
    audit.record(
        db,
        action="budget.set",
        entity_type="budget",
        entity_id=row.id,
        household_id=ctx.household_id,
        actor_user_id=ctx.user_id,
        details={
            "category_id": str(data.category_id) if data.category_id else None,
            "from_month": month.isoformat(),
            "amount_poisha": row.amount_poisha,
            "warn_percent": row.warn_percent,
        },
    )
    evaluate_alerts(db, ctx.household, {month})
    db.commit()
    return status(db, ctx, month)


def evaluate_alerts(db: Session, household: Household, months: set[date]) -> None:
    """Record each newly crossed threshold once. Runs inside the caller's DB transaction."""
    # Sessions do not autoflush: write the caller's pending change before summing.
    db.flush()
    for month in months:
        active = _active_budgets(db, household.id, month)
        if not active:
            continue
        total, per_category = _spent(db, household, month, true())
        for a in active:
            spent = total if a.category_id is None else per_category.get(a.category_id, 0)
            for threshold in (a.warn_percent, 100):
                if spent * 100 >= a.amount_poisha * threshold:
                    db.execute(
                        insert(BudgetAlert)
                        .values(
                            household_id=household.id,
                            category_id=a.category_id,
                            month=month,
                            threshold_percent=threshold,
                            budget_poisha=a.amount_poisha,
                            spent_poisha=spent,
                        )
                        .on_conflict_do_nothing()
                    )


def list_alerts(
    db: Session, ctx: HouseholdContext, month: date | None
) -> list[tuple[BudgetAlert, Category | None]]:
    ctx.require(Role.ADMIN)
    query = (
        select(BudgetAlert, Category)
        .outerjoin(Category, Category.id == BudgetAlert.category_id)
        .where(BudgetAlert.household_id == ctx.household_id)
        .order_by(BudgetAlert.created_at.desc(), BudgetAlert.threshold_percent.desc())
    )
    if month is not None:
        query = query.where(BudgetAlert.month == month)
    return [(alert, category) for alert, category in db.execute(query.limit(200)).all()]
