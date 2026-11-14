import uuid
from datetime import date

from fastapi import APIRouter, status

from app.api.deps import Ctx, DbSession
from app.models import CategoryKind
from app.schemas.finance import (
    AccountBalanceOut,
    AccountBalanceRow,
    AccountBalancesOut,
    AccountCreate,
    AccountOut,
    AccountUpdate,
    CategoryCreate,
    CategoryOut,
    CategoryUpdate,
    OpeningBalanceCorrection,
    OpeningBalanceIn,
    OpeningBalanceOut,
)
from app.services import accounts, categories

router = APIRouter(tags=["accounts & categories"])


def _account_out(item: accounts.AccountWithOpening) -> AccountOut:
    a = item.account
    return AccountOut(
        id=a.id,
        name=a.name,
        kind=a.kind,
        institution=a.institution,
        owner_user_id=a.owner_user_id,
        is_shared=a.owner_user_id is None,
        is_private=a.is_private,
        is_archived=a.is_archived,
        opening_balance=OpeningBalanceOut.model_validate(item.opening) if item.opening else None,
        created_at=a.created_at,
    )


@router.get("/accounts", response_model=list[AccountOut])
def list_accounts(ctx: Ctx, db: DbSession, include_archived: bool = False) -> list[AccountOut]:
    return [
        _account_out(item)
        for item in accounts.list_accounts(db, ctx, include_archived=include_archived)
    ]


@router.post("/accounts", response_model=AccountOut, status_code=status.HTTP_201_CREATED)
def create_account(body: AccountCreate, ctx: Ctx, db: DbSession) -> AccountOut:
    return _account_out(accounts.create_account(db, ctx, body))


# Declared before /accounts/{account_id} so "balances" is not parsed as an id.
@router.get("/accounts/balances", response_model=AccountBalancesOut)
def account_balances(
    ctx: Ctx, db: DbSession, as_of: date | None = None, include_archived: bool = False
) -> AccountBalancesOut:
    rows = [
        AccountBalanceRow(
            **_balance_out(balance).model_dump(),
            name=account.name,
            kind=account.kind,
            is_shared=account.owner_user_id is None,
            is_private=account.is_private,
            is_archived=account.is_archived,
        )
        for account, balance in accounts.list_balances(
            db, ctx, as_of_date=as_of, include_archived=include_archived
        )
    ]
    return AccountBalancesOut(
        as_of_date=as_of,
        total_poisha=sum(row.balance_poisha for row in rows),
        accounts=rows,
    )


@router.get("/accounts/{account_id}", response_model=AccountOut)
def get_account(account_id: uuid.UUID, ctx: Ctx, db: DbSession) -> AccountOut:
    return _account_out(accounts.get_account(db, ctx, account_id))


@router.patch("/accounts/{account_id}", response_model=AccountOut)
def update_account(
    account_id: uuid.UUID, body: AccountUpdate, ctx: Ctx, db: DbSession
) -> AccountOut:
    return _account_out(accounts.update_account(db, ctx, account_id, body))


@router.post(
    "/accounts/{account_id}/opening-balance",
    response_model=AccountOut,
    status_code=status.HTTP_201_CREATED,
)
def set_opening_balance(
    account_id: uuid.UUID, body: OpeningBalanceIn, ctx: Ctx, db: DbSession
) -> AccountOut:
    return _account_out(accounts.set_opening_balance(db, ctx, account_id, body))


@router.post("/accounts/{account_id}/opening-balance/correct", response_model=AccountOut)
def correct_opening_balance(
    account_id: uuid.UUID, body: OpeningBalanceCorrection, ctx: Ctx, db: DbSession
) -> AccountOut:
    """Audited correction of a wrong opening balance (admin)."""
    return _account_out(accounts.correct_opening_balance(db, ctx, account_id, body))


@router.get("/accounts/{account_id}/balance", response_model=AccountBalanceOut)
def account_balance(
    account_id: uuid.UUID, ctx: Ctx, db: DbSession, as_of: date | None = None
) -> AccountBalanceOut:
    return _balance_out(accounts.get_balance(db, ctx, account_id, as_of))


def _balance_out(balance: accounts.Balance) -> AccountBalanceOut:
    return AccountBalanceOut(
        account_id=balance.account_id,
        as_of_date=balance.as_of_date,
        has_opening_balance=balance.has_opening_balance,
        opening_balance_poisha=balance.opening_balance_poisha,
        inflow_poisha=balance.inflow_poisha,
        outflow_poisha=balance.outflow_poisha,
        balance_poisha=balance.balance_poisha,
    )


@router.get("/categories", response_model=list[CategoryOut])
def list_categories(
    ctx: Ctx, db: DbSession, kind: CategoryKind | None = None, include_archived: bool = False
) -> list[CategoryOut]:
    return [
        CategoryOut.model_validate(c)
        for c in categories.list_categories(db, ctx, kind=kind, include_archived=include_archived)
    ]


@router.post("/categories", response_model=CategoryOut, status_code=status.HTTP_201_CREATED)
def create_category(body: CategoryCreate, ctx: Ctx, db: DbSession) -> CategoryOut:
    return CategoryOut.model_validate(categories.create_category(db, ctx, body))


@router.patch("/categories/{category_id}", response_model=CategoryOut)
def update_category(
    category_id: uuid.UUID, body: CategoryUpdate, ctx: Ctx, db: DbSession
) -> CategoryOut:
    return CategoryOut.model_validate(categories.update_category(db, ctx, category_id, body))
