"""Who may see which accounts and transactions (owner decision, 2026-10-16).

Shared accounts are visible to everyone in the household. A member-owned account
can be marked private: then only its owner and the household's admins see it,
and a transaction that touches it (either side of a transfer) is hidden from
everyone else, in lists, reports, exports and the device change feed.

Balances are the exception, by design: a visible account's balance always
includes every transaction that touches it, so a shared account's balance stays
true even when money came from someone's private account.
"""

import uuid

from sqlalchemy import ColumnElement, Select, and_, or_, select, true

from app.models import Account, Transaction
from app.services.context import HouseholdContext


def _hidden_account_ids(ctx: HouseholdContext) -> Select[uuid.UUID]:
    return select(Account.id).where(
        Account.household_id == ctx.household_id,
        Account.is_private.is_(True),
        Account.owner_user_id != ctx.user_id,
    )


def account_visible(ctx: HouseholdContext) -> ColumnElement[bool]:
    """Condition on ``Account`` rows the caller may see."""
    if ctx.is_admin:
        return true()
    return or_(Account.is_private.is_(False), Account.owner_user_id == ctx.user_id)


def transaction_visible(ctx: HouseholdContext) -> ColumnElement[bool]:
    """Condition on ``Transaction`` rows the caller may see."""
    if ctx.is_admin:
        return true()
    hidden = _hidden_account_ids(ctx)
    return and_(
        Transaction.account_id.not_in(hidden),
        or_(
            Transaction.destination_account_id.is_(None),
            Transaction.destination_account_id.not_in(hidden),
        ),
    )
