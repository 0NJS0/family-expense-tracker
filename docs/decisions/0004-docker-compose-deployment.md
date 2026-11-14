# 0004 — Deploy with Docker Compose on the homelab

Date: 2026-10-15 · Status: accepted (owner's choice)

**Context.** The specification suggested systemd. The owner already runs Docker
and PostgreSQL on the homelab and chose Docker Compose.

**Decision.** Milestone 6 will ship a `compose.yaml` with the backend (single
uvicorn worker, non-root user, read-only filesystem where possible), Caddy (TLS,
dashboard static files) and a migration job. The database is either the existing
host PostgreSQL, through a dedicated least-privilege role, or a container; this is
to be decided at deployment. Neither PostgreSQL nor llama.cpp will be published
beyond the private network.

**Consequences.** Health checks (`/api/v1/health`, `/api/v1/ready`) are used as
container health checks. Backups must cover the database and the receipts
volume.

**Update (2026-10-17).** Built in `deploy/` with two changes from the plan
above. The database is the existing host PostgreSQL 18, through the `fet_app`
role (`deploy/setup-database.sql`). Caddy serves plain HTTP without TLS, by the
owner's decision (`security.md`, S4). The stack also runs the background worker
(ADR 0005) and a daily backup service.
