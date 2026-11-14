# 0001 — Money as integer poisha

Date: 2026-10-15 · Status: accepted

**Context.** Financial correctness is the top priority. Binary floating point
cannot represent 0.10 exactly, and JavaScript numbers are floats.

**Decision.** Store and transmit every amount as integer poisha (`BIGINT`). The
API accepts `amount_poisha` as a *strict* JSON integer (floats, including `120.0`,
are rejected) or `amount` as a decimal taka string parsed textually, with no
rounding and unambiguous grouping only. Transaction amounts are positive and at
most ৳1,000,000,000. The type carries the direction. Totals come from
PostgreSQL `SUM` (exact `NUMERIC`).

**Consequences.** Clients must format poisha using integer arithmetic. JSON
integers up to 2^53 are safe in JavaScript, and per-transaction amounts are far
below that. Very large aggregate totals (above about ৳90 trillion) would need
string encoding; this is not a realistic concern for a household.
