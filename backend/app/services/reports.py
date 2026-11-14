"""Household and individual reports. See docs/accounting-rules.md.

Each non-voided transaction is counted exactly once. Transfers are excluded
from income and expense. "Individual" reports filter on the person the
transaction is attributed to, not on who entered it or who owns the account.
"""

import calendar
import uuid
from collections import defaultdict
from dataclasses import dataclass
from datetime import date, timedelta
from fractions import Fraction

from sqlalchemy import Date, cast, func, select, text
from sqlalchemy.orm import Session

from app.errors import ValidationFailed
from app.models import Category, Transaction, TransactionType, User
from app.schemas.reports import (
    CategoryChange,
    CategoryTotal,
    CompareTo,
    Comparison,
    ComparisonMode,
    Granularity,
    MemberTotal,
    SummaryOut,
    TimeseriesBucket,
    TimeseriesOut,
)
from app.services import visibility
from app.services.context import HouseholdContext
from app.services.households import is_member
from app.timeutil import local_range_bounds, local_today

MAX_RANGE_DAYS = 366 * 20
MAX_BUCKETS = 1000


@dataclass(frozen=True)
class ReportRange:
    date_from: date
    date_to: date
    member_id: uuid.UUID | None


def resolve_range(
    ctx: HouseholdContext,
    date_from: date | None,
    date_to: date | None,
    member_id: uuid.UUID | None,
    db: Session,
) -> ReportRange:
    """Defaults to the current month to date in the household timezone."""
    today = local_today(ctx.household.timezone)
    date_to = date_to or today
    date_from = date_from or date_to.replace(day=1)
    if date_from > date_to:
        raise ValidationFailed("date_from must be on or before date_to")
    if (date_to - date_from).days > MAX_RANGE_DAYS:
        raise ValidationFailed("Date range is too long")
    if member_id is not None and not is_member(db, ctx.household_id, member_id):
        raise ValidationFailed("member_id is not a member of this household")
    return ReportRange(date_from, date_to, member_id)


@dataclass(frozen=True)
class _PeriodTotals:
    income: int
    expense: int
    transfer: int
    count: int
    # category_id -> (total_poisha, transaction_count)
    per_category: dict[uuid.UUID, tuple[int, int]]


def _period_totals(
    db: Session, ctx: HouseholdContext, date_from: date, date_to: date, member_id: uuid.UUID | None
) -> _PeriodTotals:
    start, end = local_range_bounds(date_from, date_to, ctx.household.timezone)
    query = (
        select(
            Transaction.type,
            Transaction.category_id,
            func.sum(Transaction.amount_poisha),
            func.count(),
        )
        .where(
            Transaction.household_id == ctx.household_id,
            visibility.transaction_visible(ctx),
            Transaction.voided_at.is_(None),
            Transaction.occurred_at >= start,
            Transaction.occurred_at < end,
        )
        .group_by(Transaction.type, Transaction.category_id)
    )
    if member_id is not None:
        query = query.where(Transaction.attributed_user_id == member_id)
    totals: dict[str, int] = defaultdict(int)
    count = 0
    per_category: dict[uuid.UUID, tuple[int, int]] = {}
    for tx_type, category_id, amount, n in db.execute(query).all():
        # SUM(bigint) is NUMERIC; int() of an integral Decimal is exact.
        totals[tx_type] += int(amount)
        count += n
        if category_id is not None:
            per_category[category_id] = (int(amount), n)
    return _PeriodTotals(
        income=totals[TransactionType.INCOME.value],
        expense=totals[TransactionType.EXPENSE.value],
        transfer=totals[TransactionType.TRANSFER.value],
        count=count,
        per_category=per_category,
    )


def previous_period(
    date_from: date, date_to: date, compare_to: CompareTo = CompareTo.PREVIOUS_PERIOD
) -> tuple[date, date, ComparisonMode]:
    """The period a range is compared against (see docs/accounting-rules.md)."""
    if compare_to is CompareTo.PREVIOUS_YEAR:
        return (
            _shift_months(date_from, -12),
            _shift_months(date_to, -12),
            ComparisonMode.PREVIOUS_YEAR,
        )
    if date_from.day == 1:
        months = (date_to.year - date_from.year) * 12 + date_to.month - date_from.month + 1
        prev_from = _shift_months(date_from, -months)
        if date_to == _month_end(date_to):
            prev_to = _month_end(_shift_months(date_to.replace(day=1), -months))
        else:
            prev_to = _shift_months(date_to, -months)
        return prev_from, prev_to, ComparisonMode.CALENDAR_MONTHS
    length = (date_to - date_from).days + 1
    return (
        date_from - timedelta(days=length),
        date_from - timedelta(days=1),
        ComparisonMode.PRECEDING_DAYS,
    )


def summary(
    db: Session,
    ctx: HouseholdContext,
    rng: ReportRange,
    compare_to: CompareTo = CompareTo.PREVIOUS_PERIOD,
) -> SummaryOut:
    tz = ctx.household.timezone
    current = _period_totals(db, ctx, rng.date_from, rng.date_to, rng.member_id)
    prev_from, prev_to, mode = previous_period(rng.date_from, rng.date_to, compare_to)
    previous = _period_totals(db, ctx, prev_from, prev_to, rng.member_id)

    category_ids = current.per_category.keys() | previous.per_category.keys()
    categories = (
        {
            c.id: c
            for c in db.scalars(
                select(Category).where(
                    Category.household_id == ctx.household_id, Category.id.in_(category_ids)
                )
            )
        }
        if category_ids
        else {}
    )

    breakdown: dict[str, list[CategoryTotal]] = {"income": [], "expense": []}
    for category_id, (amount, n) in current.per_category.items():
        category = categories[category_id]
        breakdown[category.kind].append(
            CategoryTotal(
                category_id=category.id,
                kind=category.kind,
                name_en=category.name_en,
                name_bn=category.name_bn,
                total_poisha=amount,
                transaction_count=n,
            )
        )
    for items in breakdown.values():
        items.sort(key=lambda item: (-item.total_poisha, item.name_en))

    changes = []
    for category_id in category_ids:
        category = categories[category_id]
        now = current.per_category.get(category_id, (0, 0))[0]
        before = previous.per_category.get(category_id, (0, 0))[0]
        changes.append(
            CategoryChange(
                category_id=category.id,
                kind=category.kind,
                name_en=category.name_en,
                name_bn=category.name_bn,
                current_poisha=now,
                previous_poisha=before,
                change_poisha=now - before,
            )
        )
    changes.sort(key=lambda c: (-abs(c.change_poisha), c.kind, c.name_en))

    return SummaryOut(
        date_from=rng.date_from,
        date_to=rng.date_to,
        timezone=tz,
        member_id=rng.member_id,
        is_partial=rng.date_to >= local_today(tz),
        income_poisha=current.income,
        expense_poisha=current.expense,
        net_poisha=current.income - current.expense,
        transfer_poisha=current.transfer,
        transaction_count=current.count,
        expense_by_category=breakdown["expense"],
        income_by_category=breakdown["income"],
        by_member=_member_totals(db, ctx, rng),
        comparison=Comparison(
            mode=mode,
            date_from=prev_from,
            date_to=prev_to,
            income_poisha=previous.income,
            expense_poisha=previous.expense,
            net_poisha=previous.income - previous.expense,
            transfer_poisha=previous.transfer,
            transaction_count=previous.count,
            income_change_poisha=current.income - previous.income,
            expense_change_poisha=current.expense - previous.expense,
            net_change_poisha=(current.income - current.expense)
            - (previous.income - previous.expense),
            income_change_basis_points=_basis_points(current.income, previous.income),
            expense_change_basis_points=_basis_points(current.expense, previous.expense),
            category_changes=changes,
        ),
    )


def _member_totals(db: Session, ctx: HouseholdContext, rng: ReportRange) -> list[MemberTotal]:
    """Income/expense per attributed person (transfers excluded), largest spender first."""
    start, end = local_range_bounds(rng.date_from, rng.date_to, ctx.household.timezone)
    query = (
        select(
            Transaction.attributed_user_id,
            User.display_name,
            Transaction.type,
            func.sum(Transaction.amount_poisha),
            func.count(),
        )
        .join(User, User.id == Transaction.attributed_user_id)
        .where(
            Transaction.household_id == ctx.household_id,
            visibility.transaction_visible(ctx),
            Transaction.voided_at.is_(None),
            Transaction.type.in_([TransactionType.INCOME.value, TransactionType.EXPENSE.value]),
            Transaction.occurred_at >= start,
            Transaction.occurred_at < end,
        )
        .group_by(Transaction.attributed_user_id, User.display_name, Transaction.type)
    )
    if rng.member_id is not None:
        query = query.where(Transaction.attributed_user_id == rng.member_id)
    people: dict[uuid.UUID, dict[str, int | str]] = {}
    for user_id, name, tx_type, amount, n in db.execute(query).all():
        row = people.setdefault(user_id, {"name": name, "income": 0, "expense": 0, "count": 0})
        row[tx_type] = int(row[tx_type]) + int(amount)
        row["count"] = int(row["count"]) + n
    totals = [
        MemberTotal(
            user_id=user_id,
            display_name=str(row["name"]),
            income_poisha=int(row["income"]),
            expense_poisha=int(row["expense"]),
            transaction_count=int(row["count"]),
        )
        for user_id, row in people.items()
    ]
    return sorted(totals, key=lambda m: (-m.expense_poisha, m.display_name))


def _basis_points(current: int, previous: int) -> int | None:
    """Exact relative change in basis points, rounded half-to-even."""
    if previous == 0:
        return None
    return round(Fraction((current - previous) * 10_000, previous))


def _month_end(day: date) -> date:
    return day.replace(day=calendar.monthrange(day.year, day.month)[1])


def _shift_months(day: date, months: int) -> date:
    """Move by whole months, clamping the day to the target month's length."""
    index = day.year * 12 + day.month - 1 + months
    year, month = divmod(index, 12)
    last = calendar.monthrange(year, month + 1)[1]
    return date(year, month + 1, min(day.day, last))


def timeseries(
    db: Session, ctx: HouseholdContext, rng: ReportRange, granularity: Granularity
) -> TimeseriesOut:
    tz = ctx.household.timezone
    week_start = ctx.household.week_start
    periods = list(_periods(rng.date_from, rng.date_to, granularity, week_start))
    if len(periods) > MAX_BUCKETS:
        raise ValidationFailed(
            f"Too many {granularity.value} buckets; choose a shorter range or coarser granularity"
        )

    start, end = local_range_bounds(rng.date_from, rng.date_to, tz)
    local_day = cast(func.timezone(tz, Transaction.occurred_at), Date)
    query = (
        select(local_day, Transaction.type, func.sum(Transaction.amount_poisha))
        .where(
            Transaction.household_id == ctx.household_id,
            visibility.transaction_visible(ctx),
            Transaction.voided_at.is_(None),
            Transaction.type.in_([TransactionType.INCOME.value, TransactionType.EXPENSE.value]),
            Transaction.occurred_at >= start,
            Transaction.occurred_at < end,
        )
        # Positional GROUP BY: the timezone is a bound parameter, and repeating
        # the expression would render a second placeholder PostgreSQL treats
        # as a different expression.
        .group_by(text("1"), text("2"))
    )
    if rng.member_id is not None:
        query = query.where(Transaction.attributed_user_id == rng.member_id)

    sums: dict[date, dict[str, int]] = defaultdict(lambda: defaultdict(int))
    for day, tx_type, amount in db.execute(query).all():
        sums[_period_start(day, granularity, week_start)][tx_type] += int(amount)

    today = local_today(tz)
    buckets = []
    for natural_start, natural_end in periods:
        income = sums[natural_start][TransactionType.INCOME.value]
        expense = sums[natural_start][TransactionType.EXPENSE.value]
        clipped_start = max(natural_start, rng.date_from)
        clipped_end = min(natural_end, rng.date_to)
        buckets.append(
            TimeseriesBucket(
                start=clipped_start,
                end=clipped_end,
                income_poisha=income,
                expense_poisha=expense,
                net_poisha=income - expense,
                is_partial=(
                    clipped_start != natural_start
                    or clipped_end != natural_end
                    or natural_end >= today
                ),
            )
        )
    return TimeseriesOut(
        granularity=granularity,
        date_from=rng.date_from,
        date_to=rng.date_to,
        timezone=tz,
        week_start=week_start,
        member_id=rng.member_id,
        buckets=buckets,
    )


def _period_start(day: date, granularity: Granularity, week_start: int) -> date:
    match granularity:
        case Granularity.DAY:
            return day
        case Granularity.WEEK:
            return day - timedelta(days=(day.weekday() - week_start) % 7)
        case Granularity.MONTH:
            return day.replace(day=1)
        case Granularity.YEAR:
            return day.replace(month=1, day=1)


def _period_end(start: date, granularity: Granularity) -> date:
    match granularity:
        case Granularity.DAY:
            return start
        case Granularity.WEEK:
            return start + timedelta(days=6)
        case Granularity.MONTH:
            return start.replace(day=calendar.monthrange(start.year, start.month)[1])
        case Granularity.YEAR:
            return start.replace(month=12, day=31)


def _periods(
    date_from: date, date_to: date, granularity: Granularity, week_start: int
) -> list[tuple[date, date]]:
    """Every natural period overlapping [date_from, date_to], including empty ones."""
    periods = []
    current = _period_start(date_from, granularity, week_start)
    while current <= date_to:
        end = _period_end(current, granularity)
        periods.append((current, end))
        if len(periods) > MAX_BUCKETS:
            break
        current = end + timedelta(days=1)
    return periods
