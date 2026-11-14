import uuid
from datetime import date, datetime
from enum import StrEnum
from typing import Annotated, Self

from pydantic import Field, StrictInt, StringConstraints, model_validator

from app.models.budgets import MAX_BUDGET_POISHA
from app.money import InvalidAmountError, parse_taka
from app.schemas.common import ApiModel

# "2026-10": a calendar month in the household's timezone.
Month = Annotated[str, StringConstraints(pattern=r"^\d{4}-(0[1-9]|1[0-2])$")]
BudgetPoisha = Annotated[StrictInt, Field(gt=0, le=MAX_BUDGET_POISHA)]


class BudgetSet(ApiModel):
    """Set (or, with no amount, remove) a monthly budget from ``from_month`` on.

    Exactly one of ``amount_poisha`` / ``amount`` (decimal taka) sets it; both
    absent with ``remove: true`` removes it. Past months are never changed.
    """

    # Omit for the whole-household budget.
    category_id: uuid.UUID | None = None
    amount_poisha: BudgetPoisha | None = None
    amount: Annotated[str, Field(min_length=1, max_length=32)] | None = None
    remove: bool = False
    warn_percent: Annotated[StrictInt, Field(ge=1, le=99)] = 80
    # Defaults to the current month.
    from_month: Month | None = None

    @model_validator(mode="after")
    def _amount(self) -> Self:
        if self.amount is not None:
            if self.amount_poisha is not None:
                raise ValueError("provide either amount_poisha or amount, not both")
            try:
                value = parse_taka(self.amount)
            except InvalidAmountError as exc:
                raise ValueError(str(exc)) from exc
            if not 0 < value <= MAX_BUDGET_POISHA:
                raise ValueError("amount must be greater than zero and at most 1,000,000,000 taka")
            self.amount_poisha = value
            self.amount = None
        if self.remove == (self.amount_poisha is not None):
            raise ValueError("give an amount to set a budget, or remove: true to remove it")
        return self


class BudgetState(StrEnum):
    OK = "ok"
    WARNING = "warning"  # at or past the warning level
    OVER = "over"  # more spent than budgeted


class BudgetStatusItem(ApiModel):
    category_id: uuid.UUID | None
    name_en: str | None
    name_bn: str | None
    budget_poisha: int
    warn_percent: int
    # Since when this budget amount applies.
    effective_month: date
    spent_poisha: int
    # Negative when over budget.
    remaining_poisha: int
    # Spent as a share of the budget, in basis points (10000 = 100%), rounded down.
    used_basis_points: int
    state: BudgetState


class BudgetStatusOut(ApiModel):
    month: date
    # True for the current (unfinished) month.
    is_partial: bool
    # The whole-household budget first (if any), then categories by name.
    items: list[BudgetStatusItem]


class BudgetAlertOut(ApiModel):
    category_id: uuid.UUID | None
    name_en: str | None
    name_bn: str | None
    month: date
    threshold_percent: int
    budget_poisha: int
    spent_poisha: int
    created_at: datetime
