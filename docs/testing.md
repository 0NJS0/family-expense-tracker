# Testing

## Backend

Tests run against **real PostgreSQL**, never SQLite and never production data.

### Setup (once)

On the database server, as a superuser:

```sql
CREATE ROLE fet_test LOGIN CREATEDB PASSWORD '<strong password>';
```

Then, on the development machine:

```bash
cp backend/.env.test.example backend/.env.test   # gitignored
# edit TEST_DATABASE_URL; percent-encode special characters in the password (@ → %40)
chmod 600 backend/.env.test
```

`TEST_DATABASE_URL` may also be set as an environment variable, which CI would
use.

### Run

```bash
cd backend
uv run pytest            # full suite
uv run pytest -k accounting -q
uv run ruff check app tests alembic && uv run ruff format --check app tests alembic
uv run mypy app          # strict
```

### How isolation works

- Each test session creates a fresh database `fet_test_<random>` and migrates it
  with **Alembic**, so the migrations themselves are exercised on every run. The
  database is dropped (`WITH (FORCE)`) at the end. `tests/pgutil.py` refuses to
  drop any database not named `fet_test_*`.
- After each test, every table is truncated.
- `test_migrations.py` additionally upgrades, fully downgrades and re-upgrades a
  second temporary database. It also asserts that the ORM models and migrations
  have **no diff**.

### Coverage map

| Area | File |
|------|------|
| Money parsing (exact, rejects ambiguous input) | `test_money.py` |
| Production config validation, missing secrets, docs hidden | `test_config.py` |
| Migrations, model/migration drift, append-only audit trigger | `test_migrations.py` |
| Login, rotation, reuse detection, logout, revocation, rate limit, token tampering, no plaintext secrets | `test_auth.py` |
| Households, default categories, invitations, bootstrap (service and CLI) | `test_households.py` |
| Roles (viewer, member, admin) and household isolation | `test_authorization.py` |
| Amount validation, transaction shapes, idempotency, concurrent duplicate submissions, concurrent edits, versioning, voids, filters and pagination, DB constraint backstops | `test_transactions.py` |
| Accounting rules R1–R10, opening balances, backdating, timezone and week boundaries, partial periods | `test_accounting.py` |
| Accounts, categories, audit trail contents | `test_accounts_categories_audit.py` |
| All-account balances, previous-period rules, exact basis points, category changes | `test_balances_and_comparison.py` |
| CSV export: contents, Dhaka times, quoting, formula injection, filters, audit, isolation | `test_export.py` |
| Role changes, last-admin protection (including concurrent mutual demotion), deactivation, password change | `test_members.py` |
| Change feed: paging in change order, edits and voids reach devices, late commits not missed (settled boundary), recent rows re-sent then settle, household scope, roles, bad cursor | `test_sync_feed.py` |
| Private accounts: invisible to other members in accounts, balances, transactions, reports, timeseries, CSV and the feed; 404 when addressed; cannot be used or changed by others; shared balances stay true; made private later → `removed` in the feed; sharing again clears private; only admins change privacy | `test_private_accounts.py` |
| Budgets: exact actuals (expenses only, voids out), states at the exact thresholds, from-month timeline (past never changes), alerts once per threshold (void and re-cross), lowering a budget alerts at once, a correction into another month, validation, roles, private spending | `test_budgets.py` |
| Recurring: idempotent generation (also 6 concurrent generators), nothing posted until confirmed, exact confirmed amount and midday date, double confirm, skip, auto-post once, auto-post that cannot post stays pending, pause/resume, end date and future start, validation and versions, roles, private accounts, month-end dates | `test_recurring.py`, `test_recurrence.py` |
| Reminder choices: gentle defaults, per person, validation | `test_notifications.py` |
| Receipts: metadata (location, camera) removed, type from the bytes (HTML and fake JPEG refused), size limit, same file twice is one receipt, who may add/see/remove, private accounts, other households, voided entries, tampered files detected | `test_receipts.py` |
| OCR parsing: totals only from total lines (sub total ignored), Bangla digits, lakh grouping, ambiguous commas refused, day-first dates, no future dates | `test_ocr.py` |
| Worker: AI numbers check (Bangla digits, rounding, invented arithmetic, numbers hidden in names), notes never in the prompt, injection in a category name, rejected summaries hidden, outages retried then failed, one claim per worker and stale claims, scheduling, roles; real Tesseract on a generated receipt; PDFs and missing Tesseract | `test_worker.py` |
| Opening-balance correction: exact balance change, still not income, audit before/after/reason, stale values refused, date rules, roles | `test_opening_correction.py` |

### Mutation spot-check (2026-10-15)

Deliberately breaking the code confirmed that the suite catches:

- transfers counted as expense;
- voided transactions included in balances;
- a missing `version` check.

A second round (same day) confirmed that the suite catches: removing the
household lock (concurrent mutual demotion), removing the last-admin check,
removing formula escaping, deactivated members keeping access, dropping transfer
inflows from the batch balance query, and not revoking other sessions on a
password change.

Milestone 4 (2026-10-16): switching the private-account rule off fails four
private-account tests. Removing the rule lock in recurring generation did
**not** fail a test: the unique `(rule, due_date)` constraint and the
occurrence-derived `client_id` already prevent duplicates (the concurrency test
proves it), so the lock is defence in depth. Two real bugs were found by the new
tests and fixed: budget alerts read stale data on corrections (sessions do not
autoflush), and `DISTINCT ON` needed a literal (not a bound) nil UUID.

Removing the idempotency pre-lookup did **not** fail any test. Duplicates are
still prevented by the unique constraint and its IntegrityError fallback (also
tested under concurrency), so behaviour stays correct.

## Dashboard

```bash
cd dashboard
npm test                      # Vitest (jsdom, TZ=Asia/Dhaka)
npm run typecheck && npm run lint
```

| Area | File |
|------|------|
| Exact money formatting (BigInt, lakh grouping, Bangla digits), amount input mirroring the backend | `src/lib/money.test.ts` |
| Dhaka "today", presets, Saturday weeks, month and year boundaries, datetime-local round trip | `src/lib/dates.test.ts` |
| Chart mapping: values unchanged, zero-filled buckets, fixed colours, faded partial periods, escaped tooltips, exact "Other" fold | `src/charts/options.test.ts` |
| API client: auth and household headers, refresh and retry, shared refresh, expiry, token-response validation, network vs API errors, downloads | `src/api/client.test.ts` |
| Pages against a fake backend (real client, session and query code): login errors, loading, error with retry, empty, 403, Bangla, filters to query params, pagination reset, invalid range, CSV export, edit sends only changed fields with version, 409 reload rebuilds the form, void reason, viewer and voided read-only, comparison choice | `src/pages/pages.test.tsx` |
| Family & settings: members, accounts (exact signed opening balance, private only with an owner, private toggle, opening-balance correction sending the shown values), categories, invitation acceptance | `src/pages/admin.test.tsx` |
| Budgets: exact server figures and state in words, setting from the shown month with the typed amount, local refusal of bad amounts and warning levels, server explanations, past months read-only, members read-only | `src/pages/budgets.test.tsx` |
| Recurring: "Paid" with the typed amount or none, skip conflict explained, pause with version, new rule not auto-posted unless chosen, incomplete rule refused locally, viewers read-only | `src/pages/recurring.test.tsx` |

Mutation spot-check: removing the conflict remount, sending unchanged fields,
dropping the shared refresh, not resetting the page on filter change, and not
fading partial periods are each caught.

### End-to-end check (automated: `scripts/e2e.sh`)

One command, from the repository root:

```bash
scripts/e2e.sh                 # phone part skipped when no emulator is running
scripts/e2e.sh --require-phone # fails without the emulator
```

It creates a throwaway `fet_test_*` database, migrates it, creates an admin
with a random password and seeds dev-only data through the API
(`scripts/e2e_seed.py`). Then:

1. **Dashboard:** the production build (`vite preview`, proxying to the real
   backend) is driven in a headless Chromium-based browser (`dashboard/e2e/browser-check.mjs`,
   `puppeteer-core` 25.12.0): sign in, every page in Bangla and English, the
   session surviving a reload, and no sideways scrolling at 390 px. Any console
   error, failed request or HTTP error fails the check.
2. **Phone:** `RealBackendTest` on the emulator (`ANDROID_SERIAL=emulator-5554`
   only) types "১,২৫০.৭৫", saves and syncs; the script then checks the server
   holds exactly one entry of 125075 poisha. A skipped test counts as a failure.

The database, processes and files are removed at the end, also on failure.
First run (2026-10-17): passed in full.

## Android

```bash
cd android && export ANDROID_HOME=$HOME/Android/Sdk
./gradlew testDebugUnitTest            # 100 JVM tests
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest
                                       # 38 device tests (+1 skipped real-backend test)
```

| Area | File |
|------|------|
| Money parsing and formatting identical to the backend (Bangla digits, grouping, rejects "12,50") | `test/.../money/TakaTest.kt` |
| Entry validation | `test/.../ui/EntryFormTest.kt` |
| Sync engine: upload order, batching, offline, lost response (no duplicate), 5xx stop, 422 → FAILED and continue, 409 → CONFLICT, expiry keeps data, only own entries, pull reconciliation | `test/.../sync/SyncEngineTest.kt` |
| Session against MockWebServer: login, wrong password, other-user guard, 429 and unreachable, refresh and retry, single refresh for parallel calls, rejected refresh, network failure keeps the token, https-only release URLs | `test/.../data/SessionTest.kt` |
| Local save: PENDING first, timezone timestamps, midday for past days, unique client ids; transfers with both accounts and no category, malformed transfers refused | `test/.../data/EntryRepositoryTest.kt` |
| Corrections and voids: only changed fields and the version seen, merging, changing back cancels, new day at midday, entries not yet sent, rejected entries fixed in place, void replaces a correction, own entries only (viewers none), re-apply after conflict, a void elsewhere wins, resend as new, logout blocked | `test/.../data/ChangeRepositoryTest.kt` |
| Change outbox in the sync engine: exact PATCH body, create-then-correct in one run, **lost reply not a conflict**, someone else's edit never overwritten then re-applied, void, edit after a void elsewhere, rejected changes not retried, offline, folding into rejected entries, only own changes; change feed (edits and voids from elsewhere, resume from cursor, paging, never an older version); private accounts (copies dropped, unsent and corrected kept, `removed` list, feed restart); budgets and due items cached exactly, older server tolerated | `test/.../sync/SyncEngineTest.kt` |
| Reminders and notifications: each item shown once, switched-off items not flooded later, admin-only alerts, one reminder a day, missing-entry only when nothing entered, household timezone | `test/.../notify/NotificationPlanTest.kt` |
| Receipts: kept privately and queued, camera file removed, waiting photo removable; uploaded after its entry with the exact bytes, local copy deleted, waits for its entry, refused photo kept, offline retried | `test/.../data/ReceiptRepositoryTest.kt`, `SyncEngineTest.kt` |
| Household switch: role and figures follow, feed restarts; refused while anything is unsent | `test/.../data/HouseholdSwitchTest.kt` |
| Admin API: edits send only changed fields plus the shown version, 409 surfaced, void sends version and reason, exact signed opening balance, member changes | `test/.../data/AdminApiTest.kt` |
| Manage area through the whole app: reports show server figures exactly, add account with `-500` → `-50000` poisha, a correction sends exactly `{"version":1,"amount_poisha":15000}`, members never see Manage | `androidTest/.../ui/AdminFlowTest.kt` |
| Real Room SQL: pending selection, no overwrite on duplicate id, state transitions, exact integer sums, merge rules, voided hidden; waiting changes counted (not summed), one change per entry, complete and fold atomically, dropping hidden-account copies only when safe | `androidTest/.../data/TransactionDaoTest.kt` |
| Room migrations 1 → 2 and 2 → 3 keep every entry and waiting change; waiting receipts counted as unsent | `androidTest/.../data/MigrationTest.kt`, `TransactionDaoTest.kt` |
| Room migration 1 → 2 keeps every entry (Bangla note, attempts, versions) and adds the change outbox | `androidTest/.../data/MigrationTest.kt` |
| Keystore encryption: no plaintext, survives restart, corrupt data handled | `androidTest/.../data/KeystoreTokenStoreTest.kt` |
| Whole app (Compose UI, Room, WorkManager, OkHttp): Bangla by default, save and send, offline save then exactly one upload, invalid amount explained; transfer sent without category (source not offered as destination); balances; correcting sends exactly `{"version":1,"amount_poisha":15000}`; cancelling sends the reason and hides the entry; budget in words; a due rent recorded only after "yes" | `androidTest/.../ui/EntryFlowTest.kt` |
| Against the real backend (manual, throwaway database) | `androidTest/.../ui/RealBackendTest.kt` |

**Mutation spot-check:** treating 409 as temporary, a non-shared refresh, a
rejected refresh not ending the session, past days at midnight, and retrying
422 forever are each caught by the intended test.

**Found by device testing (fixed):** a crash on corrupted stored token data,
and the app opening in English on a fresh install. `setApplicationLocales` was
called in `Application.onCreate`, where AppCompat silently ignores it; it now
runs in `MainActivity.onCreate`.

**Milestone 4 mutation checks:** dropping a transfer's destination, treating
every 409 on a correction as a conflict, and silently overwriting on 409 are
each caught. Device tests found that a budget card above the buttons pushed
"Add expense" off-screen; it now sits below them.

**Milestone 5 on devices:** reminder choices saved to the server; a 4000×3000
photo shrunk to ≤ 2000 px and uploaded as a JPEG after its entry; the AI
summaries screen shows only checked summaries; a stress test signs out while
on each screen ten times.

**Known flaky crash (open):** twice in about ten full device runs, Compose
crashed while tearing down the activity at the end of a test
(`SlotWriter.moveSlotGapTo`, `ArrayIndexOutOfBoundsException`), never in a
single-test run. It came back once more in Milestone 5. Every screen now
avoids `return` after a composable call (a missing profile is handled with an
`if`), and a stress test signs out on each screen ten times; four full runs
since were clean. Putting the old pattern back on Home did not reproduce it in
three runs, so the cause is **not confirmed**. On 2026-10-17 Compose was
updated (BOM 2026.03.01 → 2026.09.00, runtime 1.10.6 → 1.12.1) and Navigation 3
1.0.1 → 1.2.0, which include later slot-table fixes; six full device runs since
were all clean. Together with the earlier four, that is ten clean runs in a
row. It is likely fixed, but without a reproduction this cannot be proven;
report any unexpected app closing.

**End-to-end (2026-10-16):** a throwaway database migrated 0001 → 0006 from
scratch, a real server, an HTTP smoke test of the new endpoints (budgets,
alerts, recurring confirm with a double tap, the feed, opening-balance
correction and its stale-value refusal; every figure checked by hand), then the
phone's real-backend test: "১,২৫০.৭৫" arrived as exactly 125075 poisha on নগদ.

**End-to-end (2026-10-15):** emulator (Android 16) → real backend on a
throwaway database. An amount typed as "১,২৫০.৭৫" arrived as exactly 125075
poisha with a +06:00 timestamp, the correct category and the correct creator.
A second run from a cleared app also passed, with no duplicate.

## Deployment

| Area | File |
|------|------|
| Daily backup script (with stub `pg_dump`/`pg_restore`/`date`): complete folder with checksums and readable dump, receipts included, monthly copy as hard links, pruning (daily and monthly), a failed or unreadable dump leaves nothing behind, next-run time (leading zeros, midnight) | `backend/tests/test_backup_script.py` |
| `backend/requirements.txt` (installed by the Docker image) matches `uv.lock` | `backend/tests/test_requirements_lock.py` |
| Excel export: exact amounts (the file holds `0.07`, not a float), Dhaka dates, Bangla, text that looks like a formula stays text, filters, private accounts, audit | `backend/tests/test_export_xlsx.py` |

Also checked without Docker (none on the development machine): the image's
exact install from `requirements.txt` in a clean environment, migrations on a
fresh database, the API in production mode with the health-check command, the
worker, and the dashboard build from a clean `npm ci`. The images, Caddy and
backups against the real PostgreSQL are first run on the homelab, checked by
`deploy/verify.sh` (`deployment.md`). The backup script was tested with bash
in POSIX mode; the container's busybox shell runs it for the first time there.
