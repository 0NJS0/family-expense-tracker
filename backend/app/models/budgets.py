"""Monthly budgets and their alerts (docs/accounting-rules.md, "Budgets").

A budget is a timeline per scope (the whole household, or one expense
category): each row says "from this month on, the budget is X" (or, with a NULL
amount, "no budget from this month"). The budget for a month is the latest row
at or before it, so changing a budget never rewrites past months.

An alert records that spending in a month reached a threshold of the budget. The
unique index makes each (scope, month, threshold) alert happen at most once.
"""

import uuid
from datetime import date, datetime

from sqlalchemy import (
    BigInteger,
    CheckConstraint,
    Date,
    ForeignKey,
    ForeignKeyConstraint,
    Index,
    SmallInteger,
    text,
)
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, created_at_column, updated_at_column, uuid_pk
from app.money import MAX_TRANSACTION_POISHA

# The whole-household budget has no category; indexes use this in its place.
_SCOPE = "COALESCE(category_id, '00000000-0000-0000-0000-000000000000'::uuid)"
MAX_BUDGET_POISHA = MAX_TRANSACTION_POISHA


class Budget(Base):
    __tablename__ = "budgets"
    __table_args__ = (
        CheckConstraint("EXTRACT(DAY FROM effective_month) = 1", name="first_of_month"),
        CheckConstraint(
            "amount_poisha IS NULL OR "
            f"(amount_poisha > 0 AND amount_poisha <= {MAX_BUDGET_POISHA})",
            name="amount_bounds",
        ),
        CheckConstraint("warn_percent BETWEEN 1 AND 99", name="warn_percent"),
        ForeignKeyConstraint(
            ["household_id", "category_id"],
            ["categories.household_id", "categories.id"],
            name="fk_budgets_category",
            ondelete="RESTRICT",
        ),
        Index(
            "uq_budgets_scope_month",
            "household_id",
            text(_SCOPE),
            "effective_month",
            unique=True,
        ),
    )

    id: Mapped[uuid.UUID] = uuid_pk()
    household_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("households.id", ondelete="RESTRICT"), nullable=False
    )
    # NULL: the overall household budget.
    category_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True))
    effective_month: Mapped[date] = mapped_column(Date, nullable=False)
    # NULL: no budget from this month on.
    amount_poisha: Mapped[int | None] = mapped_column(BigInteger)
    warn_percent: Mapped[int] = mapped_column(SmallInteger, nullable=False, server_default="80")
    created_by_user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    created_at: Mapped[datetime] = created_at_column()
    updated_at: Mapped[datetime] = updated_at_column()


class BudgetAlert(Base):
    __tablename__ = "budget_alerts"
    __table_args__ = (
        CheckConstraint("EXTRACT(DAY FROM month) = 1", name="first_of_month"),
        CheckConstraint("threshold_percent BETWEEN 1 AND 100", name="threshold"),
        ForeignKeyConstraint(
            ["household_id", "category_id"],
            ["categories.household_id", "categories.id"],
            name="fk_budget_alerts_category",
            ondelete="RESTRICT",
        ),
        Index(
            "uq_budget_alerts_once",
            "household_id",
            text(_SCOPE),
            "month",
            "threshold_percent",
            unique=True,
        ),
    )

    id: Mapped[uuid.UUID] = uuid_pk()
    household_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("households.id", ondelete="RESTRICT"), nullable=False
    )
    category_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True))
    month: Mapped[date] = mapped_column(Date, nullable=False)
    threshold_percent: Mapped[int] = mapped_column(SmallInteger, nullable=False)
    # What was known when the threshold was crossed (for the message).
    budget_poisha: Mapped[int] = mapped_column(BigInteger, nullable=False)
    spent_poisha: Mapped[int] = mapped_column(BigInteger, nullable=False)
    created_at: Mapped[datetime] = created_at_column()
