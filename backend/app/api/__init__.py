from fastapi import APIRouter

from app.api.routes import (
    accounts,
    ai_reports,
    auth,
    budgets,
    health,
    households,
    receipts,
    recurring,
    reports,
    sync,
    transactions,
)

api_router = APIRouter(prefix="/api/v1")
for module in (
    health,
    auth,
    households,
    accounts,
    transactions,
    reports,
    sync,
    budgets,
    recurring,
    receipts,
    ai_reports,
):
    api_router.include_router(module.router)
