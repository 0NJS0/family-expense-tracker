"""AI-written monthly summaries, generated in the background and kept.

The figures (``facts``) are computed by the backend; the model only puts them
into words. A summary whose numbers do not all come from the facts is kept as
``rejected`` (with the reason) and never shown as a summary.
"""

import uuid
from datetime import date, datetime
from typing import Any

from sqlalchemy import (
    CheckConstraint,
    Date,
    DateTime,
    ForeignKey,
    Integer,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, created_at_column, uuid_pk


class AiReport(Base):
    __tablename__ = "ai_reports"
    __table_args__ = (
        UniqueConstraint("household_id", "period_start", "language", name="uq_ai_reports_period"),
        CheckConstraint(
            "status IN ('queued', 'running', 'done', 'rejected', 'failed')", name="status"
        ),
        CheckConstraint("language IN ('bn', 'en')", name="language"),
        CheckConstraint("EXTRACT(DAY FROM period_start) = 1", name="first_of_month"),
    )

    id: Mapped[uuid.UUID] = uuid_pk()
    household_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("households.id", ondelete="RESTRICT"), nullable=False
    )
    period_start: Mapped[date] = mapped_column(Date, nullable=False)
    language: Mapped[str] = mapped_column(String(2), nullable=False)
    status: Mapped[str] = mapped_column(String(16), nullable=False, server_default="queued")
    # NULL when queued automatically at the start of a month.
    requested_by_user_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT")
    )
    facts: Mapped[dict[str, Any] | None] = mapped_column(JSONB)
    text: Mapped[str | None] = mapped_column(Text)
    # Why it was rejected or failed (never contains secrets).
    problem: Mapped[str | None] = mapped_column(Text)
    model: Mapped[str | None] = mapped_column(String(200))
    attempts: Mapped[int] = mapped_column(Integer, nullable=False, server_default="0")
    not_before: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    started_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    finished_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = created_at_column()
