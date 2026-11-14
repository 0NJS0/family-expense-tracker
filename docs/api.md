# API reference (v1)

Base path `/api/v1`. JSON only. In development, an interactive OpenAPI UI is at
`/docs`; it is disabled in production.

Every endpoint listed here is **implemented and covered by tests** in
`backend/tests/`.

## Conventions

- **Auth**: `Authorization: Bearer <access_token>`, except where marked public.
- **Household**: `X-Household-ID: <uuid>`. Optional when the user belongs to
  exactly one household; required (`422`) when they belong to several.
- **Money**: integer poisha (`*_poisha`). Inputs may use `amount` (a decimal taka
  string) instead of `amount_poisha`; see `accounting-rules.md`.
- **Dates**: `date_from` and `date_to` are inclusive local dates (`YYYY-MM-DD`) in
  the household timezone. Timestamps must include an offset.
- **Pagination**: `limit` (1–200, default 50) and `offset`. Responses are
  `{items, total, limit, offset}`.
- **Errors**: `{"error": {"code": "...", "message": "...", "details": [...]}}`.

| Status | Code | Meaning |
|--------|------|---------|
| 401 | `unauthorized` | missing, invalid or expired token, or revoked session |
| 403 | `forbidden` | role too low, or not the creator |
| 404 | `not_found` | does not exist **or** belongs to another household |
| 409 | `conflict` | duplicate name, stale `version`, already voided, `client_id` reused, opening balance already set or changed meanwhile, occurrence already resolved |
| 422 | `validation_failed` | invalid input (details list the fields) |
| 429 | `rate_limited` | too many attempts |

## Health (public)

| Method | Path | Notes |
|--------|------|-------|
| GET | `/health` | liveness: `{"status":"ok"}` |
| GET | `/ready` | readiness: checks the database, `503` if unavailable |

## Authentication

| Method | Path | Body | Response |
|--------|------|------|----------|
| POST | `/auth/login` (public, rate limited) | `{username, password, device_name?}` | `TokenPair` |
| POST | `/auth/refresh` (public, rate limited) | `{refresh_token}` | `TokenPair` (rotated) |
| POST | `/auth/logout` (public) | `{refresh_token}` | `204`; revokes that session |
| GET | `/me` | | `{user, memberships[{household_id, household_name, role}]}`; households where the user was deactivated are omitted |
| POST | `/me/password` (rate limited) | `{current_password, new_password}` | `204`; every **other** session of the user is revoked. `401` wrong current password, `422` same or too short |

`TokenPair` = `{token_type, access_token, access_token_expires_at,
refresh_token, refresh_token_expires_at}`.

## Households and members

| Method | Path | Role | Notes |
|--------|------|------|-------|
| POST | `/households` | any user | `{name, timezone?="Asia/Dhaka", week_start?=5}`; caller becomes admin; default categories seeded |
| GET | `/households/current` | viewer | |
| GET | `/members` | viewer | `[{user_id, username, display_name, role, is_active, joined_at}]`, including deactivated members |
| PATCH | `/members/{user_id}` | admin | `{role?, is_active?}`; `409` if it would leave no active admin. Deactivation is per household, takes effect immediately, and keeps history |
| POST | `/members/invitations` | admin | `{display_name, role?="member"}` → `{id, role, display_name, expires_at, token}`; the token is shown once |
| POST | `/invitations/accept` | public, rate limited | `{token, username, password, preferred_language?="bn"}` → user (`201`) |

## Accounts

| Method | Path | Role | Notes |
|--------|------|------|-------|
| GET | `/accounts?include_archived=` | viewer | |
| POST | `/accounts` | admin | `{name, kind, institution?, owner_user_id?, is_private?=false, opening_balance?{amount_poisha, as_of_date}}`; a private account needs an owner (`422`) |
| GET | `/accounts/{id}` | viewer | |
| PATCH | `/accounts/{id}` | admin | `{name?, institution?, is_archived?, owner_user_id?, is_private?}`; `owner_user_id: null` makes it shared (and not private) |
| POST | `/accounts/{id}/opening-balance` | admin | `{amount_poisha, as_of_date}`; once only (`409` after) |
| POST | `/accounts/{id}/opening-balance/correct` | admin | `{previous_amount_poisha, previous_as_of_date, amount_poisha, as_of_date, reason}`; `409` if the stored values differ from `previous_*` (someone changed them) or the new date is after an existing transaction; `422` if nothing changes; audited with before, after and reason |
| GET | `/accounts/balances?as_of=&include_archived=` | viewer | `{as_of_date, total_poisha, accounts[{account_id, name, kind, is_shared, is_archived, …balance fields}]}`; every account in one call |
| GET | `/accounts/{id}/balance?as_of=` | viewer | `{account_id, as_of_date, has_opening_balance, opening_balance_poisha, inflow_poisha, outflow_poisha, balance_poisha}` |

`kind` ∈ `cash | bank | mobile_wallet | other`.

## Categories

| Method | Path | Role | Notes |
|--------|------|------|-------|
| GET | `/categories?kind=&include_archived=` | viewer | |
| POST | `/categories` | admin | `{kind, name_en, name_bn}` |
| PATCH | `/categories/{id}` | admin | `{name_en?, name_bn?, is_archived?}`; the kind cannot change |

## Transactions

| Method | Path | Role | Notes |
|--------|------|------|-------|
| POST | `/transactions` | member | `201` created, **`200` idempotent replay**, `409` if `client_id` was reused with different data |
| GET | `/transactions?sort=newest\|largest` | viewer | `largest` orders by amount (then newest); filters: `date_from`, `date_to`, `type`, `account_id` (source or destination), `category_id`, `member_id` (attributed), `q` (note search), `include_voided` |
| GET | `/transactions/export.csv` | viewer | same filters as the list (no pagination, max 100,000 rows). UTF-8 with BOM; formula-like cells prefixed with `'`; audited as `transactions.exported` |
| GET | `/transactions/export.xlsx` | viewer | the same columns and filters as an Excel workbook: `amount_taka` a number written with its exact digits (`#,##0.00`), `amount_poisha` an integer, `local_date` a real date; every text cell stored as text (never a formula); audited with `format: xlsx` |
| GET | `/transactions/{id}` | viewer | voided transactions remain retrievable |
| PATCH | `/transactions/{id}` | member (own) / admin | requires `version`; `409` if stale (`details: [{current_version}]`); `type` cannot change |
| POST | `/transactions/{id}/void` | member (own) / admin | `{version, reason}` |

Create body:

```jsonc
{
  "client_id": "b8f1…",            // UUID generated by the client, reused on every retry
  "type": "expense",               // income | expense | transfer
  "amount_poisha": 12050,          // or "amount": "120.50"
  "category_id": "…",              // income/expense only
  "account_id": "…",
  "destination_account_id": null,  // transfer only
  "occurred_at": "2026-03-05T12:00:00+06:00",
  "note": "rice",
  "attributed_user_id": null       // defaults to the caller
}
```

CSV columns: `transaction_id, local_date, occurred_at_local, type, amount_taka,
amount_poisha, category_en, category_bn, account, destination_account,
attributed_to, entered_by, note, voided_at, void_reason, created_at`.

## Reports

| Method | Path | Role | Notes |
|--------|------|------|-------|
| GET | `/reports/summary?date_from=&date_to=&member_id=&compare_to=previous_period\|previous_year` | viewer | income, expense, net, transfer volume, count, `expense_by_category`, `income_by_category`, `by_member` (per attributed person: income, expense, count; sums to the totals, transfers excluded), `is_partial`, `comparison` (below) |
| GET | `/reports/timeseries?granularity=day\|week\|month\|year&date_from=&date_to=&member_id=` | viewer | zero-filled buckets `{start, end, income_poisha, expense_poisha, net_poisha, is_partial}`; at most 1000 buckets |

Default range: the first of the current month to today.

`comparison` holds the previous period's totals and the changes from it:
`{mode, date_from, date_to, income_poisha, expense_poisha, net_poisha,
transfer_poisha, transaction_count, income_change_poisha, expense_change_poisha,
net_change_poisha, income_change_basis_points, expense_change_basis_points,
category_changes[{category_id, kind, name_en, name_bn, current_poisha,
previous_poisha, change_poisha}]}`. Basis points are 1/100 of a percent
(`1250` = +12.5%), rounded half-to-even, or `null` when the previous value is 0.
How the previous period is chosen is defined in `accounting-rules.md`.

Accounts and transactions respect **private accounts** (see
`accounting-rules.md`): for members and viewers, someone else's private account
and every transaction touching it are absent from every list, report, export and
the change feed, and return `404` when addressed directly.

## Sync (devices)

| Method | Path | Role | Notes |
|--------|------|------|-------|
| GET | `/sync/changes?cursor=&limit=1..500` | viewer | transactions changed after `cursor` (voids included), oldest change first: `{items[TransactionOut], removed[client_id], next_cursor, has_more}`. Keep calling while `has_more`. `removed` lists changed entries the caller may not see (private accounts): drop local copies. The cursor is opaque; a malformed one is `422`. The end-of-feed cursor is never later than "now − 5 minutes", so recent changes may be sent again (merge by `version`) |

## Budgets

| Method | Path | Role | Notes |
|--------|------|------|-------|
| GET | `/budgets?month=YYYY-MM` | viewer | budgets in force in the month (default: this month) against spending: `{month, is_partial, items[{category_id (null = whole household), name_en, name_bn, budget_poisha, warn_percent, effective_month, spent_poisha, remaining_poisha, used_basis_points, state: ok\|warning\|over}]}`. Spending is what the caller may see |
| PUT | `/budgets` | admin | `{category_id?, amount_poisha \| amount \| remove: true, warn_percent?=80 (1–99), from_month?=this month}`; past months `422`; removing a budget that does not exist `404` |
| GET | `/budgets/alerts?month=` | admin | thresholds crossed: `[{category_id, name_en, name_bn, month, threshold_percent, budget_poisha, spent_poisha, created_at}]`; each (scope, month, threshold) at most once |

## Recurring entries

| Method | Path | Role | Notes |
|--------|------|------|-------|
| GET | `/recurring-rules` | viewer | rules (hidden if on someone else's private account) with `next_due_date`, `last_processed_date`, `paused`, `version` |
| POST | `/recurring-rules` | admin | `{name, type, amount_poisha \| amount, category_id?, account_id, destination_account_id?, attributed_user_id?, note?, frequency: weekly\|monthly\|yearly, interval?=1 (1–12), start_date, end_date?, auto_post?=false}` |
| PATCH | `/recurring-rules/{id}` | admin | `{version, name?, amount…?, category_id?, account_id?, destination_account_id?, note?, end_date?, auto_post?, paused?}`; `409` if stale. The schedule (frequency, interval, start) cannot change: make a new rule. Resuming skips what fell due while paused |
| GET | `/recurring/occurrences?status=pending\|posted\|skipped` | viewer | generates everything due up to today first (idempotent), then lists: `[{id, rule_id, rule_name, type, amount_poisha, …, due_date, status, transaction_id}]` |
| POST | `/recurring/occurrences/{id}/confirm` | member | `{amount_poisha \| amount?, occurred_on?}` → creates the transaction (12:00 local on the date). Repeating returns the same result; a skipped one is `409` |
| POST | `/recurring/occurrences/{id}/skip` | member | nothing is recorded; a posted one is `409` |

## Audit

| Method | Path | Role | Notes |
|--------|------|------|-------|
| GET | `/audit-events?entity_id=` | admin | paginated, newest first, household-scoped |

## Reminders

| Method | Path | Role | Notes |
|--------|------|------|-------|
| GET | `/me/notifications` | viewer | this person's choices in the current household (defaults if never set): `{daily_reminder (false), missing_entry_reminder (true), reminder_time ("20:00:00", household time), recurring_due (true), budget_alerts (true)}` |
| PUT | `/me/notifications` | viewer | any of the same fields; audited. The phone schedules the reminders itself |

## Receipts

| Method | Path | Role | Notes |
|--------|------|------|-------|
| POST | `/transactions/{id}/receipts` | member (own entry) / admin | the request **body is the file** (any `Content-Type`); JPEG, PNG, WebP or PDF by its bytes, at most 10 MB (`422` otherwise); `201`, or `200` with the existing receipt when the same file was already attached; `409` on a voided entry |
| GET | `/transactions/{id}/receipts` | viewer | `[{id, transaction_id, content_type, size_bytes, uploaded_by_user_id, created_at, ocr_status: pending\|done\|failed\|unavailable, ocr_amount_poisha, ocr_date}]`; OCR values are suggestions only |
| GET | `/receipts/{id}/file` | viewer | the stored file (photos re-encoded as JPEG, metadata removed); `private, no-store`, `nosniff`, sandbox CSP; `409` if the file fails its checksum |
| DELETE | `/receipts/{id}` | uploader / admin | removes it (file deleted, record kept and audited) |

A receipt is visible exactly when its transaction is (`404` otherwise).

## AI summaries

| Method | Path | Role | Notes |
|--------|------|------|-------|
| GET | `/ai-reports` | admin | newest first: `{id, period_start, language, status: queued\|running\|done\|rejected\|failed, text (only when done), problem, facts, model, attempts, created_at, finished_at}` |
| POST | `/ai-reports` | admin | `{month: "YYYY-MM", language?: bn\|en}` → `202`; written later by the worker. Asking again redoes a finished one. `422` if AI is not configured or the month has not started |
| GET | `/ai-reports/{id}` | admin | one summary |

## Not yet implemented

Voice entry (`voice-evaluation.md`) and admin-initiated password reset. See `TASKS.md`.
