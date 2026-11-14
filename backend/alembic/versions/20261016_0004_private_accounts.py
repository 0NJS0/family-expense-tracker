"""private accounts

Additive: accounts.is_private (NOT NULL, default false, so every existing
account stays shared/visible exactly as before) and a CHECK that only an owned
account can be private. Downgrading drops the flag, making every account
visible to all members again.

Revision ID: 0004
Revises: 0003
Create Date: 2026-10-16 11:00:00+00:00
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0004"
down_revision: str | None = "0003"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "accounts",
        sa.Column("is_private", sa.Boolean(), server_default=sa.text("false"), nullable=False),
    )
    op.create_check_constraint(
        op.f("ck_accounts_private_has_owner"),
        "accounts",
        "NOT is_private OR owner_user_id IS NOT NULL",
    )


def downgrade() -> None:
    op.drop_constraint(op.f("ck_accounts_private_has_owner"), "accounts", type_="check")
    op.drop_column("accounts", "is_private")
