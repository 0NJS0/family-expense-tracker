import uuid
from datetime import date, datetime
from typing import Annotated, Any, Literal

from pydantic import StringConstraints

from app.schemas.common import ApiModel


class AiReportRequest(ApiModel):
    month: Annotated[str, StringConstraints(pattern=r"^\d{4}-(0[1-9]|1[0-2])$")]
    language: Literal["bn", "en"] = "bn"


class AiReportOut(ApiModel):
    id: uuid.UUID
    period_start: date
    language: str
    # queued | running | done | rejected | failed
    status: str
    # Only for "done": the checked summary. AI-written; every number was verified.
    text: str | None
    problem: str | None
    facts: dict[str, Any] | None
    model: str | None
    attempts: int
    created_at: datetime
    finished_at: datetime | None
