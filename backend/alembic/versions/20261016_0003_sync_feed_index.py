"""index for the device change feed

Additive: one index on transactions (household_id, updated_at, id). No data
changes. Downgrading drops only the index.

Revision ID: 0003
Revises: 0002
Create Date: 2026-10-16 09:00:00+00:00
"""

from collections.abc import Sequence

from alembic import op

revision: str = "0003"
down_revision: str | None = "0002"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_index(
        "ix_transactions_household_updated",
        "transactions",
        ["household_id", "updated_at", "id"],
        unique=False,
    )


def downgrade() -> None:
    op.drop_index("ix_transactions_household_updated", table_name="transactions")
