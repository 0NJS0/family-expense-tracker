# Security

The VPN is one layer, not the security model. Every request is still
authenticated and authorized.

## Authentication

- **Passwords**: Argon2id (`argon2-cffi` defaults, RFC 9106 profile), 8–256
  characters. Hashes are upgraded transparently on login when the parameters
  change. Unknown usernames still run a full hash, so response timing does not
  reveal which usernames exist. Wrong-password and unknown-user responses are
  identical.
- **Access token**: HS256 JWT, 15 minutes by default, with claims `sub`
  (user), `sid` (session), `iss`, `typ`, `iat` and `exp`. The algorithm is
  pinned (no `none`). On **every** request the session must exist, be unexpired
  and not revoked, and the user must be active. Logout and deactivation therefore
  take effect immediately, not after token expiry.
- **Refresh token**: 256-bit random opaque string. Only its SHA-256 hash is
  stored. It is valid for 60 days, capped by the session's absolute lifetime
  (365 days). **Rotation**: every refresh marks the presented token used and
  issues a new one.
  - **Reuse detection**: presenting an already-used token more than
    `FET_REFRESH_REUSE_GRACE_SECONDS` (60 s) after it was rotated revokes the
    whole session. Both the thief and the victim are logged out, and an
    `auth.refresh_reuse_detected` audit event is written.
  - **Grace window**: within 60 s, a repeated refresh is treated as a mobile
    retry after a lost response. The token issued by the first attempt is
    invalidated and a fresh one is issued, so an older user on a flaky
    connection is not logged out.
- **Logout** takes the refresh token (it works even when the access token has
  expired) and revokes that device's session only. Unknown tokens return `204`
  without error, so the endpoint cannot be used to test tokens.
- **Bootstrap**: `python -m app.cli bootstrap` creates the first admin. It holds a
  PostgreSQL advisory lock and refuses once any user exists. The password is read
  from a prompt or `FET_BOOTSTRAP_PASSWORD`, never from command-line arguments.
- **Invitations**: 256-bit single-use tokens, stored hashed, valid for 7 days,
  created by admins only.

## Authorization

- Every household-scoped query filters by the caller's active household. A
  household the caller does not belong to returns `404`, the same as one that does
  not exist.
- Roles: **viewer** (read only), **member** (create transactions; edit or void
  their own), **admin** (everything, including corrections to anyone's
  transactions, accounts, categories, invitations and the audit log).
- **Private accounts** (`app/services/visibility.py`): for members and viewers,
  someone else's private account and every transaction touching it are filtered
  out of every query (lists, reports, budgets, export, change feed, recurring
  rules) and are `404` when addressed directly, like another household's data.
  The feed reports only the client ids of entries that became invisible, never
  their content. Balances of visible accounts remain true by design (a shared
  account shows money that came from a private one, without the source entry).
- Defence in depth: composite foreign keys make cross-household references
  impossible even if a service check were missing (see `data-model.md`).

## Account lifecycle

- **Password change** (`POST /me/password`) requires the current password and
  is rate limited. It revokes every *other* session of the user, so a lost or
  stolen device is logged out, while the device making the change stays logged
  in. It is audited without any password material.
- **Deactivation** (`PATCH /members/{id}`, admin) is per household. The person
  loses access to that household on their next request, with their existing
  tokens, because the context lookup ignores deactivated memberships. They
  remain able to use other households they belong to, since an admin of one
  household must not be able to lock someone out of another. History stays
  attributed to them.
- An admin cannot remove the last active admin. Membership changes lock the
  household row, so two admins demoting each other concurrently cannot leave the
  household without one (tested).
- **Admin-initiated password reset** for a family member who forgets their
  password is *not* implemented yet. It needs care: an admin of one household
  must not gain control of an account that also belongs to another household.

## Rate limiting

In-process sliding windows per client IP:

| Endpoint | Limit |
|----------|-------|
| login | 10 per minute |
| refresh | 30 per minute |
| invitation accept | 10 per minute |
| password change | 10 per minute |

**Limitation:** state is per process. The deployment must run a single uvicorn
worker, or a shared store must be added. Behind the reverse proxy, uvicorn must
run with `--proxy-headers --forwarded-allow-ips=<proxy IP>`; otherwise every
client shares the proxy's IP.

## Input handling

- Pydantic models use `extra="forbid"` (unknown fields are rejected) and strict
  integers for money.
- Timezone-aware timestamps are required.
- Lengths are bounded everywhere.
- `LIKE` wildcards in search are escaped.
- Validation errors never echo submitted values back, so a password is never
  reflected.

## Data protection

- Android: the refresh token is encrypted with a non-exportable Android Keystore
  key (AES-256-GCM), and app data is excluded from cloud backup and device
  transfer (`docs/android.md`).

- CSV exports neutralise spreadsheet formula injection: text cells starting with
  `= + - @`, tab or CR are prefixed with `'`. Exports are sent with
  `Cache-Control: no-store` and audited as `transactions.exported` (who, row
  count, filters).

- **Receipts:** the type is decided from the file's bytes (JPEG, PNG, WebP,
  PDF; 10 MB), never from its name or header. Photos are decoded and
  re-encoded, which removes EXIF data such as the phone's location and model;
  a decompression-bomb limit applies. Files live in private storage
  (`FET_RECEIPTS_DIR`, mode 0700) under random names, are checked against their
  SHA-256 when read, and are only served through the authenticated API with
  `Cache-Control: private, no-store`, `nosniff` and a sandboxing CSP (a PDF
  cannot run scripts in the dashboard's origin). A receipt is visible exactly
  when its transaction is (private accounts included).
- **OCR text** is private: stored on the receipt, never logged (the worker logs
  only receipt ids) and never sent to the AI model.
- **AI:** the model receives computed figures and category names only, never
  notes; replies are shown as plain text and only when every number matches
  the figures (`ai-integration.md`). Summaries are admin-only, since they cover
  private accounts too.
- **Notifications** are local to the phone (no push service, nothing leaves
  the homelab); their content is only rule names, amounts and budget names.
- No secrets in Git (`.gitignore` covers `.env*`, keystores, dumps and receipts).
- The audit service refuses detail keys such as `password` and `token`.
  Transaction notes are not copied into audit records.
- Logs are JSON with method, path, status and duration only: no bodies, query
  strings or tokens.
- Production startup fails if `FET_JWT_SECRET` is missing, the development
  default, or shorter than 32 characters, or if CORS contains `*` or `http://`
  origins. API docs (`/docs`, `/openapi.json`) are disabled in production.
- CORS is off unless `FET_CORS_ORIGINS` is set. Credentials are not allowed
  (bearer tokens only, no cookies).

## Findings and open items

| # | Item | Status |
|---|------|--------|
| S1 | The llama.cpp server on the homelab accepts **unauthenticated** requests from the whole LAN. M5 now sends it household figures (no notes), so start it with `--api-key` (then set `FET_LLAMA_API_KEY`) and/or bind it to the Docker network. | open, more important now |
| S2 | PostgreSQL on the homelab (`:5432`) is reachable from the LAN. The deployment allows `fet_app` only from the app's Docker network (`docs/deployment.md`); tighten the other `pg_hba.conf` entries too. | open (owner) |
| S3 | Rotate the test role's (`fet_test`) password. It is a test-only role with no production access. | open |
| S4 | No TLS: owner decision (2026-10-17) to use plain HTTP on the home Wi-Fi and a VPN (which encrypts traffic outside the home). On the home Wi-Fi, passwords and amounts are not encrypted. The phone uses the debug build (release builds require HTTPS). | accepted by owner |
| S5 | In-memory rate limiter needs a single worker (see above). | accepted |
| S6 | Password change ✅ done. Admin-initiated reset for a forgotten password is still missing. | partly done |
| S7 | Dependency audits, not yet automated. Clean on 2026-10-15. On 2026-11-14, `pip-audit` over `backend/requirements.txt` found **Pillow 12.3.0** with 42 known vulnerabilities (fixed in 13.0.0; Pillow decodes uploaded receipt photos) and `opentelemetry-api` 1.45.1 with one (fixed in 1.45.2). `npm audit` found `undici` (via jsdom) and `zod` (via puppeteer-core), both test-only and not shipped. Fixed the same day: Pillow 13.0.0 and `opentelemetry-api` 1.45.2; `pip-audit` now finds nothing. The `npm audit` items remain (test tooling only). Not yet automated. | backend fixed |
| S8 | Dashboard refresh token is in `sessionStorage`, readable by any script that runs on the page (an XSS would expose it). Mitigations: strict input escaping (React, escaped chart tooltips), no third-party scripts, same-origin deployment, short tab lifetime, and a strict Content-Security-Policy from Caddy (`deploy/Caddyfile`: `script-src 'self'`, `object-src 'none'`, `frame-ancestors 'none'`). Still possible: moving refresh to an `HttpOnly` `SameSite=Strict` cookie. | mitigated |
| S9 | Android debug builds permit cleartext `http://`; release builds are https-only. The homelab serves plain HTTP (S4), so the phone runs the debug build. A release signing key is still open in M6. | accepted by owner |
| S10 | Receipt PDFs are stored as sent (their own metadata is not stripped); only photos are re-encoded. | accepted |
| S11 | Receipt files are not encrypted at rest; they rely on the homelab disk (and the daily backup folder's protection). | accepted |
| S12 | Deployment (`deploy/`): only the web port is published; containers run read-only with no new privileges and dropped capabilities (the backup keeps only `DAC_READ_SEARCH` to read receipts); secrets only in `deploy/.env` on the homelab; daily backups with checksums. | done |
| S13 | Backup files were readable by every account on the homelab. `deploy/backup/backup.sh` sets no `umask`, so the root-run backup writes `fet_prod.dump` (all transactions, notes, password hashes) and `receipts.tar.gz` as mode 0644 in 0755 folders. Fixed 2026-11-14: both scripts set `umask 077`, `deployment.md` makes the folder 0700, and `deploy/verify.sh` reads it inside the backup container; `test_backup_script.py` checks the modes. | fixed |
| S14 | Unexpected database errors could log transaction notes, password hashes and token hashes. `create_engine` in `backend/app/database.py` does not set `hide_parameters=True`, so SQLAlchemy error text includes the statement's values, and `errors.py` and `worker.py` log it. Fixed 2026-11-14: `build_engine` sets `hide_parameters=True` (`test_config.py`). | fixed |
| S15 | The backup role's password was visible in the process list (`ps`, `/proc/*/cmdline`) while a backup runs, because `backup.sh` and `restore.sh` pass the full database URL to `pg_dump` and `pg_restore`. Fixed 2026-11-14: the URL has no password; compose passes `BACKUP_DATABASE_PASSWORD` as `PGPASSWORD`, and both scripts refuse a URL that contains one (`test_backup_script.py`). | fixed |
| S16 | A void's free-text reason was copied into the audit details (`backend/app/services/transactions.py`, `void_transaction`), against the rule that audit records hold no note contents. Fixed 2026-11-14: the audit records `has_reason` only (`test_accounts_categories_audit.py`). | fixed |
