import uuid

from fastapi import APIRouter, status

from app.api.deps import AppSettings, Ctx, DbSession
from app.errors import NotFound, ValidationFailed
from app.models import AiReport
from app.schemas.ai_reports import AiReportOut, AiReportRequest
from app.services import ai_reports, budgets

router = APIRouter(tags=["ai"])


def _out(report: AiReport) -> AiReportOut:
    out = AiReportOut.model_validate(report)
    # A rejected reply is kept for review, but never presented as a summary.
    return out if report.status == "done" else out.model_copy(update={"text": None})


@router.get("/ai-reports", response_model=list[AiReportOut])
def list_reports(ctx: Ctx, db: DbSession) -> list[AiReportOut]:
    """Monthly AI summaries (admins): generated later by the homelab worker."""
    return [_out(r) for r in ai_reports.list_reports(db, ctx)]


@router.post("/ai-reports", response_model=AiReportOut, status_code=status.HTTP_202_ACCEPTED)
def request_report(
    body: AiReportRequest, ctx: Ctx, db: DbSession, settings: AppSettings
) -> AiReportOut:
    if not settings.llama_base_url:
        raise ValidationFailed("AI summaries are not configured on this server")
    month = budgets.parse_month(body.month)
    return _out(ai_reports.request_report(db, ctx, month, body.language))


@router.get("/ai-reports/{report_id}", response_model=AiReportOut)
def get_report(report_id: uuid.UUID, ctx: Ctx, db: DbSession) -> AiReportOut:
    for report in ai_reports.list_reports(db, ctx):
        if report.id == report_id:
            return _out(report)
    raise NotFound("Report not found")
