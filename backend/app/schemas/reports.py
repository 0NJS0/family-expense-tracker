import uuid
from datetime import date, datetime
from enum import StrEnum
from typing import Any

from app.models import CategoryKind
from app.schemas.common import ApiModel


class Granularity(StrEnum):
    DAY = "day"
    WEEK = "week"
    MONTH = "month"
    YEAR = "year"


class CategoryTotal(ApiModel):
    category_id: uuid.UUID
    kind: CategoryKind
    name_en: str
    name_bn: str
    total_poisha: int
    transaction_count: int


class CompareTo(StrEnum):
    PREVIOUS_PERIOD = "previous_period"
    PREVIOUS_YEAR = "previous_year"


class ComparisonMode(StrEnum):
    # Range starts on the 1st: same days of the preceding month(s), e.g. 1-15 Oct vs 1-15 Sep.
    CALENDAR_MONTHS = "calendar_months"
    # Otherwise: a window of equal length immediately before the range.
    PRECEDING_DAYS = "preceding_days"
    # Requested explicitly: the same dates one year earlier (29 Feb -> 28 Feb).
    PREVIOUS_YEAR = "previous_year"


class CategoryChange(ApiModel):
    category_id: uuid.UUID
    kind: CategoryKind
    name_en: str
    name_bn: str
    current_poisha: int
    previous_poisha: int
    change_poisha: int


class Comparison(ApiModel):
    """Totals for the previous period and the change from it to the current one.

    ``*_change_basis_points`` is the relative change in 1/100ths of a percent
    (1250 = +12.5%), rounded half-to-even, or null when the previous value is 0.
    """

    mode: ComparisonMode
    date_from: date
    date_to: date
    income_poisha: int
    expense_poisha: int
    net_poisha: int
    transfer_poisha: int
    transaction_count: int
    income_change_poisha: int
    expense_change_poisha: int
    net_change_poisha: int
    income_change_basis_points: int | None
    expense_change_basis_points: int | None
    # Union of categories used in either period, largest absolute change first.
    category_changes: list[CategoryChange]


class MemberTotal(ApiModel):
    """Income and spending attributed to one person (household reports only)."""

    user_id: uuid.UUID
    display_name: str
    income_poisha: int
    expense_poisha: int
    transaction_count: int


class SummaryOut(ApiModel):
    date_from: date
    date_to: date
    timezone: str
    member_id: uuid.UUID | None
    # True when the range includes today or a future date, i.e. totals may still change.
    is_partial: bool
    income_poisha: int
    expense_poisha: int
    net_poisha: int
    # Informational only; transfers are neither income nor expense.
    transfer_poisha: int
    transaction_count: int
    expense_by_category: list[CategoryTotal]
    income_by_category: list[CategoryTotal]
    # Per attributed person; each transaction counts once, so these sum to the totals.
    by_member: list[MemberTotal]
    comparison: Comparison


class TimeseriesBucket(ApiModel):
    start: date
    end: date
    income_poisha: int
    expense_poisha: int
    net_poisha: int
    # True when the bucket is cut off by the requested range or not yet over.
    is_partial: bool


class TimeseriesOut(ApiModel):
    granularity: Granularity
    date_from: date
    date_to: date
    timezone: str
    week_start: int
    member_id: uuid.UUID | None
    buckets: list[TimeseriesBucket]


class AuditEventOut(ApiModel):
    id: uuid.UUID
    actor_user_id: uuid.UUID | None
    action: str
    entity_type: str
    entity_id: uuid.UUID | None
    details: dict[str, Any]
    created_at: datetime
