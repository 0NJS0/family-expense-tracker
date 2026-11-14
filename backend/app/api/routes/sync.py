from typing import Annotated

from fastapi import APIRouter, Query

from app.api.deps import Ctx, DbSession
from app.schemas.finance import SyncChangesOut, TransactionOut
from app.services import sync

router = APIRouter(tags=["sync"])


@router.get("/sync/changes", response_model=SyncChangesOut)
def sync_changes(
    ctx: Ctx,
    db: DbSession,
    cursor: Annotated[str | None, Query(max_length=200)] = None,
    limit: Annotated[int, Query(ge=1, le=500)] = 200,
) -> SyncChangesOut:
    changes = sync.changes_since(db, ctx, cursor, limit)
    return SyncChangesOut(
        items=[TransactionOut.model_validate(t) for t in changes.items],
        removed=changes.removed,
        next_cursor=changes.next_cursor,
        has_more=changes.has_more,
    )
