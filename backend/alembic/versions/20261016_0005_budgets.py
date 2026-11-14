"""budgets and budget alerts

Additive: two new tables. No existing data changes. Downgrading drops the
budget settings and the alert history.

Revision ID: 0005
Revises: 0004
Create Date: 2026-10-16 13:00:00+00:00
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0005"
down_revision: str | None = "0004"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

_SCOPE = "COALESCE(category_id, '00000000-0000-0000-0000-000000000000'::uuid)"


def upgrade() -> None:
    op.create_table(
        "budgets",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("household_id", sa.UUID(), nullable=False),
        sa.Column("category_id", sa.UUID(), nullable=True),
        sa.Column("effective_month", sa.Date(), nullable=False),
        sa.Column("amount_poisha", sa.BigInteger(), nullable=True),
        sa.Column("warn_percent", sa.SmallInteger(), server_default="80", nullable=False),
        sa.Column("created_by_user_id", sa.UUID(), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "EXTRACT(DAY FROM effective_month) = 1", name=op.f("ck_budgets_first_of_month")
        ),
        sa.CheckConstraint(
            "amount_poisha IS NULL OR (amount_poisha > 0 AND amount_poisha <= 100000000000)",
            name=op.f("ck_budgets_amount_bounds"),
        ),
        sa.CheckConstraint("warn_percent BETWEEN 1 AND 99", name=op.f("ck_budgets_warn_percent")),
        sa.ForeignKeyConstraint(
            ["household_id"],
            ["households.id"],
            name=op.f("fk_budgets_household_id_households"),
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(
            ["created_by_user_id"],
            ["users.id"],
            name=op.f("fk_budgets_created_by_user_id_users"),
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(
            ["household_id", "category_id"],
            ["categories.household_id", "categories.id"],
            name="fk_budgets_category",
            ondelete="RESTRICT",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_budgets")),
    )
    op.create_index(
        "uq_budgets_scope_month",
        "budgets",
        ["household_id", sa.text(_SCOPE), "effective_month"],
        unique=True,
    )
    op.create_table(
        "budget_alerts",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("household_id", sa.UUID(), nullable=False),
        sa.Column("category_id", sa.UUID(), nullable=True),
        sa.Column("month", sa.Date(), nullable=False),
        sa.Column("threshold_percent", sa.SmallInteger(), nullable=False),
        sa.Column("budget_poisha", sa.BigInteger(), nullable=False),
        sa.Column("spent_poisha", sa.BigInteger(), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "EXTRACT(DAY FROM month) = 1", name=op.f("ck_budget_alerts_first_of_month")
        ),
        sa.CheckConstraint(
            "threshold_percent BETWEEN 1 AND 100", name=op.f("ck_budget_alerts_threshold")
        ),
        sa.ForeignKeyConstraint(
            ["household_id"],
            ["households.id"],
            name=op.f("fk_budget_alerts_household_id_households"),
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(
            ["household_id", "category_id"],
            ["categories.household_id", "categories.id"],
            name="fk_budget_alerts_category",
            ondelete="RESTRICT",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_budget_alerts")),
    )
    op.create_index(
        "uq_budget_alerts_once",
        "budget_alerts",
        ["household_id", sa.text(_SCOPE), "month", "threshold_percent"],
        unique=True,
    )


def downgrade() -> None:
    op.drop_index("uq_budget_alerts_once", table_name="budget_alerts")
    op.drop_table("budget_alerts")
    op.drop_index("uq_budgets_scope_month", table_name="budgets")
    op.drop_table("budgets")
