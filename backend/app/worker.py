"""The background worker: slow jobs that must never hold up a request.

Run beside the API on the homelab (a separate Docker Compose service in
Milestone 6):

    uv run python -m app.worker

Each round it (1) reads one pending receipt with OCR, (2) at most once an
hour queues last month's AI summary for households that had entries, and
(3) writes one AI summary. Jobs are claimed with row locks
(``SKIP LOCKED``), so running two workers is safe. The model call happens
outside any database transaction; it can take many minutes.
"""

import logging
import signal
import time
from collections.abc import Callable
from datetime import timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

from sqlalchemy import select
from sqlalchemy.orm import Session

from app import llm, ocr
from app.config import Settings, get_settings
from app.database import build_engine, build_sessionmaker
from app.models import Household, Receipt
from app.services import ai_reports
from app.timeutil import utcnow

log = logging.getLogger("fet.worker")


def ocr_one(db: Session, settings: Settings) -> bool:
    """Read one pending receipt. Returns True if there was one."""
    receipt = db.scalar(
        select(Receipt)
        .where(Receipt.ocr_status == "pending", Receipt.deleted_at.is_(None))
        .order_by(Receipt.created_at)
        .limit(1)
        .with_for_update(skip_locked=True)
    )
    if receipt is None:
        return False
    household = db.get(Household, receipt.household_id)
    today = utcnow().astimezone(ZoneInfo(household.timezone if household else "Asia/Dhaka")).date()
    receipt.ocr_at = utcnow()
    if receipt.content_type != "image/jpeg":
        receipt.ocr_status = "unavailable"  # PDFs are kept, not read
    else:
        try:
            text = ocr.read_image(
                Path(settings.receipts_dir) / receipt.storage_key, settings.ocr_timeout_seconds
            )
            receipt.ocr_text = text
            receipt.ocr_amount_poisha = ocr.find_total(text)
            receipt.ocr_date = ocr.find_date(text, today)
            receipt.ocr_status = "done"
        except ocr.OcrUnavailable:
            receipt.ocr_status = "unavailable"
        except Exception:
            # The text may be private: log only the receipt id.
            log.warning("OCR failed for receipt %s", receipt.id)
            receipt.ocr_status = "failed"
    db.commit()
    return True


def ai_one(db: Session, settings: Settings, generate: llm.Generate | None) -> bool:
    """Write one AI summary. Returns True if there was one."""
    if generate is None:
        return False
    now = utcnow()
    report_id = ai_reports.claim(
        db, now, stale_after=timedelta(seconds=2 * settings.llama_timeout_seconds)
    )
    if report_id is None:
        return False
    try:
        facts, language = ai_reports.prepare(db, report_id)
        system, user = ai_reports.prompts(facts, language)
        db.close()  # no transaction stays open during the (long) model call
        started = time.monotonic()
        reply = generate(system, user)
        log.info("AI summary %s written in %.0f s", report_id, time.monotonic() - started)
        report = ai_reports.finish(db, report_id, reply, settings.llama_model)
        if report.status == "rejected":
            log.warning("AI summary %s rejected: %s", report_id, report.problem)
    except llm.LlmError as exc:
        ai_reports.fail(db, report_id, str(exc), utcnow())
    except Exception:
        db.rollback()
        ai_reports.fail(db, report_id, "internal error", utcnow())
        raise
    return True


def run_round(
    sessions: Callable[[], Session],
    settings: Settings,
    generate: llm.Generate | None,
    schedule: bool,
) -> bool:
    """One pass over every kind of job. Returns True if any work was done."""
    worked = False
    with sessions() as db:
        worked |= ocr_one(db, settings)
    if schedule and generate is not None:
        with sessions() as db:
            ai_reports.schedule_last_month(db, utcnow())
    with sessions() as db:
        worked |= ai_one(db, settings, generate)
    return worked


def main() -> None:  # pragma: no cover - exercised by running the worker
    settings = get_settings()
    logging.basicConfig(
        level=settings.log_level, format="%(asctime)s %(levelname)s %(name)s %(message)s"
    )
    engine = build_engine(settings.database_url)
    sessions = build_sessionmaker(engine)
    generate = llm.client(settings)
    log.info("worker started (AI %s)", "on" if generate else "off: FET_LLAMA_BASE_URL unset")
    stopping = False

    def stop(*_: object) -> None:
        nonlocal stopping
        stopping = True

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    last_schedule = 0.0
    while not stopping:
        schedule = time.monotonic() - last_schedule > 3600
        if schedule:
            last_schedule = time.monotonic()
        try:
            worked = run_round(sessions, settings, generate, schedule)
        except Exception:
            log.exception("worker round failed")
            worked = False
        if not worked:
            for _ in range(settings.worker_poll_seconds):
                if stopping:
                    break
                time.sleep(1)
    engine.dispose()


if __name__ == "__main__":  # pragma: no cover
    main()
