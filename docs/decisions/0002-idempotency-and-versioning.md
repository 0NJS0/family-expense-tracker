# 0002 — Idempotent creation and optimistic concurrency

Date: 2026-10-15 · Status: accepted

**Context.** The Android app is offline-first. Retries after timeouts must never
duplicate money, and edits from two devices must never silently overwrite each
other.

**Decision.**
- Clients generate `client_id` (UUID) at local save time. The database enforces
  `UNIQUE (household_id, client_id)`. A replay returns `200` with the existing
  row. A different payload with the same id returns `409`, detected by a SHA-256
  fingerprint of the canonical, normalised payload.
- Every transaction has a `version`. `PATCH` and `void` must quote it. The row is
  locked `FOR UPDATE`, and a mismatch returns `409`.
- Accounts involved in a write are locked `FOR SHARE`, so an opening balance
  (which takes `FOR UPDATE`) cannot be set concurrently with a conflicting
  backdated entry.

**Alternatives rejected.** An `Idempotency-Key` header with a separate response
cache would be more machinery for the same guarantee. Last-write-wins was
rejected because it can silently lose financial changes.
