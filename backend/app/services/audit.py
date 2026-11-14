"""Audit trail. Events are added to the caller's database transaction so the
audit record commits or rolls back together with the change it describes."""

import uuid
from typing import Any

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.models import AuditEvent, Role
from app.services.context import HouseholdContext

_FORBIDDEN_KEYS = {"password", "password_hash", "token", "token_hash", "refresh_token"}


def record(
    db: Session,
    *,
    action: str,
    entity_type: str,
    entity_id: uuid.UUID | None,
    household_id: uuid.UUID | None,
    actor_user_id: uuid.UUID | None,
    details: dict[str, Any] | None = None,
) -> None:
    details = details or {}
    leaked = _FORBIDDEN_KEYS.intersection(details)
    if leaked:
        # A programming error: refuse rather than persist secrets.
        raise ValueError(f"audit details must not contain secrets: {sorted(leaked)}")
    db.add(
        AuditEvent(
            household_id=household_id,
            actor_user_id=actor_user_id,
            action=action,
            entity_type=entity_type,
            entity_id=entity_id,
            details=details,
        )
    )


def list_events(
    db: Session,
    ctx: HouseholdContext,
    *,
    entity_id: uuid.UUID | None,
    limit: int,
    offset: int,
) -> tuple[list[AuditEvent], int]:
    ctx.require(Role.ADMIN)
    query = select(AuditEvent).where(AuditEvent.household_id == ctx.household_id)
    if entity_id is not None:
        query = query.where(AuditEvent.entity_id == entity_id)
    total = db.scalar(select(func.count()).select_from(query.subquery())) or 0
    rows = db.scalars(
        query.order_by(AuditEvent.created_at.desc(), AuditEvent.id).limit(limit).offset(offset)
    ).all()
    return list(rows), total
