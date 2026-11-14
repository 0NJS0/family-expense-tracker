"""Receipt upload, listing, download and removal.

Rules:
- The file type is decided from the bytes, never from a name or header. JPEG,
  PNG and WebP photos are accepted and re-encoded as JPEG (this drops EXIF and
  other metadata such as location, and fixes the orientation); PDFs are stored
  as sent. Anything else is refused.
- A receipt can be seen by whoever can see its transaction (private accounts
  included) and attached by whoever may correct it (its creator or an admin).
- Sending the same file to the same transaction again returns the existing
  receipt, so a phone can retry an upload safely.
- Files are written atomically; the database row is the source of truth.
"""

import hashlib
import io
import os
import uuid
from pathlib import Path

from PIL import Image, ImageOps, UnidentifiedImageError
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.config import Settings
from app.errors import Conflict, Forbidden, NotFound, ValidationFailed
from app.models import Receipt, Role, Transaction
from app.services import audit, transactions
from app.services.context import HouseholdContext
from app.timeutil import utcnow

MAX_SIDE = 3000
# Refuse "decompression bombs" (a small file that expands to a huge image).
Image.MAX_IMAGE_PIXELS = 60_000_000


def _kind(data: bytes) -> str | None:
    if data.startswith(b"\xff\xd8\xff"):
        return "jpeg"
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "png"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "webp"
    if data.startswith(b"%PDF-"):
        return "pdf"
    return None


def _normalise_image(data: bytes) -> bytes:
    """Decode and re-encode: drops all metadata, applies the EXIF rotation, caps the size."""
    try:
        with Image.open(io.BytesIO(data)) as image:
            image.load()
            upright = ImageOps.exif_transpose(image)
            rgb = upright.convert("RGB")
            rgb.thumbnail((MAX_SIDE, MAX_SIDE))
            out = io.BytesIO()
            rgb.save(out, format="JPEG", quality=85, optimize=True)  # no exif= → none written
            return out.getvalue()
    except (UnidentifiedImageError, OSError, Image.DecompressionBombError, ValueError) as exc:
        raise ValidationFailed("The image could not be read") from exc


def _path(settings: Settings, storage_key: str) -> Path:
    return Path(settings.receipts_dir) / storage_key


def _write_atomically(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    tmp = path.with_suffix(path.suffix + ".tmp")
    with open(tmp, "wb") as f:
        f.write(data)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


def _visible_transaction(
    db: Session, ctx: HouseholdContext, transaction_id: uuid.UUID
) -> Transaction:
    return transactions.get_transaction(db, ctx, transaction_id)  # 404 if hidden or foreign


def upload(
    db: Session, ctx: HouseholdContext, settings: Settings, transaction_id: uuid.UUID, data: bytes
) -> tuple[Receipt, bool]:
    """Returns (receipt, created); ``created`` is False for a repeated upload."""
    ctx.require(Role.MEMBER)
    transaction = _visible_transaction(db, ctx, transaction_id)
    if not ctx.is_admin and transaction.created_by_user_id != ctx.user_id:
        raise Forbidden("Members can only add receipts to entries they made")
    if transaction.voided_at is not None:
        raise Conflict("This entry is voided")
    if not data:
        raise ValidationFailed("The file is empty")
    if len(data) > settings.max_receipt_bytes:
        raise ValidationFailed(
            f"The file is larger than {settings.max_receipt_bytes // (1024 * 1024)} MB"
        )
    kind = _kind(data)
    if kind is None:
        raise ValidationFailed("Only photos (JPEG, PNG, WebP) and PDF files are accepted")

    upload_sha = hashlib.sha256(data).hexdigest()
    existing = db.scalar(
        select(Receipt).where(
            Receipt.transaction_id == transaction.id,
            Receipt.upload_sha256 == upload_sha,
            Receipt.deleted_at.is_(None),
        )
    )
    if existing is not None:
        return existing, False

    stored = data if kind == "pdf" else _normalise_image(data)
    content_type = "application/pdf" if kind == "pdf" else "image/jpeg"
    storage_key = f"{ctx.household_id}/{uuid.uuid4()}.{'pdf' if kind == 'pdf' else 'jpg'}"
    path = _path(settings, storage_key)
    _write_atomically(path, stored)
    receipt = Receipt(
        household_id=ctx.household_id,
        transaction_id=transaction.id,
        uploaded_by_user_id=ctx.user_id,
        content_type=content_type,
        size_bytes=len(stored),
        upload_sha256=upload_sha,
        stored_sha256=hashlib.sha256(stored).hexdigest(),
        storage_key=storage_key,
        ocr_status="pending",
    )
    db.add(receipt)
    try:
        db.flush()
    except IntegrityError:
        # A concurrent identical upload won: keep its file, drop ours.
        db.rollback()
        path.unlink(missing_ok=True)
        winner = db.scalar(
            select(Receipt).where(
                Receipt.transaction_id == transaction_id,
                Receipt.upload_sha256 == upload_sha,
                Receipt.deleted_at.is_(None),
            )
        )
        if winner is None:  # pragma: no cover
            raise
        return winner, False
    audit.record(
        db,
        action="receipt.added",
        entity_type="receipt",
        entity_id=receipt.id,
        household_id=ctx.household_id,
        actor_user_id=ctx.user_id,
        details={
            "transaction_id": str(transaction.id),
            "content_type": content_type,
            "size_bytes": len(stored),
        },
    )
    try:
        db.commit()
    except Exception:
        path.unlink(missing_ok=True)
        raise
    return receipt, True


def list_for_transaction(
    db: Session, ctx: HouseholdContext, transaction_id: uuid.UUID
) -> list[Receipt]:
    transaction = _visible_transaction(db, ctx, transaction_id)
    return list(
        db.scalars(
            select(Receipt)
            .where(Receipt.transaction_id == transaction.id, Receipt.deleted_at.is_(None))
            .order_by(Receipt.created_at)
        )
    )


def _get(db: Session, ctx: HouseholdContext, receipt_id: uuid.UUID) -> Receipt:
    receipt = db.scalar(
        select(Receipt).where(
            Receipt.id == receipt_id,
            Receipt.household_id == ctx.household_id,
            Receipt.deleted_at.is_(None),
        )
    )
    if receipt is None:
        raise NotFound("Receipt not found")
    _visible_transaction(db, ctx, receipt.transaction_id)  # private accounts: 404
    return receipt


def open_file(
    db: Session, ctx: HouseholdContext, settings: Settings, receipt_id: uuid.UUID
) -> tuple[Receipt, bytes]:
    receipt = _get(db, ctx, receipt_id)
    path = _path(settings, receipt.storage_key)
    try:
        data = path.read_bytes()
    except FileNotFoundError as exc:
        raise NotFound("The receipt file is missing from storage") from exc
    if hashlib.sha256(data).hexdigest() != receipt.stored_sha256:
        raise Conflict("The receipt file failed its integrity check")
    return receipt, data


def delete(db: Session, ctx: HouseholdContext, settings: Settings, receipt_id: uuid.UUID) -> None:
    """Remove a wrongly attached receipt (uploader or admin). The file is deleted."""
    ctx.require(Role.MEMBER)
    receipt = _get(db, ctx, receipt_id)
    if not ctx.is_admin and receipt.uploaded_by_user_id != ctx.user_id:
        raise Forbidden("Only the person who added it, or an admin, can remove a receipt")
    receipt.deleted_at = utcnow()
    receipt.deleted_by_user_id = ctx.user_id
    audit.record(
        db,
        action="receipt.removed",
        entity_type="receipt",
        entity_id=receipt.id,
        household_id=ctx.household_id,
        actor_user_id=ctx.user_id,
        details={"transaction_id": str(receipt.transaction_id)},
    )
    db.commit()
    _path(settings, receipt.storage_key).unlink(missing_ok=True)
