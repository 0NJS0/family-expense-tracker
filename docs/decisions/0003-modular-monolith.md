# 0003 — Modular monolith, synchronous SQLAlchemy, no repository layer

Date: 2026-10-15 · Status: accepted

**Decision.** A single FastAPI process with routes, services and models. Routes
are thin. Services hold all rules, authorization and transaction boundaries, and
use SQLAlchemy 2 directly in synchronous mode (FastAPI runs sync endpoints in a
thread pool).

**Why.** For a single family, throughput is trivial. Synchronous code is simpler
to reason about for row locking and transactions. A repository layer over
SQLAlchemy would add indirection without testability gains, because tests use a
real PostgreSQL database. Microservices would add failure modes with no benefit.
The optional AI integration will live in `app/integrations/` behind a narrow
interface and may become a separate worker later if needed.
