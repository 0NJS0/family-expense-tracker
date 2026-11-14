"""Accounts, opening balances and derived balances.

Balances are never stored; they are computed from the opening balance plus
non-voided transactions (docs/accounting-rules.md).
"""

import uuid
from dataclasses import dataclass
from datetime import date, timedelta

from sqlalchemy import case, func, or_, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.errors import Conflict, NotFound, ValidationFailed
from app.models import Account, OpeningBalance, Role, Transaction, TransactionType
from app.schemas.finance import (
    AccountCreate,
    AccountUpdate,
    OpeningBalanceCorrection,
    OpeningBalanceIn,
)
from app.services import audit, visibility
from app.services.context import HouseholdContext
from app.services.households import is_active_member
from app.timeutil import local_day_start


@dataclass(frozen=True)
class AccountWithOpening:
    account: Account
    opening: OpeningBalance | None


@dataclass(frozen=True)
class Balance:
    account_id: uuid.UUID
    as_of_date: date | None
    has_opening_balance: bool
    opening_balance_poisha: int
    inflow_poisha: int
    outflow_poisha: int

    @property
    def balance_poisha(self) -> int:
        return self.opening_balance_poisha + self.inflow_poisha - self.outflow_poisha


def list_accounts(
    db: Session, ctx: HouseholdContext, *, include_archived: bool
) -> list[AccountWithOpening]:
    query = (
        select(Account, OpeningBalance)
        .outerjoin(OpeningBalance, OpeningBalance.account_id == Account.id)
        .where(Account.household_id == ctx.household_id, visibility.account_visible(ctx))
        .order_by(Account.created_at, Account.name)
    )
    if not include_archived:
        query = query.where(Account.is_archived.is_(False))
    return [AccountWithOpening(account, opening) for account, opening in db.execute(query).all()]


def get_account(db: Session, ctx: HouseholdContext, account_id: uuid.UUID) -> AccountWithOpening:
    row = db.execute(
        select(Account, OpeningBalance)
        .outerjoin(OpeningBalance, OpeningBalance.account_id == Account.id)
        .where(
            Account.id == account_id,
            Account.household_id == ctx.household_id,
            visibility.account_visible(ctx),
        )
    ).first()
    if row is None:
        raise NotFound("Account not found")
    return AccountWithOpening(row[0], row[1])


def create_account(db: Session, ctx: HouseholdContext, data: AccountCreate) -> AccountWithOpening:
    ctx.require(Role.ADMIN)
    if data.owner_user_id is not None and not is_active_member(
        db, ctx.household_id, data.owner_user_id
    ):
        raise ValidationFailed("Account owner must be an active member of this household")
    if data.is_private and data.owner_user_id is None:
        raise ValidationFailed("A private account needs an owner")
    account = Account(
        household_id=ctx.household_id,
        name=data.name,
        kind=data.kind.value,
        institution=data.institution,
        owner_user_id=data.owner_user_id,
        is_private=data.is_private,
    )
    db.add(account)
    try:
        db.flush()
    except IntegrityError as exc:
        db.rollback()
        raise Conflict("An account with this name already exists") from exc
    audit.record(
        db,
        action="account.created",
        entity_type="account",
        entity_id=account.id,
        household_id=ctx.household_id,
        actor_user_id=ctx.user_id,
        details={
            "name": account.name,
            "kind": account.kind,
            "owner_user_id": str(account.owner_user_id) if account.owner_user_id else None,
            "is_private": account.is_private,
        },
    )
    opening = None
    if data.opening_balance is not None:
        opening = _insert_opening_balance(db, ctx, account, data.opening_balance)
    db.commit()
    return AccountWithOpening(account, opening)


def update_account(
    db: Session, ctx: HouseholdContext, account_id: uuid.UUID, data: AccountUpdate
) -> AccountWithOpening:
    ctx.require(Role.ADMIN)
    current = get_account(db, ctx, account_id)
    account = current.account
    changes: dict[str, dict[str, object]] = {}
    for field in ("name", "institution", "is_archived"):
        if field in data.model_fields_set:
            new = getattr(data, field)
            if field in ("name", "is_archived") and new is None:
                raise ValidationFailed(f"{field} cannot be null")
            old = getattr(account, field)
            if new != old:
                changes[field] = {"from": old, "to": new}
                setattr(account, field, new)
    if "owner_user_id" in data.model_fields_set and data.owner_user_id != account.owner_user_id:
        if data.owner_user_id is not None and not is_active_member(
            db, ctx.household_id, data.owner_user_id
        ):
            raise ValidationFailed("Account owner must be an active member of this household")
        changes["owner_user_id"] = {
            "from": _str_or_none(account.owner_user_id),
            "to": _str_or_none(data.owner_user_id),
        }
        account.owner_user_id = data.owner_user_id
    private = data.is_private if data.is_private is not None else account.is_private
    if account.owner_user_id is None:
        if data.is_private:
            raise ValidationFailed("A private account needs an owner")
        private = False  # a shared account is never private
    if private != account.is_private:
        changes["is_private"] = {"from": account.is_private, "to": private}
        account.is_private = private
    if not changes:
        return current
    try:
        db.flush()
    except IntegrityError as exc:
        db.rollback()
        raise Conflict("An account with this name already exists") from exc
    audit.record(
        db,
        action="account.updated",
        entity_type="account",
        entity_id=account.id,
        household_id=ctx.household_id,
        actor_user_id=ctx.user_id,
        details={"changes": changes},
    )
    db.commit()
    return current


def _str_or_none(value: uuid.UUID | None) -> str | None:
    return str(value) if value is not None else None


def set_opening_balance(
    db: Session, ctx: HouseholdContext, account_id: uuid.UUID, data: OpeningBalanceIn
) -> AccountWithOpening:
    ctx.require(Role.ADMIN)
    account = db.scalar(
        select(Account)
        .where(Account.id == account_id, Account.household_id == ctx.household_id)
        .with_for_update()
    )
    if account is None:
        raise NotFound("Account not found")
    opening = _insert_opening_balance(db, ctx, account, data)
    db.commit()
    return AccountWithOpening(account, opening)


def _insert_opening_balance(
    db: Session, ctx: HouseholdContext, account: Account, data: OpeningBalanceIn
) -> OpeningBalance:
    """Caller must hold a lock on ``account`` (or have just created it)."""
    if db.get(OpeningBalance, account.id) is not None:
        raise Conflict("This account already has an opening balance; it can only be set once")
    _check_nothing_before(db, ctx, account, data.as_of_date)
    opening = OpeningBalance(
        account_id=account.id,
        household_id=ctx.household_id,
        amount_poisha=data.amount_poisha,
        as_of_date=data.as_of_date,
        created_by_user_id=ctx.user_id,
    )
    db.add(opening)
    db.flush()
    audit.record(
        db,
        action="account.opening_balance_set",
        entity_type="account",
        entity_id=account.id,
        household_id=ctx.household_id,
        actor_user_id=ctx.user_id,
        details={"amount_poisha": data.amount_poisha, "as_of_date": data.as_of_date.isoformat()},
    )
    return opening


def _check_nothing_before(
    db: Session, ctx: HouseholdContext, account: Account, as_of_date: date
) -> None:
    # The opening balance is the balance at the start of as_of_date, so it
    # already includes everything before that day. Allowing earlier
    # transactions would count them twice.
    start = local_day_start(as_of_date, ctx.household.timezone)
    earlier = db.scalar(
        select(func.count())
        .select_from(Transaction)
        .where(
            Transaction.household_id == ctx.household_id,
            Transaction.voided_at.is_(None),
            or_(
                Transaction.account_id == account.id,
                Transaction.destination_account_id == account.id,
            ),
            Transaction.occurred_at < start,
        )
    )
    if earlier:
        raise Conflict(
            "This account has transactions dated before the opening balance date; "
            "choose a date on or before the earliest transaction"
        )


def correct_opening_balance(
    db: Session, ctx: HouseholdContext, account_id: uuid.UUID, data: OpeningBalanceCorrection
) -> AccountWithOpening:
    """Fix a wrong opening balance. The caller states the values it saw, so two
    admins cannot overwrite each other; the audit trail keeps before, after and why."""
    ctx.require(Role.ADMIN)
    account = db.scalar(
        select(Account)
        .where(Account.id == account_id, Account.household_id == ctx.household_id)
        .with_for_update()
    )
    if account is None:
        raise NotFound("Account not found")
    opening = db.get(OpeningBalance, account.id, with_for_update=True)
    if opening is None:
        raise NotFound("This account has no opening balance to correct; set one instead")
    if (opening.amount_poisha, opening.as_of_date) != (
        data.previous_amount_poisha,
        data.previous_as_of_date,
    ):
        raise Conflict(
            "The opening balance was changed by someone else; reload and try again",
            details=[
                {
                    "current_amount_poisha": opening.amount_poisha,
                    "current_as_of_date": opening.as_of_date.isoformat(),
                }
            ],
        )
    if (opening.amount_poisha, opening.as_of_date) == (data.amount_poisha, data.as_of_date):
        raise ValidationFailed("Nothing to correct: the new values are the same")
    if data.as_of_date > opening.as_of_date:
        _check_nothing_before(db, ctx, account, data.as_of_date)
    audit.record(
        db,
        action="account.opening_balance_corrected",
        entity_type="account",
        entity_id=account.id,
        household_id=ctx.household_id,
        actor_user_id=ctx.user_id,
        details={
            "from": {
                "amount_poisha": opening.amount_poisha,
                "as_of_date": opening.as_of_date.isoformat(),
            },
            "to": {"amount_poisha": data.amount_poisha, "as_of_date": data.as_of_date.isoformat()},
            "reason": data.reason,
        },
    )
    opening.amount_poisha = data.amount_poisha
    opening.as_of_date = data.as_of_date
    db.commit()
    return AccountWithOpening(account, opening)


def get_balance(
    db: Session, ctx: HouseholdContext, account_id: uuid.UUID, as_of_date: date | None
) -> Balance:
    """Balance at the end of local day ``as_of_date`` (or now if omitted)."""
    return _balances(db, ctx, [get_account(db, ctx, account_id)], as_of_date)[0]


def list_balances(
    db: Session, ctx: HouseholdContext, *, as_of_date: date | None, include_archived: bool
) -> list[tuple[Account, Balance]]:
    """Every account's balance from two aggregate queries, not one per account."""
    items = list_accounts(db, ctx, include_archived=include_archived)
    return list(
        zip(
            (item.account for item in items),
            _balances(db, ctx, items, as_of_date),
            strict=True,
        )
    )


def _balances(
    db: Session,
    ctx: HouseholdContext,
    items: list[AccountWithOpening],
    as_of_date: date | None,
) -> list[Balance]:
    if not items:
        return []
    ids = [item.account.id for item in items]
    amount = Transaction.amount_poisha
    conditions = [Transaction.household_id == ctx.household_id, Transaction.voided_at.is_(None)]
    if as_of_date is not None:
        end = local_day_start(as_of_date + timedelta(days=1), ctx.household.timezone)
        conditions.append(Transaction.occurred_at < end)

    # Source side: income flows in; expenses and outgoing transfers flow out.
    source_rows = db.execute(
        select(
            Transaction.account_id,
            func.sum(case((Transaction.type == TransactionType.INCOME.value, amount), else_=0)),
            func.sum(case((Transaction.type != TransactionType.INCOME.value, amount), else_=0)),
        )
        .where(*conditions, Transaction.account_id.in_(ids))
        .group_by(Transaction.account_id)
    ).all()
    # Destination side: only transfers have one, and they flow in.
    destination_rows = db.execute(
        select(Transaction.destination_account_id, func.sum(amount))
        .where(*conditions, Transaction.destination_account_id.in_(ids))
        .group_by(Transaction.destination_account_id)
    ).all()

    # SUM(bigint) is NUMERIC in PostgreSQL; int() of an integral Decimal is exact.
    inflow: dict[uuid.UUID, int] = {account_id: int(i) for account_id, i, _ in source_rows}
    outflow: dict[uuid.UUID, int] = {account_id: int(o) for account_id, _, o in source_rows}
    for account_id, total in destination_rows:
        if account_id is not None:  # always true: filtered by IN (ids)
            inflow[account_id] = inflow.get(account_id, 0) + int(total)

    balances = []
    for item in items:
        opening = item.opening
        applies = opening is not None and (as_of_date is None or opening.as_of_date <= as_of_date)
        balances.append(
            Balance(
                account_id=item.account.id,
                as_of_date=as_of_date,
                has_opening_balance=opening is not None,
                opening_balance_poisha=opening.amount_poisha if opening and applies else 0,
                inflow_poisha=inflow.get(item.account.id, 0),
                outflow_poisha=outflow.get(item.account.id, 0),
            )
        )
    return balances
