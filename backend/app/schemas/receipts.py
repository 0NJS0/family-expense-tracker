import uuid
from datetime import date, datetime

from app.schemas.common import ApiModel


class ReceiptOut(ApiModel):
    id: uuid.UUID
    transaction_id: uuid.UUID
    content_type: str
    size_bytes: int
    uploaded_by_user_id: uuid.UUID
    created_at: datetime
    # pending | done | failed | unavailable. Suggestions only; never applied automatically.
    ocr_status: str
    ocr_amount_poisha: int | None
    ocr_date: date | None
