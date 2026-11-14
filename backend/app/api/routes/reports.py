import uuid
from datetime import date
from typing import Annotated

from fastapi import APIRouter, Query

from app.api.deps import Ctx, DbSession
from app.schemas.common import Page
from app.schemas.reports import (
    AuditEventOut,
    CompareTo,
    Granularity,
    SummaryOut,
    TimeseriesOut,
)
from app.services import audit, reports

router = APIRouter(tags=["reports"])


@router.get("/reports/summary", response_model=SummaryOut)
def summary(
    ctx: Ctx,
    db: DbSession,
    date_from: date | None = None,
    date_to: date | None = None,
    member_id: uuid.UUID | None = None,
    compare_to: CompareTo = CompareTo.PREVIOUS_PERIOD,
) -> SummaryOut:
    rng = reports.resolve_range(ctx, date_from, date_to, member_id, db)
    return reports.summary(db, ctx, rng, compare_to)


@router.get("/reports/timeseries", response_model=TimeseriesOut)
def timeseries(
    ctx: Ctx,
    db: DbSession,
    granularity: Granularity = Granularity.DAY,
    date_from: date | None = None,
    date_to: date | None = None,
    member_id: uuid.UUID | None = None,
) -> TimeseriesOut:
    rng = reports.resolve_range(ctx, date_from, date_to, member_id, db)
    return reports.timeseries(db, ctx, rng, granularity)


@router.get("/audit-events", response_model=Page[AuditEventOut])
def audit_events(
    ctx: Ctx,
    db: DbSession,
    entity_id: uuid.UUID | None = None,
    limit: Annotated[int, Query(ge=1, le=200)] = 50,
    offset: Annotated[int, Query(ge=0)] = 0,
) -> Page[AuditEventOut]:
    items, total = audit.list_events(db, ctx, entity_id=entity_id, limit=limit, offset=offset)
    return Page[AuditEventOut](
        items=[AuditEventOut.model_validate(e) for e in items],
        total=total,
        limit=limit,
        offset=offset,
    )
