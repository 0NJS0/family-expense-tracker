# Tasks

Legend: `[x]` done and tested · `[ ]` to do · `[~]` partially done

## Milestone 1: Core backend and accounting ✅ (2026-10-15)

- [x] Repository structure, `.gitignore`, environment examples
- [x] FastAPI app factory, settings with production safety validation
- [x] PostgreSQL integration (SQLAlchemy 2, psycopg 3), health and readiness
- [x] Models and constraints: users, households, memberships, invitations,
      sessions, refresh tokens, accounts, opening balances, categories,
      transactions, audit events
- [x] Alembic initial migration and append-only audit trigger; migration drift test
- [x] Auth: Argon2id, JWT access, rotating refresh with reuse detection, logout,
      rate limiting
- [x] One-time bootstrap CLI
- [x] Roles (admin, member, viewer) and household isolation
- [x] Accounts and categories (bilingual defaults, create, rename, archive)
- [x] Opening balances (once, backdating rule)
- [x] Income, expense and transfer; idempotent create; versioned edit; void
- [x] Listing with filters, search and pagination
- [x] Reports: summary and time series (day, week, month, year; Dhaka timezone;
      Saturday week start; zero-fill; partial flags)
- [x] Audit trail and admin audit endpoint
- [x] 167 tests passing against PostgreSQL 18.6 at milestone close; ruff and mypy (strict) clean
- [x] Docs: requirements, architecture, data model, accounting rules, API,
      security, testing, ADRs

## Pre-dashboard backend additions ✅ (2026-10-15)

- [x] `GET /accounts/balances`: all balances in one call (2 queries), with a total
- [x] Summary previous-period comparison (calendar-month or preceding-days
      rule), exact changes, basis points, per-category changes
- [x] `GET /transactions/export.csv`: shared filters, Bangla-safe BOM,
      formula-injection safe, audited
- [x] `PATCH /members/{id}` (role, per-household deactivation, last-admin
      guard with lock); `POST /me/password` (revokes other sessions)
- [x] Migration 0002 (additive: `memberships.deactivated_at`)
- [x] 204 tests passing; 6 targeted mutations all caught

## Milestone 2: Web dashboard MVP ✅ (2026-10-15)

- [x] Vite + React 19 + strict TypeScript; typed API client with a shared
      refresh; session restore; household switching
- [x] Login (Bangla and English), expiry handling, network and permission messages
- [x] Overview: totals with previous-period change, in-progress notice,
      income/expense chart, category chart, all balances with total, recent entries
- [x] Analytics: day/week/month/year, household or one person,
      previous-period or year-over-year comparison, category-change table
- [x] Transactions: filters, search, pagination, detail panel, edit (only
      changed fields plus version; conflict-safe reload), void with reason, CSV export
- [x] Accessible charts (validated palette, legends, table view), exact money
      formatting, Bangla digits and lakh grouping
- [x] Backend: `compare_to=previous_year` on the summary (year-to-date fix)
- [x] 86 dashboard tests; 208 backend tests; typecheck and lint clean;
      npm audit and pip-audit clean
- [x] Manual end-to-end run: production build plus real backend in a headless
      browser, both languages, no errors

## Milestone 3: Android MVP ✅ (2026-10-15)

- [x] Kotlin + Compose app (`android/`), AGP 9.4, minSdk 26; Bangla default,
      English in Settings
- [x] Login with Keystore-encrypted refresh token; https-only release builds
- [x] Room outbox: save offline first with a client UUID; WorkManager upload with
      backoff and bounded retries; PENDING, SYNCED, FAILED and CONFLICT states
- [x] Home (month totals, waiting banner), Add expense and income (large
      targets, pinned Save), History with status, Settings (language, send now,
      guarded logout)
- [x] No cloud backup of app data
- [x] 36 JVM tests, 11 device tests (+1 real-backend test, passed manually);
      5/5 mutations caught
- [x] Real end-to-end: emulator → backend → server row verified exactly

## UI improvements ✅ (2026-10-15)

- [x] Dashboard redesign: sidebar layout, stat cards with icons, light/dark/auto
      theme, phone-width layout with slide-in menu
- [x] Overview: biggest expenses (backend `sort=largest`), who spent what
      (backend `by_member`)
- [x] Family & settings: members (roles, remove/restore, invitation links),
      accounts (create with exact signed opening balance, rename, archive),
      categories (create, rename, archive); accept-invitation page
- [x] Phone: category icons, quick amounts, today's spending, recent entries on
      home, wallet app icon; missing fields now scroll into view with a message
      next to Save (found by testing)
- [x] Tests: backend 210, dashboard 112, Android 39 JVM + 13 device; real-backend
      run on the emulator; mutation checks

## Phone Manage area for admins ✅ (2026-10-16)

- [x] Role saved at login and refreshed on sync; ⚙ পরিচালনা on Home for admins only
- [x] Reports and balances (server figures), all transactions with filters,
      correct and void with version (409 reloads, never overwrites)
- [x] Accounts (signed starting balance), categories, members and invitations
- [x] Tests: Android 46 JVM + 17 device; mutation checks (Manage shown to
      members; unchanged fields sent) were caught by the tests

## Next up

- [x] Default accounts নগদ, বিকাশ, ব্যাংক for new households (2026-10-15)

- [ ] **Owner actions**: rotate the `fet_test` password (S3); add `--api-key` to
      llama.cpp (S1); try the debug app on a phone and give feedback on
      text size and wording
- [ ] Admin-initiated password reset (one-time reset token; only for users who
      belong to no other household), needed before an older user relies on the app
- [x] Dashboard admin screens: members, accounts, categories (Family & settings)
- [ ] Automate the dashboard browser check and the Android real-backend test
- [ ] Before production: phone-side editing of budgets and recurring rules is
      dashboard-only (by design for now); reminders/notifications come in M5

## Milestone 4: Offline reliability and family features ✅ (2026-10-16)

Owner decisions (2026-10-16): account permissions = **private accounts**
(owner and admins see a private account; shared accounts are visible to all).

- [x] Phone: transfers (offline outbox), accounts and balances screen (cached
      server figures) — 2026-10-16
- [x] Phone: correct and cancel (void) entries offline through a change outbox
      (Room v2, auto-migration tested); version-checked; lost replies detected;
      conflicts shown side by side (re-apply or keep the server's); fix or drop
      ⚠ entries — 2026-10-16
- [x] Phone: household switcher in Settings (blocked while anything is unsent;
      the new household's feed starts fresh) — 2026-10-16
- [x] Sync change feed (`GET /sync/changes`, migration 0003 index); the phone
      pulls it page by page with a saved cursor — 2026-10-16
- [x] Private accounts (migration 0004): hidden from other members in lists,
      reports, export and the feed; phones drop copies; dashboard toggle — 2026-10-16
- [x] Budgets (migration 0005): household and category, from-month timeline
      (past months never change), warning level, alerts once per threshold;
      dashboard Budgets page; phone budget card — 2026-10-16
- [x] Recurring rules (migration 0006): weekly/monthly/yearly, pause, end date,
      idempotent occurrences, confirm ("paid") or skip, auto-post only when
      chosen; dashboard Recurring page; phone "due now" card — 2026-10-16
- [x] Audited opening-balance correction (sends the values seen; before, after
      and reason in the audit trail); dashboard form — 2026-10-16
- [x] Tests at milestone close: backend 256, dashboard 127, Android 88 JVM +
      32 device (+1 real-backend test, passed against a throwaway server);
      ruff, mypy, typecheck, lint, builds clean
- [x] Real end-to-end on a fresh database migrated 0001 → 0006 (HTTP smoke of
      every new endpoint + phone real-backend test)
- [ ] Watch: a rare Compose crash at activity teardown seen twice in device
      test runs (see `docs/testing.md`); not reproduced since a fix

## Milestone 5: Alerts, receipts and AI ✅ (2026-10-17)

Owner decision (2026-10-17): the local model is slow (~1 s per prompt token),
so AI reports are generated by a background worker on the homelab and stored;
people read them later, never waiting on a request.

- [x] Notification preferences (migration 0007, per person and household);
      phone: evening reminder (daily, or only when nothing was entered), due
      bills and admin budget alerts shown once; settings screen — 2026-10-17
- [x] Receipts (migration 0008): type from the bytes, metadata stripped, private
      storage, idempotent retries, visibility; phone camera capture offline
      (Room v3 outbox); dashboard view/add/remove — 2026-10-17
- [x] OCR suggestions by the background worker (Tesseract; totals only from
      total lines; applied only by a person) — 2026-10-17
- [x] AI monthly summaries (migration 0009): background worker (ADR 0005),
      facts in code, notes never sent, every number checked, rejected ones
      never shown; dashboard page; phone (admins) — real model run: 1 min 24 s
- [x] Background worker `python -m app.worker` (OCR, monthly scheduling, AI)
- [x] Voice entry evaluated (`docs/voice-evaluation.md`): not built; needs an
      on-device Bangla recognizer check on the owner's phone
- [x] Tests at milestone close: backend 300, dashboard 134, Android 100 JVM +
      38 device (+1 real-backend test); ruff, mypy, typecheck, lint, builds clean
- [ ] Owner: install `tesseract-ocr-ben` on the homelab (in the M6 image) and
      add `--api-key` to llama.cpp (S1)
- [x] Rare Compose teardown crash: early returns removed, Compose 1.12.1 and
      Navigation 3 1.2.0; ten clean full device runs in a row (cause never
      reproduced on demand; keep an eye on real use) — 2026-10-17

## Milestone 6: Production hardening (in progress)

Owner decisions (2026-10-17): the fresh production database `fet_prod` is on
the homelab's existing PostgreSQL; access is plain HTTP on the home Wi-Fi and
a VPN (no TLS); daily backups go to a folder on the homelab.

- [x] Docker Compose (`deploy/`): migrate job, API, worker (Bangla OCR), Caddy
      serving the dashboard and proxying `/api`, backup; read-only containers,
      no new privileges, dropped capabilities, health checks; `verify.sh`
- [x] Daily backups (pg_dump + receipts, checked readable, checksums,
      14 daily + 12 monthly) and `restore.sh`; script tested with stubs
- [ ] **Owner:** run `deploy/setup-database.sql`, add the `pg_hba.conf` line,
      start the stack, run `deploy/verify.sh`, and test one restore
      (`docs/deployment.md`)
- [x] XLSX export (exact amounts, no formulas) — 2026-10-17
- [x] Automated end-to-end check `scripts/e2e.sh` (backend + dashboard in a
      headless browser + phone on the emulator) — passed 2026-10-17
- [ ] TLS: not wanted for now (owner decision); the phone keeps the debug build
- [ ] APK signing (a stable key, so the app can be updated without uninstalling)
- [ ] Accessibility review
- [x] Security review and dependency audit (2026-11-14); findings in
      `docs/security.md`
- [x] Fixed the review findings (2026-11-14): Pillow 13 (S7), backup file
      permissions (S13), `hide_parameters=True` (S14), backup password off the
      command line (S15), no void reason in audit details (S16)
- [ ] **Owner, before deploying:** in `deploy/.env`, remove the password from
      `BACKUP_DATABASE_URL` and set `BACKUP_DATABASE_PASSWORD`; `chmod 700` the
      backup folder
