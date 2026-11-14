import uuid
from datetime import date
from typing import Annotated

from fastapi import APIRouter, Depends, Query, Response, status

from app.api.deps import Ctx, DbSession
from app.models import TransactionType
from app.schemas.common import Page
from app.schemas.finance import (
    TransactionCreate,
    TransactionOut,
    TransactionUpdate,
    TransactionVoid,
)
from app.services import exports, transactions
from app.timeutil import local_today

router = APIRouter(tags=["transactions"])


@router.post(
    "/transactions",
    response_model=TransactionOut,
    status_code=status.HTTP_201_CREATED,
    responses={200: {"description": "Idempotent replay of an already-created transaction"}},
)
def create_transaction(
    body: TransactionCreate, ctx: Ctx, db: DbSession, response: Response
) -> TransactionOut:
    transaction, created = transactions.create_transaction(db, ctx, body)
    if not created:
        response.status_code = status.HTTP_200_OK
    return TransactionOut.model_validate(transaction)


def _filters(
    date_from: date | None = None,
    date_to: date | None = None,
    type: TransactionType | None = None,
    account_id: uuid.UUID | None = None,
    category_id: uuid.UUID | None = None,
    member_id: uuid.UUID | None = None,
    q: Annotated[str | None, Query(max_length=100)] = None,
    include_voided: bool = False,
) -> transactions.TransactionFilters:
    return transactions.TransactionFilters(
        date_from=date_from,
        date_to=date_to,
        type=type,
        account_id=account_id,
        category_id=category_id,
        member_id=member_id,
        search=q.strip() if q else None,
        include_voided=include_voided,
    )


Filters = Annotated[transactions.TransactionFilters, Depends(_filters)]


@router.get("/transactions", response_model=Page[TransactionOut])
def list_transactions(
    ctx: Ctx,
    db: DbSession,
    filters: Filters,
    limit: Annotated[int, Query(ge=1, le=200)] = 50,
    offset: Annotated[int, Query(ge=0, le=1_000_000)] = 0,
    sort: transactions.SortOrder = transactions.SortOrder.NEWEST,
) -> Page[TransactionOut]:
    items, total = transactions.list_transactions(
        db, ctx, filters, limit=limit, offset=offset, sort=sort
    )
    return Page[TransactionOut](
        items=[TransactionOut.model_validate(t) for t in items],
        total=total,
        limit=limit,
        offset=offset,
    )


@router.get(
    "/transactions/export.xlsx",
    response_class=Response,
    responses={
        200: {
            "content": {"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": {}},
            "description": "Excel workbook, same columns as the CSV",
        }
    },
)
def export_transactions_xlsx(ctx: Ctx, db: DbSession, filters: Filters) -> Response:
    body = exports.export_transactions_xlsx(db, ctx, filters)
    filename = f"transactions-{local_today(ctx.household.timezone).isoformat()}.xlsx"
    return Response(
        content=body,
        media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        headers={
            "Content-Disposition": f'attachment; filename="{filename}"',
            "Cache-Control": "no-store",
        },
    )


# Declared before /transactions/{transaction_id} so "export.csv" is not parsed as an id.
@router.get(
    "/transactions/export.csv",
    response_class=Response,
    responses={200: {"content": {"text/csv": {}}, "description": "UTF-8 CSV with BOM"}},
)
def export_transactions(ctx: Ctx, db: DbSession, filters: Filters) -> Response:
    body = exports.export_transactions_csv(db, ctx, filters)
    filename = f"transactions-{local_today(ctx.household.timezone).isoformat()}.csv"
    return Response(
        content=body.encode("utf-8"),
        media_type="text/csv; charset=utf-8",
        headers={
            "Content-Disposition": f'attachment; filename="{filename}"',
            "Cache-Control": "no-store",
        },
    )


@router.get("/transactions/{transaction_id}", response_model=TransactionOut)
def get_transaction(transaction_id: uuid.UUID, ctx: Ctx, db: DbSession) -> TransactionOut:
    return TransactionOut.model_validate(transactions.get_transaction(db, ctx, transaction_id))


@router.patch("/transactions/{transaction_id}", response_model=TransactionOut)
def update_transaction(
    transaction_id: uuid.UUID, body: TransactionUpdate, ctx: Ctx, db: DbSession
) -> TransactionOut:
    return TransactionOut.model_validate(
        transactions.update_transaction(db, ctx, transaction_id, body)
    )


@router.post("/transactions/{transaction_id}/void", response_model=TransactionOut)
def void_transaction(
    transaction_id: uuid.UUID, body: TransactionVoid, ctx: Ctx, db: DbSession
) -> TransactionOut:
    return TransactionOut.model_validate(
        transactions.void_transaction(db, ctx, transaction_id, body)
    )
