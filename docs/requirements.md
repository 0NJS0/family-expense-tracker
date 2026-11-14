# Requirements

## Purpose

A private, self-hosted expense tracker for one family (with room for more
households later). All financial data stays on the family's homelab. No cloud
AI, hosted finance services or external analytics.

## Users

| Persona | Device | Needs |
|---------|--------|-------|
| Primary user: older adult, prefers Bangla | Android phone | Very simple, large and high-contrast entry of expenses and income; works offline; clear confirmation |
| Administrator: family member | PC browser | Overview, analytics, corrections, family and account administration |
| Other family members | Android or browser | Enter their own transactions; viewers can only look |

## Fixed configuration

BDT currency stored as integer poisha · `Asia/Dhaka` default timezone · Bangla and
English UI · Android (Kotlin, Compose, Room, WorkManager) · React, TypeScript and
ECharts dashboard · Python, FastAPI, SQLAlchemy 2, Pydantic and Alembic ·
PostgreSQL · a local llama.cpp model (optional) · VPN-only remote access
· daily backups to a folder on the homelab · **Docker Compose** deployment on the homelab (owner's decision,
2026-10-15).

## Priorities (in order)

1. Financial correctness · 2. Data integrity and recoverability · 3. Security and
privacy · 4. Offline reliability · 5. Accessibility for an older Bangla speaker ·
6. Maintainability · 7. Performance and polish.

## Functional requirements

Status: ✅ implemented and tested · ⏳ planned (milestone).

### Identity and households
- ✅ Username and password login; short-lived access tokens; revocable, rotating
  refresh sessions; logout per device.
- ✅ Households with admin, member and viewer roles; a user may belong to several
  households.
- ✅ Invitations (admin only, single use, expire after 7 days).
- ✅ One-time administrative bootstrap that refuses to run twice.
- ✅ Password change (logs out other devices); role changes; per-household
  deactivation; last-admin protection.
- ⏳ Admin-initiated password reset for a forgotten password.

### Accounts and categories
- ✅ Cash, bank, mobile wallet and other accounts; shared or owned by a member;
  archive.
- ✅ Opening balance, set once per account, not counted as income.
- ✅ Derived balances, including as of a date, for one account or all accounts in one call.
- ✅ Bilingual default categories; admins can create, rename and archive them.
- ✅ Private accounts (only the owner and admins see them); audited opening-balance
  correction.

### Transactions
- ✅ Income, expense and transfer with validation and database constraints.
- ✅ Idempotent creation (client UUID); optimistic-concurrency edits; void with a
  reason instead of delete.
- ✅ Creator and attributed member tracked separately.
- ✅ Listing with date, type, account, category, member and note-search filters,
  plus pagination.
- ✅ Recurring transactions (confirm or skip; automatic only when chosen).
- ✅ Receipts (photo or PDF, metadata stripped, private, OCR suggestions).

### Reports
- ✅ Summary: income, expense, net, transfer volume, per-category breakdown,
  household or individual.
- ✅ Time series: day, week (Saturday start, configurable), month and year;
  zero-filled; partial-period flags.
- ✅ Previous-period comparison with per-category changes.
- ✅ CSV export (exact amounts, Bangla-safe, formula-injection safe, audited).
- ✅ Monthly budgets (household and category) vs. actual, warning levels, alerts
  once per threshold, history by month.
- ✅ XLSX export (exact amounts, no formulas).

### Audit
- ✅ Append-only audit trail for logins, financial changes and admin changes,
  without secrets or note contents. Admin-only listing.

### Later milestones
- ✅ M2 web dashboard (overview, analytics, transactions, edit and void, CSV export,
  Bangla and English).
- ✅ M3 Android MVP (Bangla-first entry, offline save, background sync, history,
  login; see `android.md`).
- ✅ M4 phone transfers, offline corrections and voids with conflict handling,
  change feed, private accounts, budgets, recurring entries, household switcher.
- ✅ M5 reminders and notifications, receipts, OCR suggestions, AI monthly
  summaries (background worker). Voice entry evaluated, not built.
- ✅ M6 (part): Docker Compose deployment, daily backups with a restore script,
  XLSX export, the end-to-end check.
- ✅ M6 (part): security review and fixes (`security.md`, S7 and S13–S16).
- ⏳ M6 (rest): APK signing, an accessibility review.

## Non-functional requirements

- The backend is authoritative for every financial figure. AI and clients never
  compute authoritative totals.
- The core works fully without the AI server.
- No secrets in Git, logs or audit records.
- Every endpoint is behind authentication, except health, login, refresh, logout
  and invitation acceptance.
- Real PostgreSQL integration tests. Tests never use production data.
