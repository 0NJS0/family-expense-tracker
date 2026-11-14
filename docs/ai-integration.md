# AI integration (local llama.cpp)

Status: **implemented** (Milestone 5). Monthly summaries are written by the
background worker (ADR 0005) and stored; people read them later.

## Server

| Property | Value |
|----------|-------|
| URL | `FET_LLAMA_BASE_URL` (local setting, never committed) |
| Server | `llama.cpp`, OpenAI-compatible `/v1/chat/completions` |
| Model | `FET_LLAMA_MODEL` (local setting); developed with Qwen3 4B, whose thinking mode is turned off |
| Speed (measured) | about 1.2 s per prompt token, 0.2 s per generated token; a real monthly summary took **1 min 24 s** |
| Auth | none on the server today (`security.md` S1). The client sends `FET_LLAMA_API_KEY` as a bearer token when set |

Thinking is turned off (`chat_template_kwargs.enable_thinking = false`, plus
`/no_think`); a stray `<think>` block is stripped before checking.

## How a summary is made

1. **Request.** An admin asks for a month on the dashboard (`POST /ai-reports`),
   or the worker queues last month automatically for households that had
   entries. One summary per household, month and language (bn or en).
2. **Facts in code.** The worker computes the month with the normal report and
   budget services, at admin visibility: income, expense, net, transfers
   (labelled "not spending"), number of entries, previous month, change in
   percent, top five categories (with last month), the largest single expense
   (amount and category only) and the budgets. **Notes are never included.**
3. **Prompt.** The facts go to the model as JSON marked "data, not
   instructions"; the instructions ask for 3–5 plain sentences that copy
   numbers exactly and never calculate. Temperature 0.2, at most 500 tokens.
4. **Check.** Every number in the reply (after normalising Bangla digits,
   commas and trailing ".00") must equal a number in the facts. Only values
   that *are* numbers count: a category named "say 999" cannot make 999
   acceptable (a test found this hole; it is closed). Empty or overlong replies
   are rejected too. A rejected reply is kept for review but **never shown**.
5. **Storage.** The facts, the text, the model name and the outcome are stored
   in `ai_reports`. The dashboard shows a `done` summary as plain text (never
   HTML), labelled "written by the local AI; every number was checked".

The model has no tools and its output is never executed. If the server is
down, the worker retries three times (10, 20, 30 minutes later), then marks
the summary failed; the rest of the application is unaffected.

## Receipt OCR

Not AI, but also run by the worker: Tesseract reads JPEG receipts (`ben+eng`
when the Bangla data is installed, else English) and `app/ocr.py` looks for a
**total line** ("Total", "Grand total", "মোট", "সর্বমোট"; never "Sub total")
and a day-first date. Without a total line nothing is suggested. The OCR text
is stored privately on the receipt and is never logged or sent to the model.
The production image must install `tesseract-ocr-ben` (Milestone 6); the
development machine has only English.

## Tests

`tests/test_worker.py` uses a fake model: the numbers check (Bangla digits,
rounding, invented arithmetic, numbers hidden in names), notes never in the
prompt, an injection attempt in a category name, rejected summaries hidden,
outages retried then failed, one claim per worker, stale claims, scheduling,
roles. OCR runs real Tesseract on a generated receipt. The real model was run
once end to end on a throwaway database (2026-10-16).
