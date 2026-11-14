"""recurring rules and occurrences

Additive: two new tables. No existing data changes. Downgrading drops the
rules and their occurrence history (transactions already posted stay).

Revision ID: 0006
Revises: 0005
Create Date: 2026-10-16 15:00:00+00:00
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0006"
down_revision: str | None = "0005"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def _timestamp(name: str) -> sa.Column:  # type: ignore[type-arg]
    return sa.Column(
        name, sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False
    )


def upgrade() -> None:
    op.create_table(
        "recurring_rules",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("household_id", sa.UUID(), nullable=False),
        sa.Column("name", sa.String(length=100), nullable=False),
        sa.Column("type", sa.String(length=16), nullable=False),
        sa.Column("amount_poisha", sa.BigInteger(), nullable=False),
        sa.Column("category_id", sa.UUID(), nullable=True),
        sa.Column("account_id", sa.UUID(), nullable=False),
        sa.Column("destination_account_id", sa.UUID(), nullable=True),
        sa.Column("attributed_user_id", sa.UUID(), nullable=False),
        sa.Column("note", sa.String(length=500), nullable=True),
        sa.Column("frequency", sa.String(length=16), nullable=False),
        sa.Column("interval", sa.SmallInteger(), server_default="1", nullable=False),
        sa.Column("start_date", sa.Date(), nullable=False),
        sa.Column("end_date", sa.Date(), nullable=True),
        sa.Column("auto_post", sa.Boolean(), server_default=sa.text("false"), nullable=False),
        sa.Column("paused_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("next_index", sa.Integer(), server_default="0", nullable=False),
        sa.Column("next_due_date", sa.Date(), nullable=True),
        sa.Column("last_processed_date", sa.Date(), nullable=True),
        sa.Column("version", sa.Integer(), server_default="1", nullable=False),
        sa.Column("created_by_user_id", sa.UUID(), nullable=False),
        _timestamp("created_at"),
        _timestamp("updated_at"),
        sa.CheckConstraint(
            "type IN ('income', 'expense', 'transfer')", name=op.f("ck_recurring_rules_type")
        ),
        sa.CheckConstraint(
            "frequency IN ('weekly', 'monthly', 'yearly')",
            name=op.f("ck_recurring_rules_frequency"),
        ),
        sa.CheckConstraint("interval BETWEEN 1 AND 12", name=op.f("ck_recurring_rules_interval")),
        sa.CheckConstraint(
            "amount_poisha > 0 AND amount_poisha <= 100000000000",
            name=op.f("ck_recurring_rules_amount_bounds"),
        ),
        sa.CheckConstraint(
            "end_date IS NULL OR end_date >= start_date", name=op.f("ck_recurring_rules_dates")
        ),
        sa.CheckConstraint(
            "(type = 'transfer' AND destination_account_id IS NOT NULL AND category_id IS NULL "
            "AND destination_account_id <> account_id) OR "
            "(type <> 'transfer' AND destination_account_id IS NULL AND category_id IS NOT NULL)",
            name=op.f("ck_recurring_rules_shape"),
        ),
        sa.ForeignKeyConstraint(
            ["household_id"],
            ["households.id"],
            name=op.f("fk_recurring_rules_household_id_households"),
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(
            ["created_by_user_id"],
            ["users.id"],
            name=op.f("fk_recurring_rules_created_by_user_id_users"),
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(
            ["household_id", "account_id"],
            ["accounts.household_id", "accounts.id"],
            name="fk_recurring_rules_account",
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(
            ["household_id", "destination_account_id"],
            ["accounts.household_id", "accounts.id"],
            name="fk_recurring_rules_destination",
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(
            ["household_id", "category_id", "type"],
            ["categories.household_id", "categories.id", "categories.kind"],
            name="fk_recurring_rules_category_kind",
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(
            ["household_id", "attributed_user_id"],
            ["memberships.household_id", "memberships.user_id"],
            name="fk_recurring_rules_attributed_membership",
            ondelete="RESTRICT",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_recurring_rules")),
        sa.UniqueConstraint("household_id", "id", name=op.f("uq_recurring_rules_household_id_id")),
    )
    op.create_index(
        "ix_recurring_rules_household_due",
        "recurring_rules",
        ["household_id", "next_due_date"],
        unique=False,
    )
    op.create_table(
        "recurring_occurrences",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("household_id", sa.UUID(), nullable=False),
        sa.Column("rule_id", sa.UUID(), nullable=False),
        sa.Column("due_date", sa.Date(), nullable=False),
        sa.Column("status", sa.String(length=16), server_default="pending", nullable=False),
        sa.Column("transaction_id", sa.UUID(), nullable=True),
        sa.Column("resolved_by_user_id", sa.UUID(), nullable=True),
        sa.Column("resolved_at", sa.DateTime(timezone=True), nullable=True),
        _timestamp("created_at"),
        sa.CheckConstraint(
            "status IN ('pending', 'posted', 'skipped')",
            name=op.f("ck_recurring_occurrences_status"),
        ),
        sa.CheckConstraint(
            "(status = 'posted') = (transaction_id IS NOT NULL)",
            name=op.f("ck_recurring_occurrences_posted_has_transaction"),
        ),
        sa.ForeignKeyConstraint(
            ["household_id", "rule_id"],
            ["recurring_rules.household_id", "recurring_rules.id"],
            name="fk_recurring_occurrences_rule",
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(
            ["transaction_id"],
            ["transactions.id"],
            name="fk_recurring_occurrences_transaction",
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(
            ["resolved_by_user_id"],
            ["users.id"],
            name=op.f("fk_recurring_occurrences_resolved_by_user_id_users"),
            ondelete="RESTRICT",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_recurring_occurrences")),
        sa.UniqueConstraint("rule_id", "due_date", name="uq_recurring_occurrences_rule_date"),
    )
    op.create_index(
        "ix_recurring_occurrences_household_status",
        "recurring_occurrences",
        ["household_id", "status"],
        unique=False,
    )


def downgrade() -> None:
    op.drop_index("ix_recurring_occurrences_household_status", table_name="recurring_occurrences")
    op.drop_table("recurring_occurrences")
    op.drop_index("ix_recurring_rules_household_due", table_name="recurring_rules")
    op.drop_table("recurring_rules")
