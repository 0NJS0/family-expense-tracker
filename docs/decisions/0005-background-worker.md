# ADR 0005: Slow work runs in a background worker

Status: accepted (2026-10-17, owner decision)

## Context

The local model (a small model on llama.cpp, on a CPU) is slow: about a second
per prompt token, so a monthly summary takes minutes. Receipt OCR also takes
seconds per image. Neither may hold up an API request, and a person must
never wait on a spinner for them.

## Decision

A separate process, `python -m app.worker`, runs beside the API on the
homelab (its own Docker Compose service in Milestone 6). It shares the
database and the receipt storage. Each round it:

1. reads one pending receipt with Tesseract and stores a suggested amount and
   date (suggestions only; a person applies them);
2. at most hourly, queues last month's AI summary for households that had
   entries (idempotent: one per household, month and language);
3. writes one AI summary: facts computed in code, words from the model,
   every number checked; the model call runs outside any database
   transaction.

Jobs are claimed with `SELECT … FOR UPDATE SKIP LOCKED`, so two workers are
safe; a summary left `running` by a crashed worker is picked up again after
twice the model timeout. Model outages are retried three times (10, 20, 30
minutes apart), then marked failed for an admin to retry.

## Consequences

- Results appear later: the dashboard says so and checks back by itself.
- The API stays fast and works when the model or Tesseract is missing
  (summaries simply wait; receipts are marked "unavailable").
- One more process to deploy and monitor (Milestone 6).
