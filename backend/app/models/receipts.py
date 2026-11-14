"""Receipt files attached to transactions.

The file lives in private storage (``Settings.receipts_dir``); the row records
where, what it is and its checksum. Images are re-encoded on upload, which
removes metadata such as the phone's location. OCR results are suggestions
only: they never change a transaction by themselves.
"""

import uuid
from datetime import date, datetime

from sqlalchemy import (
    BigInteger,
    CheckConstraint,
    Date,
    DateTime,
    ForeignKey,
    ForeignKeyConstraint,
    Index,
    Integer,
    String,
    Text,
    text,
)
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, created_at_column, uuid_pk


class Receipt(Base):
    __tablename__ = "receipts"
    __table_args__ = (
        CheckConstraint("content_type IN ('image/jpeg', 'application/pdf')", name="content_type"),
        CheckConstraint(
            "ocr_status IN ('pending', 'done', 'failed', 'unavailable')", name="ocr_status"
        ),
        CheckConstraint("size_bytes > 0", name="size"),
        ForeignKeyConstraint(
            ["household_id", "transaction_id"],
            ["transactions.household_id", "transactions.id"],
            name="fk_receipts_transaction",
            ondelete="RESTRICT",
        ),
        # The same photo sent twice to the same entry (a retry) is one receipt.
        Index(
            "uq_receipts_transaction_upload",
            "transaction_id",
            "upload_sha256",
            unique=True,
            postgresql_where=text("deleted_at IS NULL"),
        ),
        Index(
            "ix_receipts_ocr_pending", "ocr_status", postgresql_where=text("ocr_status = 'pending'")
        ),
    )

    id: Mapped[uuid.UUID] = uuid_pk()
    household_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    transaction_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    uploaded_by_user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    content_type: Mapped[str] = mapped_column(String(32), nullable=False)
    size_bytes: Mapped[int] = mapped_column(Integer, nullable=False)
    # Of the bytes as uploaded (for retries) and as stored (for integrity checks).
    upload_sha256: Mapped[str] = mapped_column(String(64), nullable=False)
    stored_sha256: Mapped[str] = mapped_column(String(64), nullable=False)
    storage_key: Mapped[str] = mapped_column(String(200), nullable=False, unique=True)
    ocr_status: Mapped[str] = mapped_column(String(16), nullable=False, server_default="pending")
    # Private text: never logged and never sent to the AI model.
    ocr_text: Mapped[str | None] = mapped_column(Text)
    ocr_amount_poisha: Mapped[int | None] = mapped_column(BigInteger)
    ocr_date: Mapped[date | None] = mapped_column(Date)
    ocr_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    deleted_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    deleted_by_user_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT")
    )
    created_at: Mapped[datetime] = created_at_column()
