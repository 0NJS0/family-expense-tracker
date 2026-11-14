import uuid
from datetime import date, datetime
from enum import StrEnum
from typing import Annotated, Self

from pydantic import Field, StrictInt, model_validator

from app.models import TransactionType
from app.recurrence import Frequency
from app.schemas.common import ApiModel, ShortText
from app.schemas.finance import AmountPoisha, Note, TakaText, _resolve_amount


class RecurringRuleCreate(ApiModel):
    name: ShortText
    type: TransactionType
    amount_poisha: AmountPoisha | None = None
    amount: TakaText | None = None
    category_id: uuid.UUID | None = None
    account_id: uuid.UUID
    destination_account_id: uuid.UUID | None = None
    # Defaults to the admin creating the rule.
    attributed_user_id: uuid.UUID | None = None
    note: Note | None = None
    frequency: Frequency
    interval: Annotated[StrictInt, Field(ge=1, le=12)] = 1
    start_date: date
    end_date: date | None = None
    # Post without asking. Off by default: a due bill is not proof it was paid.
    auto_post: bool = False

    @model_validator(mode="after")
    def _check(self) -> Self:
        self.amount_poisha = _resolve_amount(self.amount_poisha, self.amount)
        self.amount = None
        if self.amount_poisha is None:
            raise ValueError("amount is required")
        if self.end_date is not None and self.end_date < self.start_date:
            raise ValueError("end_date must not be before start_date")
        return self


class RecurringRuleUpdate(ApiModel):
    version: Annotated[StrictInt, Field(ge=1)]
    name: ShortText | None = None
    amount_poisha: AmountPoisha | None = None
    amount: TakaText | None = None
    category_id: uuid.UUID | None = None
    account_id: uuid.UUID | None = None
    destination_account_id: uuid.UUID | None = None
    note: Note | None = None
    end_date: date | None = None
    auto_post: bool | None = None
    paused: bool | None = None

    @model_validator(mode="after")
    def _check(self) -> Self:
        if self.amount is not None or self.amount_poisha is not None:
            self.amount_poisha = _resolve_amount(self.amount_poisha, self.amount)
            self.amount = None
        return self


class RecurringRuleOut(ApiModel):
    id: uuid.UUID
    name: str
    type: TransactionType
    amount_poisha: int
    category_id: uuid.UUID | None
    account_id: uuid.UUID
    destination_account_id: uuid.UUID | None
    attributed_user_id: uuid.UUID
    note: str | None
    frequency: Frequency
    interval: int
    start_date: date
    end_date: date | None
    auto_post: bool
    paused: bool
    next_due_date: date | None
    last_processed_date: date | None
    version: int


class OccurrenceStatus(StrEnum):
    PENDING = "pending"
    POSTED = "posted"
    SKIPPED = "skipped"


class OccurrenceOut(ApiModel):
    id: uuid.UUID
    rule_id: uuid.UUID
    rule_name: str
    type: TransactionType
    # The rule's expected amount; the confirmed amount may differ.
    amount_poisha: int
    category_id: uuid.UUID | None
    account_id: uuid.UUID
    destination_account_id: uuid.UUID | None
    due_date: date
    status: OccurrenceStatus
    transaction_id: uuid.UUID | None
    resolved_at: datetime | None


class OccurrenceConfirm(ApiModel):
    """Record the payment. Omit the amount to use the rule's expected amount."""

    amount_poisha: AmountPoisha | None = None
    amount: TakaText | None = None
    # Defaults to the due date.
    occurred_on: date | None = None

    @model_validator(mode="after")
    def _check(self) -> Self:
        if self.amount is not None or self.amount_poisha is not None:
            self.amount_poisha = _resolve_amount(self.amount_poisha, self.amount)
            self.amount = None
        return self
