"""Recurring rules and their occurrences (rent, salary, bills).

A rule never moves money by itself. Each due date becomes one occurrence
(unique per rule and date, so generating twice is harmless); an occurrence only
becomes a transaction when someone confirms it, or automatically when the rule
explicitly says ``auto_post``.
"""

import uuid
from datetime import date, datetime

from sqlalchemy import (
    BigInteger,
    Boolean,
    CheckConstraint,
    Date,
    DateTime,
    ForeignKey,
    ForeignKeyConstraint,
    Index,
    Integer,
    SmallInteger,
    String,
    UniqueConstraint,
    text,
)
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, created_at_column, updated_at_column, uuid_pk
from app.money import MAX_TRANSACTION_POISHA


class RecurringRule(Base):
    __tablename__ = "recurring_rules"
    __table_args__ = (
        UniqueConstraint("household_id", "id"),
        CheckConstraint("type IN ('income', 'expense', 'transfer')", name="type"),
        CheckConstraint("frequency IN ('weekly', 'monthly', 'yearly')", name="frequency"),
        CheckConstraint("interval BETWEEN 1 AND 12", name="interval"),
        CheckConstraint(
            f"amount_poisha > 0 AND amount_poisha <= {MAX_TRANSACTION_POISHA}", name="amount_bounds"
        ),
        CheckConstraint("end_date IS NULL OR end_date >= start_date", name="dates"),
        CheckConstraint(
            "(type = 'transfer' AND destination_account_id IS NOT NULL AND category_id IS NULL "
            "AND destination_account_id <> account_id) OR "
            "(type <> 'transfer' AND destination_account_id IS NULL AND category_id IS NOT NULL)",
            name="shape",
        ),
        ForeignKeyConstraint(
            ["household_id", "account_id"],
            ["accounts.household_id", "accounts.id"],
            name="fk_recurring_rules_account",
            ondelete="RESTRICT",
        ),
        ForeignKeyConstraint(
            ["household_id", "destination_account_id"],
            ["accounts.household_id", "accounts.id"],
            name="fk_recurring_rules_destination",
            ondelete="RESTRICT",
        ),
        ForeignKeyConstraint(
            ["household_id", "category_id", "type"],
            ["categories.household_id", "categories.id", "categories.kind"],
            name="fk_recurring_rules_category_kind",
            ondelete="RESTRICT",
        ),
        ForeignKeyConstraint(
            ["household_id", "attributed_user_id"],
            ["memberships.household_id", "memberships.user_id"],
            name="fk_recurring_rules_attributed_membership",
            ondelete="RESTRICT",
        ),
        Index("ix_recurring_rules_household_due", "household_id", "next_due_date"),
    )

    id: Mapped[uuid.UUID] = uuid_pk()
    household_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("households.id", ondelete="RESTRICT"), nullable=False
    )
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    type: Mapped[str] = mapped_column(String(16), nullable=False)
    amount_poisha: Mapped[int] = mapped_column(BigInteger, nullable=False)
    category_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True))
    account_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    destination_account_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True))
    attributed_user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    note: Mapped[str | None] = mapped_column(String(500))
    frequency: Mapped[str] = mapped_column(String(16), nullable=False)
    interval: Mapped[int] = mapped_column(SmallInteger, nullable=False, server_default="1")
    start_date: Mapped[date] = mapped_column(Date, nullable=False)
    end_date: Mapped[date | None] = mapped_column(Date)
    # Post a transaction without asking. Off unless the admin chose it.
    auto_post: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default=text("false"))
    paused_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    # Index (0-based) and date of the next occurrence not yet generated;
    # NULL date once the rule has ended.
    next_index: Mapped[int] = mapped_column(Integer, nullable=False, server_default="0")
    next_due_date: Mapped[date | None] = mapped_column(Date)
    last_processed_date: Mapped[date | None] = mapped_column(Date)
    version: Mapped[int] = mapped_column(Integer, nullable=False, server_default="1")
    created_by_user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    created_at: Mapped[datetime] = created_at_column()
    updated_at: Mapped[datetime] = updated_at_column()


class RecurringOccurrence(Base):
    __tablename__ = "recurring_occurrences"
    __table_args__ = (
        UniqueConstraint("rule_id", "due_date", name="uq_recurring_occurrences_rule_date"),
        CheckConstraint("status IN ('pending', 'posted', 'skipped')", name="status"),
        CheckConstraint(
            "(status = 'posted') = (transaction_id IS NOT NULL)", name="posted_has_transaction"
        ),
        ForeignKeyConstraint(
            ["household_id", "rule_id"],
            ["recurring_rules.household_id", "recurring_rules.id"],
            name="fk_recurring_occurrences_rule",
            ondelete="RESTRICT",
        ),
        ForeignKeyConstraint(
            ["transaction_id"],
            ["transactions.id"],
            name="fk_recurring_occurrences_transaction",
            ondelete="RESTRICT",
        ),
        Index("ix_recurring_occurrences_household_status", "household_id", "status"),
    )

    id: Mapped[uuid.UUID] = uuid_pk()
    household_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    rule_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    due_date: Mapped[date] = mapped_column(Date, nullable=False)
    status: Mapped[str] = mapped_column(String(16), nullable=False, server_default="pending")
    transaction_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True))
    resolved_by_user_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT")
    )
    resolved_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = created_at_column()
