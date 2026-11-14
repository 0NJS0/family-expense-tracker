import uuid
from datetime import date, datetime
from typing import Annotated, Self

from pydantic import AwareDatetime, BaseModel, Field, StrictInt, StringConstraints, model_validator

from app.models import AccountKind, CategoryKind, TransactionType
from app.money import (
    MAX_ABS_OPENING_BALANCE_POISHA,
    MAX_TRANSACTION_POISHA,
    InvalidAmountError,
    parse_taka,
)
from app.schemas.common import ApiModel, ShortText

AmountPoisha = Annotated[StrictInt, Field(gt=0, le=MAX_TRANSACTION_POISHA)]
OpeningPoisha = Annotated[
    StrictInt, Field(ge=-MAX_ABS_OPENING_BALANCE_POISHA, le=MAX_ABS_OPENING_BALANCE_POISHA)
]
TakaText = Annotated[str, Field(min_length=1, max_length=32)]
Note = Annotated[str, StringConstraints(strip_whitespace=True, max_length=500)]
Reason = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=500)]


def _resolve_amount(amount_poisha: int | None, amount: str | None) -> int | None:
    """Accept either integer poisha or a decimal taka string, never both."""
    if amount_poisha is not None and amount is not None:
        raise ValueError("provide either amount_poisha or amount, not both")
    if amount is None:
        return amount_poisha
    try:
        value = parse_taka(amount)
    except InvalidAmountError as exc:
        raise ValueError(str(exc)) from exc
    if not 0 < value <= MAX_TRANSACTION_POISHA:
        raise ValueError("amount must be greater than zero and at most 1,000,000,000 taka")
    return value


# --- Accounts ---------------------------------------------------------------


class OpeningBalanceIn(ApiModel):
    amount_poisha: OpeningPoisha
    as_of_date: date


class OpeningBalanceCorrection(ApiModel):
    """The values the admin saw (to detect a concurrent change), the corrected ones and why."""

    previous_amount_poisha: OpeningPoisha
    previous_as_of_date: date
    amount_poisha: OpeningPoisha
    as_of_date: date
    reason: Reason


class OpeningBalanceOut(ApiModel):
    amount_poisha: int
    as_of_date: date
    created_at: datetime


class AccountCreate(ApiModel):
    name: ShortText
    kind: AccountKind
    institution: ShortText | None = None
    # Omit for a shared household account.
    owner_user_id: uuid.UUID | None = None
    # Private accounts need an owner; only the owner and admins see them.
    is_private: bool = False
    opening_balance: OpeningBalanceIn | None = None


class AccountUpdate(ApiModel):
    name: ShortText | None = None
    institution: ShortText | None = None
    is_archived: bool | None = None
    # null makes the account shared (which also makes it non-private).
    owner_user_id: uuid.UUID | None = None
    is_private: bool | None = None


class AccountOut(ApiModel):
    id: uuid.UUID
    name: str
    kind: AccountKind
    institution: str | None
    owner_user_id: uuid.UUID | None
    is_shared: bool
    is_private: bool
    is_archived: bool
    opening_balance: OpeningBalanceOut | None
    created_at: datetime


class AccountBalanceOut(ApiModel):
    account_id: uuid.UUID
    as_of_date: date | None
    has_opening_balance: bool
    opening_balance_poisha: int
    inflow_poisha: int
    outflow_poisha: int
    balance_poisha: int


class AccountBalanceRow(AccountBalanceOut):
    name: str
    kind: AccountKind
    is_shared: bool
    is_private: bool
    is_archived: bool


class AccountBalancesOut(ApiModel):
    as_of_date: date | None
    # Sum of the listed accounts' recorded balances. Transfers between them net to zero.
    total_poisha: int
    accounts: list[AccountBalanceRow]


# --- Categories -------------------------------------------------------------


class CategoryCreate(ApiModel):
    kind: CategoryKind
    name_en: ShortText
    name_bn: ShortText


class CategoryUpdate(ApiModel):
    name_en: ShortText | None = None
    name_bn: ShortText | None = None
    is_archived: bool | None = None


class CategoryOut(ApiModel):
    id: uuid.UUID
    kind: CategoryKind
    name_en: str
    name_bn: str
    is_default: bool
    is_archived: bool


# --- Transactions -----------------------------------------------------------


class TransactionCreate(ApiModel):
    client_id: uuid.UUID
    type: TransactionType
    amount_poisha: AmountPoisha | None = None
    amount: TakaText | None = None
    category_id: uuid.UUID | None = None
    account_id: uuid.UUID
    destination_account_id: uuid.UUID | None = None
    occurred_at: AwareDatetime
    note: Note | None = None
    # Defaults to the current user.
    attributed_user_id: uuid.UUID | None = None

    @model_validator(mode="after")
    def _amount(self) -> Self:
        resolved = _resolve_amount(self.amount_poisha, self.amount)
        if resolved is None:
            raise ValueError("amount_poisha or amount is required")
        self.amount_poisha, self.amount = resolved, None
        return self


class TransactionUpdate(ApiModel):
    """Partial update. ``version`` must match the stored version."""

    version: Annotated[StrictInt, Field(ge=1)]
    amount_poisha: AmountPoisha | None = None
    amount: TakaText | None = None
    category_id: uuid.UUID | None = None
    account_id: uuid.UUID | None = None
    destination_account_id: uuid.UUID | None = None
    occurred_at: AwareDatetime | None = None
    note: Note | None = None
    attributed_user_id: uuid.UUID | None = None

    @model_validator(mode="after")
    def _amount(self) -> Self:
        resolved = _resolve_amount(self.amount_poisha, self.amount)
        if self.amount is not None:
            self.amount_poisha, self.amount = resolved, None
            self.model_fields_set.discard("amount")
            self.model_fields_set.add("amount_poisha")
        return self


class TransactionVoid(ApiModel):
    version: Annotated[StrictInt, Field(ge=1)]
    reason: Reason


class TransactionOut(ApiModel):
    id: uuid.UUID
    client_id: uuid.UUID
    type: TransactionType
    amount_poisha: int
    category_id: uuid.UUID | None
    account_id: uuid.UUID
    destination_account_id: uuid.UUID | None
    occurred_at: datetime
    note: str | None
    created_by_user_id: uuid.UUID
    attributed_user_id: uuid.UUID
    version: int
    created_at: datetime
    updated_at: datetime
    voided_at: datetime | None
    voided_by_user_id: uuid.UUID | None
    void_reason: str | None


class SyncChangesOut(BaseModel):
    """A page of the change feed. Keep calling with ``next_cursor`` while ``has_more``."""

    items: list[TransactionOut]
    # Changed entries the caller may not see (private accounts): drop local copies.
    removed: list[uuid.UUID]
    next_cursor: str
    has_more: bool
