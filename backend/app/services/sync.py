"""Change feed for devices (docs/offline-sync.md).

A device asks for every transaction of its household that changed since its
cursor, page by page, and merges them by ``version``. Voided transactions are
included, so a void made elsewhere reaches every phone.

``updated_at`` is PostgreSQL's transaction start time, and transactions can
commit out of order: a row may become visible with an ``updated_at`` slightly
earlier than one already sent. Rows older than ``OVERLAP`` are settled (no
request runs that long). So the cursor handed out at the end of a feed is never
later than "now - OVERLAP": the next sync re-sends at most the last few minutes
of changes, and the device's merge by version makes the repeat harmless.
Within one feed, pages use an exact keyset so paging always makes progress.
"""

import base64
import binascii
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

from sqlalchemy import and_, func, or_, select
from sqlalchemy.orm import Session

from app.errors import ValidationFailed
from app.models import Transaction
from app.services import visibility
from app.services.context import HouseholdContext

OVERLAP = timedelta(minutes=5)
_NIL = uuid.UUID(int=0)
_EPOCH = datetime(2000, 1, 1, tzinfo=UTC)


@dataclass(frozen=True)
class Cursor:
    updated_at: datetime
    id: uuid.UUID

    def encode(self) -> str:
        micros = int(self.updated_at.timestamp() * 1_000_000)
        raw = f"v1:{micros}:{self.id}".encode()
        return base64.urlsafe_b64encode(raw).decode().rstrip("=")

    @staticmethod
    def decode(token: str) -> "Cursor":
        try:
            raw = base64.urlsafe_b64decode(token + "=" * (-len(token) % 4)).decode()
            version, micros, ident = raw.split(":")
            if version != "v1":
                raise ValueError(version)
            when = datetime.fromtimestamp(int(micros) / 1_000_000, tz=UTC)
            return Cursor(when, uuid.UUID(ident))
        except (ValueError, binascii.Error, UnicodeDecodeError) as exc:
            raise ValidationFailed("Invalid sync cursor") from exc


@dataclass(frozen=True)
class Changes:
    items: list[Transaction]
    # Client ids of changed transactions the caller may no longer see (private
    # accounts): the device drops its copies. Nothing else about them is sent.
    removed: list[uuid.UUID]
    next_cursor: str
    has_more: bool


def changes_since(db: Session, ctx: HouseholdContext, cursor: str | None, limit: int) -> Changes:
    """Transactions of the caller's household changed after ``cursor``, oldest change first."""
    start = Cursor.decode(cursor) if cursor else Cursor(_EPOCH, _NIL)
    rows = list(
        db.execute(
            select(Transaction, visibility.transaction_visible(ctx))
            .where(
                Transaction.household_id == ctx.household_id,
                or_(
                    Transaction.updated_at > start.updated_at,
                    and_(Transaction.updated_at == start.updated_at, Transaction.id > start.id),
                ),
            )
            .order_by(Transaction.updated_at, Transaction.id)
            .limit(limit + 1)
        ).all()
    )
    has_more = len(rows) > limit
    rows = rows[:limit]
    last = Cursor(rows[-1][0].updated_at, rows[-1][0].id) if rows else start
    if has_more:
        nxt = last
    else:
        # End of the feed: never past the settled boundary, so late commits are not missed.
        now: datetime = db.execute(select(func.now())).scalar_one()
        settled = Cursor(now - OVERLAP, _NIL)
        nxt = min(last, settled, key=lambda c: (c.updated_at, c.id))
    return Changes(
        items=[t for t, visible in rows if visible],
        removed=[t.client_id for t, visible in rows if not visible],
        next_cursor=nxt.encode(),
        has_more=has_more,
    )
