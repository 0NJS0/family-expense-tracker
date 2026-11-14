from datetime import time

from app.schemas.common import ApiModel


class NotificationPreferencesOut(ApiModel):
    daily_reminder: bool
    missing_entry_reminder: bool
    # Local time in the household timezone, e.g. "20:00".
    reminder_time: time
    recurring_due: bool
    budget_alerts: bool


class NotificationPreferencesUpdate(ApiModel):
    daily_reminder: bool | None = None
    missing_entry_reminder: bool | None = None
    reminder_time: time | None = None
    recurring_due: bool | None = None
    budget_alerts: bool | None = None
