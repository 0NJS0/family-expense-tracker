import uuid
from typing import Annotated, Literal

from fastapi import APIRouter, Query, status

from app.api.deps import Ctx, DbSession
from app.models import RecurringOccurrence, RecurringRule
from app.recurrence import Frequency
from app.schemas.recurring import (
    OccurrenceConfirm,
    OccurrenceOut,
    OccurrenceStatus,
    RecurringRuleCreate,
    RecurringRuleOut,
    RecurringRuleUpdate,
)
from app.services import recurring

router = APIRouter(tags=["recurring"])


def _rule_out(rule: RecurringRule) -> RecurringRuleOut:
    return RecurringRuleOut(
        id=rule.id,
        name=rule.name,
        type=rule.type,
        amount_poisha=rule.amount_poisha,
        category_id=rule.category_id,
        account_id=rule.account_id,
        destination_account_id=rule.destination_account_id,
        attributed_user_id=rule.attributed_user_id,
        note=rule.note,
        frequency=Frequency(rule.frequency),
        interval=rule.interval,
        start_date=rule.start_date,
        end_date=rule.end_date,
        auto_post=rule.auto_post,
        paused=rule.paused_at is not None,
        next_due_date=rule.next_due_date,
        last_processed_date=rule.last_processed_date,
        version=rule.version,
    )


def _occurrence_out(occ: RecurringOccurrence, rule: RecurringRule) -> OccurrenceOut:
    return OccurrenceOut(
        id=occ.id,
        rule_id=rule.id,
        rule_name=rule.name,
        type=rule.type,
        amount_poisha=rule.amount_poisha,
        category_id=rule.category_id,
        account_id=rule.account_id,
        destination_account_id=rule.destination_account_id,
        due_date=occ.due_date,
        status=OccurrenceStatus(occ.status),
        transaction_id=occ.transaction_id,
        resolved_at=occ.resolved_at,
    )


@router.get("/recurring-rules", response_model=list[RecurringRuleOut])
def list_rules(ctx: Ctx, db: DbSession) -> list[RecurringRuleOut]:
    return [_rule_out(r) for r in recurring.list_rules(db, ctx)]


@router.post(
    "/recurring-rules", response_model=RecurringRuleOut, status_code=status.HTTP_201_CREATED
)
def create_rule(body: RecurringRuleCreate, ctx: Ctx, db: DbSession) -> RecurringRuleOut:
    return _rule_out(recurring.create_rule(db, ctx, body))


@router.patch("/recurring-rules/{rule_id}", response_model=RecurringRuleOut)
def update_rule(
    rule_id: uuid.UUID, body: RecurringRuleUpdate, ctx: Ctx, db: DbSession
) -> RecurringRuleOut:
    return _rule_out(recurring.update_rule(db, ctx, rule_id, body))


@router.get("/recurring/occurrences", response_model=list[OccurrenceOut])
def list_occurrences(
    ctx: Ctx,
    db: DbSession,
    status: Annotated[Literal["pending", "posted", "skipped"] | None, Query()] = "pending",
) -> list[OccurrenceOut]:
    """What is due (generated up to today on every call), pending by default."""
    return [_occurrence_out(o, r) for o, r in recurring.list_occurrences(db, ctx, status=status)]


@router.post("/recurring/occurrences/{occurrence_id}/confirm", response_model=OccurrenceOut)
def confirm(
    occurrence_id: uuid.UUID, body: OccurrenceConfirm, ctx: Ctx, db: DbSession
) -> OccurrenceOut:
    return _occurrence_out(*recurring.confirm(db, ctx, occurrence_id, body))


@router.post("/recurring/occurrences/{occurrence_id}/skip", response_model=OccurrenceOut)
def skip(occurrence_id: uuid.UUID, ctx: Ctx, db: DbSession) -> OccurrenceOut:
    return _occurrence_out(*recurring.skip(db, ctx, occurrence_id))
