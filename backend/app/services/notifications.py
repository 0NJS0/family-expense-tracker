from sqlalchemy.orm import Session

from app.errors import ValidationFailed
from app.models import NotificationPreferences, Role
from app.models.notifications import DEFAULT_REMINDER_TIME
from app.schemas.notifications import NotificationPreferencesOut, NotificationPreferencesUpdate
from app.services import audit
from app.services.context import HouseholdContext

_FIELDS = (
    "daily_reminder",
    "missing_entry_reminder",
    "reminder_time",
    "recurring_due",
    "budget_alerts",
)


def _out(prefs: NotificationPreferences | None) -> NotificationPreferencesOut:
    if prefs is None:
        return NotificationPreferencesOut(
            daily_reminder=False,
            missing_entry_reminder=True,
            reminder_time=DEFAULT_REMINDER_TIME,
            recurring_due=True,
            budget_alerts=True,
        )
    return NotificationPreferencesOut.model_validate(prefs)


def get_preferences(db: Session, ctx: HouseholdContext) -> NotificationPreferencesOut:
    return _out(db.get(NotificationPreferences, (ctx.household_id, ctx.user_id)))


def update_preferences(
    db: Session, ctx: HouseholdContext, data: NotificationPreferencesUpdate
) -> NotificationPreferencesOut:
    """Each person sets their own; viewers too (they may want reminders of what is due)."""
    ctx.require(Role.VIEWER)
    prefs = db.get(NotificationPreferences, (ctx.household_id, ctx.user_id), with_for_update=True)
    if prefs is None:
        current = _out(None)
        prefs = NotificationPreferences(
            household_id=ctx.household_id,
            user_id=ctx.user_id,
            **{f: getattr(current, f) for f in _FIELDS},
        )
        db.add(prefs)
    changes = {}
    for field in _FIELDS:
        if field not in data.model_fields_set:
            continue
        value = getattr(data, field)
        if value is None:
            raise ValidationFailed(f"{field} cannot be null")
        if field == "reminder_time":
            value = value.replace(second=0, microsecond=0, tzinfo=None)
        if value != getattr(prefs, field):
            changes[field] = str(value) if field == "reminder_time" else value
            setattr(prefs, field, value)
    if changes:
        audit.record(
            db,
            action="notifications.updated",
            entity_type="user",
            entity_id=ctx.user_id,
            household_id=ctx.household_id,
            actor_user_id=ctx.user_id,
            details={"changes": changes},
        )
    db.commit()
    return _out(prefs)
