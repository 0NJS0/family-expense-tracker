# Changelog

## [Unreleased]

### Security (2026-11-14)
- Backups are owner-only: `deploy/backup/backup.sh` and `restore.sh` set
  `umask 077`; `deploy/verify.sh` reads the backup folder inside the container.
- The backup's database password moved out of the URL into
  `BACKUP_DATABASE_PASSWORD` (passed as `PGPASSWORD`), so it no longer shows in
  `ps`. **Update `deploy/.env`** (see `deploy/.env.example`).
- Database errors no longer carry statement values into logs
  (`hide_parameters=True` in `backend/app/database.py`).
- A void's reason is no longer copied into the audit log (`has_reason` only).
- Pillow 12.3.0 → 13.0.0 and `opentelemetry-api` 1.45.1 → 1.45.2 (known
  vulnerabilities).

### Changed: documentation (2026-11-14)
- `README.md` rewritten: what the app does, how it works (diagram),
  prerequisites, homelab and development setup, the end-to-end check.
- `docs/architecture.md`: the diagram and status describe the deployed stack
  (Caddy without TLS, worker, backups to a homelab folder); ADR 0005 listed.
- `docs/android.md`, `docs/requirements.md`, ADR 0004: status brought up to
  Milestone 5 and the M6 deployment.
- `docs/security.md`: 2026-11-14 dependency audit (S7), CSP now in place (S8),
  findings S13–S16, all fixed.
- `LICENSE` (MIT).
- Dashboard screenshots with demo data in `docs/screenshots/`, shown in the README.
- `dashboard/e2e/browser-check.mjs` finds a Chromium-based browser on `PATH` (or `E2E_BROWSER`).
- `.gitignore`: key and certificate files, logs, test output, Kotlin caches.

### Added: deployment and checks (2026-10-17)
- `deploy/`: Docker Compose for the homelab (API, background worker with
  Bangla OCR, dashboard behind Caddy, migrations, daily backups), using a fresh
  `fet_prod` database on the homelab's PostgreSQL. See `docs/deployment.md`.
- Daily backups of the database and receipts at 02:30, checked readable, with
  checksums; 14 daily and 12 monthly kept; restore script.
- Excel (XLSX) export next to CSV, with exact amounts.
- `scripts/e2e.sh`: one command for the end-to-end check (real backend, the
  dashboard in a real browser, and the phone app on the emulator).

### Changed
- Phone: Jetpack Compose BOM 2026.09.00 (runtime 1.12.1) and Navigation 3 1.2.0.


### Added: Milestone 5, alerts, receipts and AI (2026-10-17)
- Reminders on the phone: an evening reminder on days when nothing was entered
  (on by default) or every evening (off by default), at a chosen time; due
  rent and bills; budget alerts for admins. Each shown once; nothing leaves
  the homelab. Choices are saved on the server.
- Receipts: take a photo on the phone (works offline) or add a photo or PDF on
  the dashboard. Photos are cleaned of location and camera data, stored
  privately on the homelab, and only visible to people who can see the entry.
- Receipt reading (OCR) on the homelab suggests the total and date; nothing
  changes until someone taps "Use this amount".
- AI monthly summaries in Bangla or English, written in the background by the
  local model on the homelab and stored for later. Only figures computed by
  the app are sent (never notes), and a summary is shown only if every number
  in it matches those figures.
- A background worker (`python -m app.worker`) runs the slow jobs.
- Voice entry was evaluated and not built (no private Bangla recogniser
  confirmed yet).

### Fixed
- Phone screens no longer return early after reading the signed-in profile
  (suspected cause of a rare crash when signing out).

### Added: Milestone 4, offline reliability and family features (2026-10-16)
- Phone: transfers between accounts (offline), account balances, and
  correcting or cancelling entries **offline**. Every change is sent with the
  version that was seen; a lost reply is recognised, and a real conflict shows
  both versions so nothing is silently overwritten. Entries the server refused
  can be fixed and resent, or dropped.
- Change feed (`GET /sync/changes`): edits and cancellations made on the
  dashboard now reach every phone.
- Private accounts: an account owned by a member can be seen only by the owner
  and admins; other members' lists, reports, budgets, export and phones leave it
  out.
- Budgets: a monthly budget for the whole family and per category, with a
  warning level, a month-by-month history that never changes, and alerts that
  happen once. Dashboard Budgets page; budget card on the phone.
- Recurring entries (rent, salary, bills): due items are reminders; money is
  recorded only when someone taps "Paid" (or automatically, only if chosen).
  Dashboard Recurring page; "Due now" card on the phone.
- Audited correction of a wrong opening balance.
- Phone: switching between families for people in more than one.
- Database: migrations 0003–0006 (all additive); phone database version 2
  (automatic migration, every entry kept).

### Fixed
- Dashboard: any 409 on the Family & settings page was shown as "That name is
  already used"; other conflicts now show the server's explanation.

### Added: Manage area on the phone for admins (2026-10-16)
- Admins get a ⚙ পরিচালনা button on the phone's Home screen with reports and
  balances, every family member's transactions (filter, correct, void),
  accounts, categories, members and invitation links. It needs an internet
  connection. Members' app is unchanged. Every change is still authorised and
  audited by the server, and edits use the shown version so nothing is
  silently overwritten.

### Added: default accounts (2026-10-15)
- New households start with three shared accounts: নগদ (cash), বিকাশ
  (mobile wallet, provider bKash) and ব্যাংক (bank). They have no starting
  balance and no bank details; admins can rename them, set a provider, or
  archive them.

### Added: UI improvements (2026-10-15)
- Dashboard: redesigned layout with sidebar, theme switch (light, dark,
  automatic) and phone-friendly menu; biggest expenses and per-person spending
  on the Overview; a Family & settings page for members, invitations, accounts
  and categories; a public invitation-acceptance page.
- Backend: `by_member` in the summary report; `sort=largest` for transactions.
- Phone: category icons, quick-amount buttons, today's spending and recent
  entries on the home screen, a new app icon.

### Fixed
- Phone: tapping Save with a missing field seemed to do nothing (the message
  was scrolled out of view). The form now scrolls to it and shows a reminder
  next to Save.
- Backend tests no longer read a developer's local `backend/.env`.

### Added: Milestone 3, Android MVP (2026-10-15)
- `android/`: Kotlin + Compose app, Bangla by default, for entering expenses
  and income offline-first. Room outbox, WorkManager upload with idempotent
  retries, history with upload status, Keystore-encrypted session, no cloud
  backup.

### Fixed
- Android: the app opened in English on a fresh install (the language was set
  too early in the app lifecycle).
- Android: corrupted stored token data crashed the app; it now asks the user to
  sign in again.

### Added: Milestone 2, web dashboard (2026-10-15)
- `dashboard/`: React + TypeScript (strict) + ECharts admin dashboard in
  Bangla and English: Overview, Analytics and Transactions (filters,
  pagination, edit, void, CSV export).
- Backend: `compare_to=previous_year` on `GET /reports/summary` for
  year-over-year comparison.

### Fixed
- Year-to-date summaries were compared with the preceding ten months. The
  dashboard now asks for the same dates last year.

### Added: backend support for the dashboard (2026-10-15)
- `GET /accounts/balances`: every account's balance and a total, in one call.
- Previous-period comparison in `GET /reports/summary` (`comparison`), with
  per-category changes.
- `GET /transactions/export.csv`: CSV export with the same filters as the
  list, audited.
- `PATCH /members/{user_id}`: change role, deactivate or reactivate per household;
  the last active admin is protected.
- `POST /me/password`: change password; other devices are logged out.
- Migration 0002: `memberships.deactivated_at` (additive).

### Changed
- Deactivated members no longer see that household (`/me`, all
  household-scoped endpoints). New transactions and accounts cannot be
  attributed to or owned by them, but their existing entries stay editable.

### Added — Milestone 1: core backend and accounting (2026-10-15)
- FastAPI backend with PostgreSQL, SQLAlchemy 2 and an Alembic initial schema.
- Authentication: Argon2id passwords, short-lived JWT access tokens, rotating
  refresh sessions with reuse detection, per-device logout, login rate limiting.
- Households, memberships (admin, member, viewer), invitations, one-time
  bootstrap CLI.
- Accounts (shared or owned), one-time opening balances, derived balances.
- Bilingual (Bangla and English) default categories; custom categories.
- Income, expense and transfer transactions: idempotent creation, versioned
  edits, voids.
- Summary and time-series reports in the household timezone (Asia/Dhaka).
- Append-only audit trail.
- 167 automated tests against real PostgreSQL.
