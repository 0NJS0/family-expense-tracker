# Architecture

## Overview

A modular monolith. One FastAPI backend owns all business rules and is the
only component that talks to PostgreSQL.

```
 Android app (Kotlin/Compose,           PC browser
 Room + WorkManager outbox)             React + TS dashboard (ECharts)
            │  plain HTTP: home Wi-Fi, or a VPN       │
            └──────────────┬──────────────────────────┘
                           ▼
        web: Caddy — static dashboard, proxies /api, security headers
                           ▼
        api: FastAPI backend (Docker container, single uvicorn process)
        ├─ api/        thin HTTP routes, schemas, auth dependencies
        ├─ services/   business rules, authorization, audit
        └─ models/     SQLAlchemy 2 ORM
                           │
                           ▼
        PostgreSQL 18 (homelab host) ◀── worker: receipt OCR (Tesseract),
              │                          AI summaries ──▶ llama.cpp (local model,
              ▼                                           optional, LAN only)
        backup: daily pg_dump + receipts → folder on the homelab
```

Status: all of the above runs as one Docker Compose stack (`deploy/`, see
`deployment.md`). There is no TLS: the owner chose plain HTTP on the home Wi-Fi
and a VPN (`security.md`, S4). The worker is a separate
process so OCR and AI summaries never run inside a request (ADR 0005).

## Backend layering

| Layer | Responsibility | Rules |
|-------|----------------|-------|
| `app/api/routes` | HTTP shape: parse, call a service, serialise | no business logic |
| `app/api/deps.py` | Authentication, household context, rate limits | |
| `app/schemas` | Pydantic request and response models | strict ints for money; `extra="forbid"` |
| `app/services` | Validation, authorization (`ctx.require(role)`), DB transactions, audit | every write commits once, together with its audit record |
| `app/models` | Tables and constraints | the database is the last line of defence |
| `app/money.py`, `app/timeutil.py` | Exact money parsing and local-date arithmetic | |

There is no separate repository layer. The services use SQLAlchemy directly,
and an extra abstraction would add indirection without benefit at this size
(see ADR 0003).

## Request lifecycle

1. The bearer access token (JWT, 15 min) is decoded. Its session must exist, be
   unexpired and not revoked, and the user must be active. This makes logout and
   deactivation immediate.
2. The household context is resolved from the `X-Household-ID` header, or the
   user's only membership. If the user is not a member, the response is `404`
   (indistinguishable from "does not exist").
3. The service checks the role (`viewer < member < admin`) and validates input.
   It locks rows where needed (`FOR UPDATE` on the edited transaction, `FOR SHARE`
   on the accounts involved), writes, records an audit event and commits.
4. Errors are rendered as `{"error": {"code", "message", "details"}}`.
   Unexpected errors return a generic 500. Stack traces are only logged
   server-side.

## Key design decisions

See `docs/decisions/`:

- 0001: Integer poisha, and either a strict integer or a decimal string at the API.
- 0002: Idempotent creation via client UUID plus a payload fingerprint, and
  optimistic concurrency via `version`.
- 0003: Modular monolith, synchronous SQLAlchemy, no repository layer.
- 0004: Deployment with Docker Compose on the homelab.
- 0005: A background worker for OCR and AI summaries.

## Runtime assumptions

- **Single worker process.** Rate limiting is in-memory. Running several workers
  needs a shared limiter (documented in `docs/security.md`).
- **Python 3.12** (`uv`-managed; `uv.lock` pins exact versions).
- **PostgreSQL 16+** (developed and tested against 18.6 on the homelab).
