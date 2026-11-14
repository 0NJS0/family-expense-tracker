"""Recurring rules: scheduled reminders that become transactions only when confirmed.

Generation is lazy and idempotent: whenever someone asks what is due, every
active rule's occurrences up to today are created (at most one per rule and
date, enforced by a unique constraint) and the rule's schedule moves on. Rules
are locked while they advance, so two requests cannot generate twice.

A pending occurrence becomes a transaction when a member confirms it ("paid",
optionally with the real amount) or is skipped. With ``auto_post`` the
transaction is created at once. Either way the transaction's client_id is
derived from the occurrence, so a repeated confirmation never creates a second
transaction.
"""

import uuid
from datetime import date, datetime, time
from zoneinfo import ZoneInfo

from sqlalchemy import ColumnElement, and_, or_, select, true
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.orm import Session

from app.errors import AppError, Conflict, NotFound, ValidationFailed
from app.models import (
    Account,
    Category,
    Household,
    RecurringOccurrence,
    RecurringRule,
    Role,
    TransactionType,
    User,
)
from app.recurrence import Frequency, occurrence
from app.schemas.finance import TransactionCreate
from app.schemas.recurring import OccurrenceConfirm, RecurringRuleCreate, RecurringRuleUpdate
from app.services import audit, transactions, visibility
from app.services.context import HouseholdContext
from app.services.households import is_active_member
from app.timeutil import local_today, utcnow

# Catch-up limit per rule and request (e.g. after a long pause of the server).
MAX_GENERATE = 60
_CLIENT_NS = uuid.UUID("6b0f3c1e-2f7c-4c55-9d8e-6f1d0f6b9a10")


def occurrence_client_id(occurrence_id: uuid.UUID) -> uuid.UUID:
    """The transaction client_id for an occurrence: stable, so posting is idempotent."""
    return uuid.uuid5(_CLIENT_NS, str(occurrence_id))


def _rule_visible(ctx: HouseholdContext) -> ColumnElement[bool]:
    """Rules on someone else's private account are hidden, like their transactions."""
    if ctx.is_admin:
        return true()
    hidden = select(Account.id).where(
        Account.household_id == ctx.household_id,
        Account.is_private.is_(True),
        Account.owner_user_id != ctx.user_id,
    )
    return and_(
        RecurringRule.account_id.not_in(hidden),
        or_(
            RecurringRule.destination_account_id.is_(None),
            RecurringRule.destination_account_id.not_in(hidden),
        ),
    )


def list_rules(db: Session, ctx: HouseholdContext) -> list[RecurringRule]:
    query = select(RecurringRule).where(
        RecurringRule.household_id == ctx.household_id, _rule_visible(ctx)
    )
    return list(db.scalars(query.order_by(RecurringRule.name)))


def _validate_refs(
    db: Session,
    ctx: HouseholdContext,
    type_: TransactionType,
    category_id: uuid.UUID | None,
    account_id: uuid.UUID,
    destination_id: uuid.UUID | None,
) -> None:
    if type_ is TransactionType.TRANSFER:
        if destination_id is None or category_id is not None:
            raise ValidationFailed("A transfer needs a destination account and no category")
        if destination_id == account_id:
            raise ValidationFailed("Source and destination accounts must be different")
    else:
        if destination_id is not None or category_id is None:
            raise ValidationFailed(f"An {type_.value} needs a category and no destination")
        category = db.scalar(
            select(Category).where(
                Category.id == category_id, Category.household_id == ctx.household_id
            )
        )
        if category is None or category.kind != type_.value:
            raise ValidationFailed("Category not found for this type")
        if category.is_archived:
            raise ValidationFailed("Category is archived")
    for ident in (account_id, destination_id):
        if ident is None:
            continue
        account = db.scalar(
            select(Account).where(
                Account.id == ident,
                Account.household_id == ctx.household_id,
                visibility.account_visible(ctx),
            )
        )
        if account is None:
            raise ValidationFailed("Account not found")
        if account.is_archived:
            raise ValidationFailed(f"Account '{account.name}' is archived")


def create_rule(db: Session, ctx: HouseholdContext, data: RecurringRuleCreate) -> RecurringRule:
    ctx.require(Role.ADMIN)
    if data.amount_poisha is None:  # the schema validator resolves the amount
        raise ValidationFailed("amount is required")
    _validate_refs(
        db, ctx, data.type, data.category_id, data.account_id, data.destination_account_id
    )
    attributed = data.attributed_user_id or ctx.user_id
    if not is_active_member(db, ctx.household_id, attributed):
        raise ValidationFailed("Attributed person must be an active member of this household")
    rule = RecurringRule(
        household_id=ctx.household_id,
        name=data.name,
        type=data.type.value,
        amount_poisha=data.amount_poisha,
        category_id=data.category_id,
        account_id=data.account_id,
        destination_account_id=data.destination_account_id,
        attributed_user_id=attributed,
        note=data.note or None,
        frequency=data.frequency.value,
        interval=data.interval,
        start_date=data.start_date,
        end_date=data.end_date,
        auto_post=data.auto_post,
        next_index=0,
        next_due_date=data.start_date,
        created_by_user_id=ctx.user_id,
    )
    db.add(rule)
    db.flush()
    audit.record(
        db,
        action="recurring.created",
        entity_type="recurring_rule",
        entity_id=rule.id,
        household_id=ctx.household_id,
        actor_user_id=ctx.user_id,
        details={
            "name": rule.name,
            "type": rule.type,
            "amount_poisha": rule.amount_poisha,
            "frequency": rule.frequency,
            "interval": rule.interval,
            "start_date": rule.start_date.isoformat(),
            "auto_post": rule.auto_post,
        },
    )
    db.commit()
    return rule


def update_rule(
    db: Session, ctx: HouseholdContext, rule_id: uuid.UUID, data: RecurringRuleUpdate
) -> RecurringRule:
    """Change a rule (not its schedule: make a new rule for that). Version-checked."""
    ctx.require(Role.ADMIN)
    rule = db.scalar(
        select(RecurringRule)
        .where(RecurringRule.id == rule_id, RecurringRule.household_id == ctx.household_id)
        .with_for_update()
    )
    if rule is None:
        raise NotFound("Recurring rule not found")
    if rule.version != data.version:
        raise Conflict(
            "The rule was changed by someone else; reload and try again",
            details=[{"current_version": rule.version}],
        )
    changes: dict[str, object] = {}
    for field in (
        "name",
        "amount_poisha",
        "category_id",
        "account_id",
        "destination_account_id",
        "note",
        "end_date",
        "auto_post",
    ):
        if field not in data.model_fields_set and not (
            field == "amount_poisha" and "amount" in data.model_fields_set
        ):
            continue
        new = getattr(data, field)
        if new is None and field in ("name", "amount_poisha", "account_id", "auto_post"):
            raise ValidationFailed(f"{field} cannot be null")
        if new != getattr(rule, field):
            changes[field] = str(new) if isinstance(new, uuid.UUID | date) else new
            setattr(rule, field, new)
    if rule.end_date is not None and rule.end_date < rule.start_date:
        raise ValidationFailed("end_date must not be before start_date")
    _validate_refs(
        db,
        ctx,
        TransactionType(rule.type),
        rule.category_id,
        rule.account_id,
        rule.destination_account_id,
    )
    if data.paused is not None and data.paused != (rule.paused_at is not None):
        if data.paused:
            rule.paused_at = utcnow()
        else:
            # Resuming does not catch up on what was due during the pause.
            rule.paused_at = None
            today = local_today(ctx.household.timezone)
            while rule.next_due_date is not None and rule.next_due_date < today:
                _advance(rule)
        changes["paused"] = data.paused
    if not changes:
        return rule
    rule.version += 1
    audit.record(
        db,
        action="recurring.updated",
        entity_type="recurring_rule",
        entity_id=rule.id,
        household_id=ctx.household_id,
        actor_user_id=ctx.user_id,
        details={
            "version": rule.version,
            "changes": {k: v for k, v in changes.items() if k != "note"},
        },
    )
    db.commit()
    return rule


def _advance(rule: RecurringRule) -> None:
    rule.last_processed_date = rule.next_due_date
    rule.next_index += 1
    nxt = occurrence(rule.start_date, Frequency(rule.frequency), rule.interval, rule.next_index)
    rule.next_due_date = None if rule.end_date is not None and nxt > rule.end_date else nxt


def generate_due(db: Session, household: Household, today: date) -> None:
    """Create every occurrence due up to ``today``; post the auto-post ones. Caller commits."""
    rules = db.scalars(
        select(RecurringRule)
        .where(
            RecurringRule.household_id == household.id,
            RecurringRule.paused_at.is_(None),
            RecurringRule.next_due_date.is_not(None),
            RecurringRule.next_due_date <= today,
        )
        .order_by(RecurringRule.id)
        .with_for_update()
    ).all()
    for rule in rules:
        for _ in range(MAX_GENERATE):
            due = rule.next_due_date
            if due is None or due > today:
                break
            occurrence_id = db.scalar(
                insert(RecurringOccurrence)
                .values(household_id=household.id, rule_id=rule.id, due_date=due)
                .on_conflict_do_nothing(constraint="uq_recurring_occurrences_rule_date")
                .returning(RecurringOccurrence.id)
            )
            _advance(rule)
            if occurrence_id is not None and rule.auto_post:
                _auto_post(db, household, rule, occurrence_id)


def _auto_post(
    db: Session, household: Household, rule: RecurringRule, occurrence_id: uuid.UUID
) -> None:
    """Post as the admin who created the rule. If that fails (e.g. the account was
    archived), the occurrence simply stays pending for a person to resolve."""
    creator = db.get(User, rule.created_by_user_id)
    if creator is None:  # pragma: no cover - foreign key guarantees it
        return
    ctx = HouseholdContext(user=creator, household=household, role=Role.ADMIN)
    occ = db.get(RecurringOccurrence, occurrence_id)
    if occ is None:  # pragma: no cover
        return
    savepoint = db.begin_nested()
    try:
        _post(db, ctx, rule, occ, rule.amount_poisha, occ.due_date)
        savepoint.commit()
    except AppError:
        savepoint.rollback()


def _post(
    db: Session,
    ctx: HouseholdContext,
    rule: RecurringRule,
    occ: RecurringOccurrence,
    amount_poisha: int,
    on: date,
) -> None:
    zone = ZoneInfo(ctx.household.timezone)
    transaction, _ = transactions.create_transaction(
        db,
        ctx,
        TransactionCreate(
            client_id=occurrence_client_id(occ.id),
            type=TransactionType(rule.type),
            amount_poisha=amount_poisha,
            category_id=rule.category_id,
            account_id=rule.account_id,
            destination_account_id=rule.destination_account_id,
            # Midday, so the entry can never slip into a neighbouring day.
            occurred_at=datetime.combine(on, time(12, 0), tzinfo=zone),
            note=rule.note,
            attributed_user_id=rule.attributed_user_id,
        ),
        commit=False,
    )
    occ.status = "posted"
    occ.transaction_id = transaction.id
    occ.resolved_by_user_id = ctx.user_id
    occ.resolved_at = utcnow()
    audit.record(
        db,
        action="recurring.posted",
        entity_type="recurring_occurrence",
        entity_id=occ.id,
        household_id=ctx.household_id,
        actor_user_id=ctx.user_id,
        details={
            "rule_id": str(rule.id),
            "due_date": occ.due_date.isoformat(),
            "transaction_id": str(transaction.id),
        },
    )


def list_occurrences(
    db: Session, ctx: HouseholdContext, *, status: str | None
) -> list[tuple[RecurringOccurrence, RecurringRule]]:
    """Generates what is due, then lists occurrences (pending first, newest first)."""
    generate_due(db, ctx.household, local_today(ctx.household.timezone))
    db.commit()
    query = (
        select(RecurringOccurrence, RecurringRule)
        .join(RecurringRule, RecurringRule.id == RecurringOccurrence.rule_id)
        .where(RecurringOccurrence.household_id == ctx.household_id, _rule_visible(ctx))
    )
    if status is not None:
        query = query.where(RecurringOccurrence.status == status)
    rows = db.execute(
        query.order_by(RecurringOccurrence.due_date.desc(), RecurringRule.name).limit(200)
    ).all()
    return [(occ, rule) for occ, rule in rows]


def _lock_occurrence(
    db: Session, ctx: HouseholdContext, occurrence_id: uuid.UUID
) -> tuple[RecurringOccurrence, RecurringRule]:
    ctx.require(Role.MEMBER)
    row = db.execute(
        select(RecurringOccurrence, RecurringRule)
        .join(RecurringRule, RecurringRule.id == RecurringOccurrence.rule_id)
        .where(
            RecurringOccurrence.id == occurrence_id,
            RecurringOccurrence.household_id == ctx.household_id,
            _rule_visible(ctx),
        )
        .with_for_update(of=RecurringOccurrence)
    ).first()
    if row is None:
        raise NotFound("Occurrence not found")
    return row[0], row[1]


def confirm(
    db: Session, ctx: HouseholdContext, occurrence_id: uuid.UUID, data: OccurrenceConfirm
) -> tuple[RecurringOccurrence, RecurringRule]:
    """Record "paid": create the transaction. Confirming again returns the same result."""
    occ, rule = _lock_occurrence(db, ctx, occurrence_id)
    if occ.status == "posted":
        return occ, rule
    if occ.status == "skipped":
        raise Conflict("This occurrence was skipped")
    _post(
        db,
        ctx,
        rule,
        occ,
        data.amount_poisha or rule.amount_poisha,
        data.occurred_on or occ.due_date,
    )
    db.commit()
    return occ, rule


def skip(
    db: Session, ctx: HouseholdContext, occurrence_id: uuid.UUID
) -> tuple[RecurringOccurrence, RecurringRule]:
    """Not this time: nothing is posted. Skipping again is harmless."""
    occ, rule = _lock_occurrence(db, ctx, occurrence_id)
    if occ.status == "skipped":
        return occ, rule
    if occ.status == "posted":
        raise Conflict("This occurrence was already recorded; void the transaction instead")
    occ.status = "skipped"
    occ.resolved_by_user_id = ctx.user_id
    occ.resolved_at = utcnow()
    audit.record(
        db,
        action="recurring.skipped",
        entity_type="recurring_occurrence",
        entity_id=occ.id,
        household_id=ctx.household_id,
        actor_user_id=ctx.user_id,
        details={"rule_id": str(rule.id), "due_date": occ.due_date.isoformat()},
    )
    db.commit()
    return occ, rule
