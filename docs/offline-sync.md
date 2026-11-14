# Offline synchronisation

Status: the server-side contract (M1), the Android outbox for new entries (M3), and
the **outbox for corrections and voids, conflict resolution on the phone and the
change feed (M4)** are implemented and tested.

## Server guarantees (implemented and tested)

1. **Stable client IDs.** Every transaction carries a `client_id` UUID that the
   device generates **when the entry is saved locally**, before any network
   attempt. The device reuses it on every retry.
2. **Idempotent create.** `POST /transactions` with a `client_id` already seen in
   the household returns the existing transaction with **`200`**. A first create
   returns `201`. Concurrent duplicates are resolved by a unique constraint, so
   exactly one row is created (tested with 8 parallel submissions).
3. **Reuse detection.** The same `client_id` with *different* content returns
   `409`. The server compares a SHA-256 fingerprint of the canonical payload; the
   same instant in a different UTC offset counts as the same payload.
4. **Server identity is separate.** `id`, `created_at` and `updated_at` come from
   the server. `occurred_at` is the user's claim of when the transaction happened.
5. **Optimistic concurrency.** Edits and voids must send the `version` the device
   last saw. A mismatch returns `409` with `current_version` and changes nothing.
6. **Nothing is deleted.** Voids keep the row, so a void can always be reconciled.

## Implemented client behaviour (M3, Android)

- **The outbox is the Room `transactions` table.** Rows are `PENDING` until the
  server confirms them. There is no in-flight state to get stuck in: if the
  app dies mid-upload, the row is still `PENDING` and the retry is idempotent.
- **States:** `PENDING` (retried automatically), `SYNCED`, `FAILED` (the server
  rejected it with 400/403/404/422; shown with the reason, not retried),
  `CONFLICT` (409: same client id, different data; shown, never overwritten).
- **Transient errors** (offline, timeouts, 5xx, 408, 429) stop the current run.
  WorkManager retries with exponential backoff from 30 s, at most 8 attempts per
  chain; a 6-hourly periodic job, every save and every app start trigger new
  attempts. Nothing is ever dropped.
- **After uploading**, the phone refreshes accounts, categories, members, the
  latest 100 transactions and this month's summary. A server row whose
  `client_id` matches a local `PENDING` row marks it `SYNCED`; this resolves the
  "server saved it but the reply was lost" case even when no re-upload happens.
- **Session expiry** never deletes entries. Only the signed-in person's own
  entries are uploaded, and another person cannot sign in while someone else's
  entries are unsent.
- **Logout** is disabled while anything is unsent (`PENDING`, `FAILED` or
  `CONFLICT`).
- **Timestamps:** "today" uses the current time in the household timezone; an
  earlier day uses 12:00 local time, so it cannot slip into a neighbouring day.
  The server's `created_at` stays separate.

Tests: `SyncEngineTest` (JVM, fake server with real idempotency semantics),
`TransactionDaoTest` (real Room SQL on a device) and `EntryFlowTest` (whole app
on a device: offline save, then exactly one server entry after reconnection).

## Corrections and voids on the phone (M4)

- **A second outbox**, Room table `transaction_changes` (database version 2,
  added by an automatic migration that keeps every existing row; tested by
  `MigrationTest`). At most one waiting change per entry: a second correction
  is merged into it, and a void replaces a waiting correction.
- The transaction row keeps the **server's** data; screens show the waiting
  change on top of it. A change stores only the fields that differ, plus the
  **server version the person saw** (`1` for an entry not yet uploaded: it gets
  version 1 when created). Correcting back to the server's values cancels it.
- **Order:** new entries are uploaded first, then changes, so a correction of
  an entry made offline is sent right after its creation, in the same run.
- **Lost reply:** if a correction reached the server but the reply was lost,
  the retry gets `409` (the version moved on). The phone then fetches the
  server's copy; if it already shows the change, the change is done. Only a
  real difference becomes a conflict.
- **Conflict (409):** the phone stores the server's current copy as the row,
  keeps the person's change beside it (state `CONFLICT`) and shows both. The
  person chooses **"apply my correction again"** (re-sent on top of the
  server's new version) or **"keep the server's"** (the change is dropped). A
  void made elsewhere wins: re-applying is not offered.
- **Rejected (400/403/404/422):** the change is marked `FAILED` with the
  server's reason; the person corrects it again or drops it. Not retried
  automatically.
- **Entries the server never accepted (⚠):** corrected in place and queued
  again (safe: the server never stored them), or dropped by the person
  (`DISCARDED`: kept on the phone, hidden, never uploaded). An entry whose
  client id the server holds with different data can be **sent again as a new
  entry**.
- Waiting changes count as unsent: they block logout and household switching.
- Members change their own entries, admins any; the server checks again.

## Change feed (M4)

- `GET /sync/changes?cursor=` returns every transaction of the household
  changed after the cursor (edits and voids included), oldest change first,
  in pages. The phone saves the cursor after each merged page, so an
  interrupted pull resumes. It replaces the earlier "latest 100" refresh.
- `updated_at` is the database transaction's start time, and transactions can
  commit out of order. Rows older than five minutes are settled, so the cursor
  given at the end of a feed is never later than "now − 5 minutes": the next
  sync may re-send the last few minutes of changes, and the merge (never an
  older `version` over a newer one) makes that harmless.
- **Private accounts:** changed entries the person may no longer see come back
  only as client ids in `removed`; the phone also drops server copies on
  accounts that disappeared from its account list. Neither ever deletes an
  unsent entry or one with a waiting change. If an account becomes visible
  again, the phone pulls the feed from the start.
- A different person or household on the phone restarts the feed.

## Receipts (M5)

- Room v3 adds `receipt_uploads` (automatic migration 2 → 3, tested). A photo
  is saved privately and queued; after new entries and changes, the sync
  uploads receipts whose entry is on the server (`SYNCED`). Sending the same
  bytes again returns the same receipt, so a lost reply is harmless. On
  success the local copy is deleted; on 4xx/409 it is kept and marked ⚠;
  network or 5xx errors retry later. Waiting receipts count as unsent.

## Not yet done

- Exporting unsent entries from the phone (logout stays blocked instead).
- A per-change "sync status" screen beyond the history list and banners.
