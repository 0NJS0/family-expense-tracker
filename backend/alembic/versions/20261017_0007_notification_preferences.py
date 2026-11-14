"""notification preferences

Additive: one new table. Downgrading drops people's reminder choices (the
defaults apply again).

Revision ID: 0007
Revises: 0006
Create Date: 2026-10-17 09:00:00+00:00
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0007"
down_revision: str | None = "0006"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "notification_preferences",
        sa.Column("household_id", sa.UUID(), nullable=False),
        sa.Column("user_id", sa.UUID(), nullable=False),
        sa.Column("daily_reminder", sa.Boolean(), server_default=sa.text("false"), nullable=False),
        sa.Column(
            "missing_entry_reminder", sa.Boolean(), server_default=sa.text("true"), nullable=False
        ),
        sa.Column("reminder_time", sa.Time(), server_default=sa.text("'20:00'"), nullable=False),
        sa.Column("recurring_due", sa.Boolean(), server_default=sa.text("true"), nullable=False),
        sa.Column("budget_alerts", sa.Boolean(), server_default=sa.text("true"), nullable=False),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.ForeignKeyConstraint(
            ["household_id", "user_id"],
            ["memberships.household_id", "memberships.user_id"],
            name="fk_notification_preferences_membership",
            ondelete="RESTRICT",
        ),
        sa.PrimaryKeyConstraint(
            "household_id", "user_id", name=op.f("pk_notification_preferences")
        ),
    )


def downgrade() -> None:
    op.drop_table("notification_preferences")
