import uuid

from fastapi import APIRouter, Request, Response, status
from starlette.concurrency import run_in_threadpool

from app.api.deps import AppSettings, Ctx, DbSession
from app.errors import ValidationFailed
from app.schemas.receipts import ReceiptOut
from app.services import receipts

router = APIRouter(tags=["receipts"])
_FILE_CSP = "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; sandbox"


@router.post(
    "/transactions/{transaction_id}/receipts",
    response_model=ReceiptOut,
    status_code=status.HTTP_201_CREATED,
    responses={200: {"description": "The same file was already attached (a retry)"}},
)
async def add_receipt(
    transaction_id: uuid.UUID,
    request: Request,
    response: Response,
    ctx: Ctx,
    db: DbSession,
    settings: AppSettings,
) -> ReceiptOut:
    """The request body is the file itself (any Content-Type); its bytes decide the type."""
    limit = settings.max_receipt_bytes
    chunks: list[bytes] = []
    size = 0
    async for chunk in request.stream():
        size += len(chunk)
        if size > limit:  # refused without reading the rest
            raise ValidationFailed(f"The file is larger than {limit // (1024 * 1024)} MB")
        chunks.append(chunk)
    # The service uses the (blocking) database session: run it off the event loop.
    receipt, created = await run_in_threadpool(
        receipts.upload, db, ctx, settings, transaction_id, b"".join(chunks)
    )
    if not created:
        response.status_code = status.HTTP_200_OK
    return ReceiptOut.model_validate(receipt)


@router.get("/transactions/{transaction_id}/receipts", response_model=list[ReceiptOut])
def list_receipts(transaction_id: uuid.UUID, ctx: Ctx, db: DbSession) -> list[ReceiptOut]:
    return [
        ReceiptOut.model_validate(r) for r in receipts.list_for_transaction(db, ctx, transaction_id)
    ]


@router.get("/receipts/{receipt_id}/file")
def receipt_file(receipt_id: uuid.UUID, ctx: Ctx, db: DbSession, settings: AppSettings) -> Response:
    receipt, data = receipts.open_file(db, ctx, settings, receipt_id)
    extension = "pdf" if receipt.content_type == "application/pdf" else "jpg"
    return Response(
        content=data,
        media_type=receipt.content_type,
        headers={
            "Cache-Control": "private, no-store",
            "X-Content-Type-Options": "nosniff",
            "Content-Disposition": f'inline; filename="receipt-{receipt.id}.{extension}"',
            # A PDF must not run scripts or load anything in the dashboard's origin.
            "Content-Security-Policy": _FILE_CSP,
        },
    )


@router.delete("/receipts/{receipt_id}", status_code=status.HTTP_204_NO_CONTENT)
def remove_receipt(receipt_id: uuid.UUID, ctx: Ctx, db: DbSession, settings: AppSettings) -> None:
    receipts.delete(db, ctx, settings, receipt_id)
