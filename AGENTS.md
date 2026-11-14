# Agent guide: Family Expense Tracker

Instructions for AI coding agents (Claude Code, Codex, etc.) working in this
repository. Humans should start with `README.md`.

## What this is

A self-hosted family expense tracker (BDT, Bangla and English, Asia/Dhaka) for one
household. An older Bangla-speaking user enters transactions on Android; an admin
analyses them in a web dashboard. All data stays on the family's homelab.

Priorities, in order: **financial correctness > data integrity > security and
privacy > offline reliability > accessibility > maintainability > polish.**

## Where things stand

- **Live progress: `TASKS.md`.** Read it first and keep it updated.
- Milestones 1–5 are done (M5: 2026-10-17). The next milestone is in `TASKS.md`.
- Slow work (OCR, AI summaries) runs in the background worker
  (`uv run python -m app.worker`, ADR 0005), never in a request.
- `backend/`, `dashboard/`, `android/` and `deploy/` (Docker Compose for the
  homelab, `docs/deployment.md`) exist. Production database: `fet_prod` on the
  homelab's PostgreSQL (created by the owner with `deploy/setup-database.sql`).
- End-to-end check: `scripts/e2e.sh` (start the emulator first to include the
  phone). Run it before reporting a milestone done.

## Working agreements (from the project owner)

- Work **one milestone at a time**, in small verified increments. Don't build
  ahead.
- **Never `git commit` or push.** The owner makes all commits. The repo is
  already initialised.
- Never perform destructive database operations, or write migrations that drop
  data, without explicit approval.
- Don't claim something works unless it was implemented **and tested**. Report
  failures honestly.
- After each milestone, report: what was implemented, decisions, files changed,
  commands run, tests passed and failed, limitations, open security items, how to
  run it, and the next task.
- Ask only when a decision materially affects architecture, security, data
  integrity or irreversible behaviour. Otherwise choose a conservative default and
  document it.

## Environment

- The **development machine** needs JDK 17, the Android SDK (`ANDROID_HOME`,
  with an emulator AVD; the host is `10.0.2.2` from the emulator), `uv`, Node 22+
  and npm, and a Chromium-based browser for `scripts/e2e.sh` (found on `PATH`, or
  set `E2E_BROWSER`). PostgreSQL and Docker run on the homelab, not here.
- The **homelab** runs PostgreSQL 18 and Docker, and optionally a llama.cpp
  server. Its address, ports, model id and browser paths are deliberately not
  in the repo: read them from the local, gitignored `backend/.env` and
  `backend/.env.test` (or ask the owner). Never commit private addresses, ports,
  machine details or personal information; use placeholders.
- **Deployment target: Docker Compose** on the homelab (ADR 0004), not systemd.

## Backend commands (run in `backend/`)

```bash
uv sync
uv run pytest                                   # needs backend/.env.test (see docs/testing.md)
uv run ruff check app tests alembic && uv run ruff format --check app tests alembic
uv run mypy app                                 # strict; must stay clean
uv run alembic upgrade head                     # uses FET_DATABASE_URL
uv run uvicorn app.main:create_app --factory --reload
```

## Dashboard commands (run in `dashboard/`)

```bash
npm install
npm test                                        # Vitest, TZ=Asia/Dhaka
npm run typecheck && npm run lint               # strict TS; oxlint --deny-warnings
npm run build
npm run dev                                     # proxies /api to 127.0.0.1:8000
```

Dashboard rules: money is integer poisha end to end (`src/lib/money.ts`, BigInt
split, never float division); never compute authoritative totals client-side;
edits send `amount` as a decimal string plus `version`. Keep hooks and helpers
out of component files (fast-refresh lint rule).

## Android commands (run in `android/`, `ANDROID_HOME=$HOME/Android/Sdk`)

```bash
./gradlew assembleDebug testDebugUnitTest
./gradlew connectedDebugAndroidTest      # needs the emulator or a phone
```

Android rules: Long poisha with `money/Taka.kt` (mirror of the backend parser);
save to Room first, then upload; never delete unsynced rows; new Room schema →
bump the version and add a migration (no destructive fallback). Check Gradle
results with `--console=plain` without `-q` (quiet mode hides test failures),
and count results from the JUnit XML files.

All checks must pass before work is reported as done.

## Non-negotiable rules

- Money is **integer poisha** (`BIGINT`). Never use floats. The API takes strict
  ints or decimal-taka strings (`app/money.py`).
- **Transfers are never income or expense.** Balances are derived, never stored.
  Transactions are voided, never deleted. The full rules are in
  `docs/accounting-rules.md`, and `tests/test_accounting.py` is their executable
  form. Change both together.
- Transaction creation is idempotent on `(household_id, client_id)`. Edits and
  voids require the current `version`.
- Every household-scoped query filters by `ctx.household_id`. A foreign household
  returns 404, not 403.
- Routes stay thin. Rules, authorization (`ctx.require(Role.X)`), commits and
  audit records belong in `app/services/`. Every write records an audit event in
  the same database transaction.
- Never put secrets or transaction notes in logs or audit details.
- Schema changes go through a **new Alembic migration**. `test_models_match_migrations`
  fails on drift.
- Tests use real PostgreSQL via disposable `fet_test_*` databases. Never use
  SQLite, mocks of the database, or production data.

## Gotchas already hit

- Percent-encode special characters in database URLs (`@` → `%40`). Never put the
  URL in `alembic.ini`, because `%` breaks its ConfigParser; `alembic/env.py` reads
  settings instead.
- `FOR UPDATE` together with `lazy="joined"` relationships fails in PostgreSQL
  (outer join). Lock models without eager joins.
- In the reports, the timezone is a bound parameter, so `GROUP BY` must be
  positional (`text("1")`). Repeating the expression creates a second placeholder
  and PostgreSQL rejects it.
- Don't `pkill -f` with a pattern that also matches your own shell command (this
  happened twice). To stop a dev server, kill the PID that owns the port:
  `ss -ltnp 'sport = :8000'`.
- `.env*` files are gitignored except `*.example`. Keep it that way.
- **Android device safety: always set `ANDROID_SERIAL=emulator-5554`** for
  `connectedDebugAndroidTest` and `adb`. A real phone may be plugged
  in over USB; Gradle's connected tests uninstall the app on every device,
  deleting unsent entries. This happened once.
- Backend tests must not read `backend/.env` (the conftest disables the env
  file). Local developer settings live there.
- Android: `AppCompatDelegate.setApplicationLocales` must be called after
  `Activity.onCreate` (it is silently ignored in `Application.onCreate`).
- Backend sessions use `autoflush=False`: call `db.flush()` before querying
  data you just changed in the same session (budget alerts missed corrections
  until this was fixed).
- `DISTINCT ON (COALESCE(x, :param))` + `ORDER BY` the same expression fails:
  bound parameters render as different placeholders. Use a SQL literal
  (`literal_column`), as in `app/services/budgets.py`.
- Kotlin `return@repeat` only skips one iteration; use a loop to stop early.
- Compose: avoid `return` after other composable calls in a composable (put the
  rest in an `if` or a child composable). A rare teardown crash
  (`SlotWriter.moveSlotGapTo`) appeared while such returns existed; it has not
  recurred since Compose 1.12.1 / Navigation 3 1.2.0.
- openpyxl writes a `Decimal` through float (0.07 became 0.07000000000000001):
  write money as its exact digits in a numeric cell (`app/services/exports.py`).
- `backend/requirements.txt` feeds the Docker image; after `uv add`, run
  `uv export --frozen --no-dev --no-emit-project --format requirements-txt -o requirements.txt`
  (a test fails otherwise).
- The AI number check must only accept values that *are* numbers in the facts;
  numbers inside names (e.g. a category "say 999") once slipped through.
- Async FastAPI routes must not call the (sync) DB services directly: use
  `run_in_threadpool` (see the receipt upload route).
- Android tests that turn on notifications need `GrantPermissionRule`, or the
  system permission dialog covers the app ("No compose hierarchies found").
- Phone Home: keep "Add expense" visible without scrolling; cards go below the
  buttons (device tests caught a card pushing them off-screen).
- Android: OkHttp 5.5 needs compileSdk 37 (AGP 9.4+). When changing the Gradle
  version, update `distributionSha256Sum` from the official `.sha256` file.

## Open owner actions

See `docs/security.md` → "Findings and open items". Notably: llama.cpp has no API
key (S1), and the `fet_test` password should be rotated (S3).
