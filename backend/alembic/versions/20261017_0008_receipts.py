"""receipts

Additive: a receipts table, and a unique (household_id, id) key on
transactions for its composite foreign key (always satisfied, since id is the
primary key). Downgrading drops the receipt records (files stay on disk).

Revision ID: 0008
Revises: 0007
Create Date: 2026-10-17 11:00:00+00:00
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0008"
down_revision: str | None = "0007"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_unique_constraint(
        op.f("uq_transactions_household_id_id"), "transactions", ["household_id", "id"]
    )
    op.create_table(
        "receipts",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("household_id", sa.UUID(), nullable=False),
        sa.Column("transaction_id", sa.UUID(), nullable=False),
        sa.Column("uploaded_by_user_id", sa.UUID(), nullable=False),
        sa.Column("content_type", sa.String(length=32), nullable=False),
        sa.Column("size_bytes", sa.Integer(), nullable=False),
        sa.Column("upload_sha256", sa.String(length=64), nullable=False),
        sa.Column("stored_sha256", sa.String(length=64), nullable=False),
        sa.Column("storage_key", sa.String(length=200), nullable=False),
        sa.Column("ocr_status", sa.String(length=16), server_default="pending", nullable=False),
        sa.Column("ocr_text", sa.Text(), nullable=True),
        sa.Column("ocr_amount_poisha", sa.BigInteger(), nullable=True),
        sa.Column("ocr_date", sa.Date(), nullable=True),
        sa.Column("ocr_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("deleted_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("deleted_by_user_id", sa.UUID(), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "content_type IN ('image/jpeg', 'application/pdf')",
            name=op.f("ck_receipts_content_type"),
        ),
        sa.CheckConstraint(
            "ocr_status IN ('pending', 'done', 'failed', 'unavailable')",
            name=op.f("ck_receipts_ocr_status"),
        ),
        sa.CheckConstraint("size_bytes > 0", name=op.f("ck_receipts_size")),
        sa.ForeignKeyConstraint(
            ["household_id", "transaction_id"],
            ["transactions.household_id", "transactions.id"],
            name="fk_receipts_transaction",
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(
            ["uploaded_by_user_id"],
            ["users.id"],
            name=op.f("fk_receipts_uploaded_by_user_id_users"),
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(
            ["deleted_by_user_id"],
            ["users.id"],
            name=op.f("fk_receipts_deleted_by_user_id_users"),
            ondelete="RESTRICT",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_receipts")),
        sa.UniqueConstraint("storage_key", name=op.f("uq_receipts_storage_key")),
    )
    op.create_index(
        "uq_receipts_transaction_upload",
        "receipts",
        ["transaction_id", "upload_sha256"],
        unique=True,
        postgresql_where=sa.text("deleted_at IS NULL"),
    )
    op.create_index(
        "ix_receipts_ocr_pending",
        "receipts",
        ["ocr_status"],
        unique=False,
        postgresql_where=sa.text("ocr_status = 'pending'"),
    )


def downgrade() -> None:
    op.drop_index("ix_receipts_ocr_pending", table_name="receipts")
    op.drop_index("uq_receipts_transaction_upload", table_name="receipts")
    op.drop_table("receipts")
    op.drop_constraint(op.f("uq_transactions_household_id_id"), "transactions", type_="unique")
