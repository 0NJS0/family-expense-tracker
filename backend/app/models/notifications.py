"""Per-person reminder and notification choices, per household.

The phone schedules reminders itself (it works offline); these settings are
stored here so they follow the person to a new phone. No row means the
defaults below.
"""

import uuid
from datetime import datetime, time

from sqlalchemy import Boolean, ForeignKeyConstraint, Time, text
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, updated_at_column

DEFAULT_REMINDER_TIME = time(20, 0)


class NotificationPreferences(Base):
    __tablename__ = "notification_preferences"
    __table_args__ = (
        ForeignKeyConstraint(
            ["household_id", "user_id"],
            ["memberships.household_id", "memberships.user_id"],
            name="fk_notification_preferences_membership",
            ondelete="RESTRICT",
        ),
    )

    household_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    # A reminder every evening. Off by default: no nagging.
    daily_reminder: Mapped[bool] = mapped_column(
        Boolean, nullable=False, server_default=text("false")
    )
    # A reminder only on days when nothing was entered.
    missing_entry_reminder: Mapped[bool] = mapped_column(
        Boolean, nullable=False, server_default=text("true")
    )
    reminder_time: Mapped[time] = mapped_column(
        Time, nullable=False, server_default=text("'20:00'")
    )
    recurring_due: Mapped[bool] = mapped_column(
        Boolean, nullable=False, server_default=text("true")
    )
    # Admins only (alerts are admin information).
    budget_alerts: Mapped[bool] = mapped_column(
        Boolean, nullable=False, server_default=text("true")
    )
    updated_at: Mapped[datetime] = updated_at_column()
