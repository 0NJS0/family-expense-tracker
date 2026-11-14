"""Accounts, opening balances, categories and transactions.

Integrity rules are enforced by PostgreSQL, not only by services:
- every reference is a composite (household_id, id) foreign key, so a
  transaction can never point at another household's account, category or member;
- the (household_id, category_id, type) foreign key into categories'
  (household_id, id, kind) makes an income category unusable for an expense
  and vice versa; transfers carry no category, so the key is not checked;
- amounts are positive bounded BIGINT poisha.
"""

import uuid
from datetime import date, datetime
from enum import StrEnum

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
    String,
    Text,
    UniqueConstraint,
    text,
)
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, created_at_column, updated_at_column, uuid_pk
from app.money import MAX_ABS_OPENING_BALANCE_POISHA, MAX_TRANSACTION_POISHA


class AccountKind(StrEnum):
    CASH = "cash"
    BANK = "bank"
    MOBILE_WALLET = "mobile_wallet"
    OTHER = "other"


class CategoryKind(StrEnum):
    INCOME = "income"
    EXPENSE = "expense"


class TransactionType(StrEnum):
    INCOME = "income"
    EXPENSE = "expense"
    TRANSFER = "transfer"


class Account(Base):
    __tablename__ = "accounts"
    __table_args__ = (
        UniqueConstraint("household_id", "id"),
        CheckConstraint("kind IN ('cash', 'bank', 'mobile_wallet', 'other')", name="kind"),
        # Only an account someone owns can be private (app/services/visibility.py).
        CheckConstraint("NOT is_private OR owner_user_id IS NOT NULL", name="private_has_owner"),
        ForeignKeyConstraint(
            ["household_id", "owner_user_id"],
            ["memberships.household_id", "memberships.user_id"],
            name="fk_accounts_owner_membership",
            ondelete="RESTRICT",
        ),
        Index(
            "uq_accounts_household_name",
            "household_id",
            text("lower(name)"),
            unique=True,
        ),
    )

    id: Mapped[uuid.UUID] = uuid_pk()
    household_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("households.id", ondelete="RESTRICT"), nullable=False
    )
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    kind: Mapped[str] = mapped_column(String(16), nullable=False)
    institution: Mapped[str | None] = mapped_column(String(100))
    # NULL owner means a shared household account.
    owner_user_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True))
    is_archived: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default=text("false"))
    # Private: visible only to the owner and admins.
    is_private: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default=text("false"))
    created_at: Mapped[datetime] = created_at_column()
    updated_at: Mapped[datetime] = updated_at_column()


class OpeningBalance(Base):
    """The balance of an account at the start of local day ``as_of_date``.

    At most one per account (primary key). It is not income and never appears
    in income/expense reports. There is no update path; corrections need a
    future audited workflow (see docs/accounting-rules.md).
    """

    __tablename__ = "opening_balances"
    __table_args__ = (
        CheckConstraint(
            f"abs(amount_poisha) <= {MAX_ABS_OPENING_BALANCE_POISHA}", name="amount_bounds"
        ),
        ForeignKeyConstraint(
            ["household_id", "account_id"],
            ["accounts.household_id", "accounts.id"],
            name="fk_opening_balances_account",
            ondelete="RESTRICT",
        ),
    )

    account_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    household_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    amount_poisha: Mapped[int] = mapped_column(BigInteger, nullable=False)
    as_of_date: Mapped[date] = mapped_column(Date, nullable=False)
    created_by_user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    created_at: Mapped[datetime] = created_at_column()


class Category(Base):
    __tablename__ = "categories"
    __table_args__ = (
        UniqueConstraint("household_id", "id"),
        UniqueConstraint("household_id", "id", "kind"),
        CheckConstraint("kind IN ('income', 'expense')", name="kind"),
        Index(
            "uq_categories_household_kind_name_en",
            "household_id",
            "kind",
            text("lower(name_en)"),
            unique=True,
        ),
    )

    id: Mapped[uuid.UUID] = uuid_pk()
    household_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("households.id", ondelete="RESTRICT"), nullable=False
    )
    kind: Mapped[str] = mapped_column(String(16), nullable=False)
    name_en: Mapped[str] = mapped_column(String(100), nullable=False)
    name_bn: Mapped[str] = mapped_column(String(100), nullable=False)
    is_default: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default=text("false"))
    is_archived: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default=text("false"))
    created_at: Mapped[datetime] = created_at_column()
    updated_at: Mapped[datetime] = updated_at_column()


class Transaction(Base):
    __tablename__ = "transactions"
    __table_args__ = (
        UniqueConstraint("household_id", "client_id"),
        # Target for composite foreign keys (receipts), like accounts and categories.
        UniqueConstraint("household_id", "id"),
        CheckConstraint("type IN ('income', 'expense', 'transfer')", name="type"),
        CheckConstraint(
            f"amount_poisha > 0 AND amount_poisha <= {MAX_TRANSACTION_POISHA}",
            name="amount_bounds",
        ),
        CheckConstraint(
            "(type = 'transfer' AND destination_account_id IS NOT NULL"
            " AND destination_account_id <> account_id AND category_id IS NULL)"
            " OR (type IN ('income', 'expense') AND destination_account_id IS NULL"
            " AND category_id IS NOT NULL)",
            name="shape",
        ),
        CheckConstraint(
            "(voided_at IS NULL AND void_reason IS NULL AND voided_by_user_id IS NULL)"
            " OR (voided_at IS NOT NULL AND void_reason IS NOT NULL"
            " AND voided_by_user_id IS NOT NULL)",
            name="void_consistency",
        ),
        CheckConstraint("version >= 1", name="version_positive"),
        ForeignKeyConstraint(
            ["household_id", "account_id"],
            ["accounts.household_id", "accounts.id"],
            name="fk_transactions_account",
            ondelete="RESTRICT",
        ),
        ForeignKeyConstraint(
            ["household_id", "destination_account_id"],
            ["accounts.household_id", "accounts.id"],
            name="fk_transactions_destination_account",
            ondelete="RESTRICT",
        ),
        ForeignKeyConstraint(
            ["household_id", "category_id", "type"],
            ["categories.household_id", "categories.id", "categories.kind"],
            name="fk_transactions_category_kind",
            ondelete="RESTRICT",
        ),
        ForeignKeyConstraint(
            ["household_id", "attributed_user_id"],
            ["memberships.household_id", "memberships.user_id"],
            name="fk_transactions_attributed_membership",
            ondelete="RESTRICT",
        ),
        Index("ix_transactions_household_occurred", "household_id", "occurred_at"),
        Index("ix_transactions_account_id", "account_id"),
        Index("ix_transactions_destination_account_id", "destination_account_id"),
        Index("ix_transactions_category_id", "category_id"),
        Index("ix_transactions_attributed_user_id", "attributed_user_id"),
        # Change feed for devices (app/services/sync.py).
        Index("ix_transactions_household_updated", "household_id", "updated_at", "id"),
    )

    id: Mapped[uuid.UUID] = uuid_pk()
    household_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("households.id", ondelete="RESTRICT"), nullable=False
    )
    # Client-generated idempotency key; retries with the same key never duplicate.
    client_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    # SHA-256 of the canonical creation payload, to detect a key reused for different data.
    request_fingerprint: Mapped[str] = mapped_column(String(64), nullable=False)
    created_by_user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    attributed_user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    type: Mapped[str] = mapped_column(String(16), nullable=False)
    amount_poisha: Mapped[int] = mapped_column(BigInteger, nullable=False)
    category_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True))
    account_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    destination_account_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True))
    occurred_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    note: Mapped[str | None] = mapped_column(Text)
    # Incremented on every change; edits and voids must quote the version they saw.
    version: Mapped[int] = mapped_column(Integer, nullable=False, server_default=text("1"))
    created_at: Mapped[datetime] = created_at_column()
    updated_at: Mapped[datetime] = updated_at_column()
    voided_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    voided_by_user_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT")
    )
    void_reason: Mapped[str | None] = mapped_column(String(500))
