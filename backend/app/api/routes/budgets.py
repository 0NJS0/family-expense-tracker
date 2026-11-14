from fastapi import APIRouter

from app.api.deps import Ctx, DbSession
from app.schemas.budgets import BudgetAlertOut, BudgetSet, BudgetStatusOut, Month
from app.services import budgets

router = APIRouter(tags=["budgets"])


@router.get("/budgets", response_model=BudgetStatusOut)
def budget_status(ctx: Ctx, db: DbSession, month: Month | None = None) -> BudgetStatusOut:
    """Budgets in force in ``month`` (default: this month) against actual spending."""
    return budgets.status(db, ctx, budgets.parse_month(month) if month else None)


@router.put("/budgets", response_model=BudgetStatusOut)
def set_budget(body: BudgetSet, ctx: Ctx, db: DbSession) -> BudgetStatusOut:
    return budgets.set_budget(db, ctx, body)


@router.get("/budgets/alerts", response_model=list[BudgetAlertOut])
def budget_alerts(ctx: Ctx, db: DbSession, month: Month | None = None) -> list[BudgetAlertOut]:
    return [
        BudgetAlertOut(
            category_id=alert.category_id,
            name_en=category.name_en if category else None,
            name_bn=category.name_bn if category else None,
            month=alert.month,
            threshold_percent=alert.threshold_percent,
            budget_poisha=alert.budget_poisha,
            spent_poisha=alert.spent_poisha,
            created_at=alert.created_at,
        )
        for alert, category in budgets.list_alerts(
            db, ctx, budgets.parse_month(month) if month else None
        )
    ]
