# Accounting rules

These rules are authoritative. Each rule is covered by automated tests in
`backend/tests/test_accounting.py` (and, where noted, other test files). If
code and this document disagree, that is a bug.

## Money

- All amounts are **integer poisha** (৳1 = 100 poisha), stored as PostgreSQL
  `BIGINT`. Binary floating point is never used for money.
- A transaction amount is **strictly positive** and at most
  **100,000,000,000 poisha** (৳1,000,000,000). The direction of money is given by
  the transaction type, never by a sign.
- The API accepts either `amount_poisha` (a JSON integer; floats, strings and
  booleans are rejected) or `amount` (a decimal taka string such as `"1,250.75"`
  or `"১২০.৫০"`), never both. Decimal strings are parsed textually
  (`app/money.py`):
  - at most two decimal places; extra places are **rejected, not rounded**;
  - commas are accepted only as valid Western (`1,250,000`) or South Asian
    (`12,50,000`) digit grouping, so `"12,50"` (European decimal comma) is rejected
    rather than misread as ৳1250;
  - Bangla digits are accepted.
- Totals are computed by PostgreSQL `SUM` (exact `NUMERIC`) and converted to Python
  `int` exactly. With the per-transaction ceiling, sums cannot overflow.
- Clients (dashboard, Android) must not compute authoritative totals. They display
  backend figures and must format poisha with integer arithmetic.

## Transaction types

For a transaction of amount **A**:

| Rule | Type | Effect |
|------|------|--------|
| R1 | income | selected account **+A** |
| R2 | expense | selected account **−A** |
| R3 | transfer | source account **−A**, destination account **+A** |

- **R4** A transfer is **not** income and **not** expense. A ৳500 transfer from
  cash to bank never appears as ৳500 of spending. Reports show transfer volume
  separately as `transfer_poisha` for information only.
- Income and expense require exactly one account and a category of the **same
  kind**. Enforced in the service and by a composite foreign key
  `(household_id, category_id, type) → categories(household_id, id, kind)`.
- A transfer requires a destination account different from the source, both in
  the same household, and **no category**.
- The type of a transaction cannot be changed. Void it and enter a new one.

## Balances

- **R5** Net cash flow = income − expenses (transfers excluded).
- **R6** Account balance = opening balance + inflows − outflows, where inflows are
  income into the account and transfers into it, and outflows are expenses from
  it and transfers out of it. Balances are **always derived**; there is no stored
  balance field anyone could edit.
- **R7** Voided transactions are excluded from balances, totals, breakdowns and
  time series. They stay in the database and are listed with
  `include_voided=true`.
- Balances are what the household **recorded**. They are not assumed to equal the
  real bank or wallet balance. Reconciliation is a future feature.
- Balances may go negative. The system does not block an expense on a
  "insufficient" recorded balance, because records may be incomplete; dashboards
  may highlight it.

## Opening balances

- An opening balance is the balance of an account **at the start of local day
  `as_of_date`** (household timezone). It is **not income** and never appears in
  income, expense or net figures.
- At most **one** per account (primary key on `account_id`); a second "set"
  returns `409`. A wrong opening balance is fixed with the **audited
  correction** (`POST /accounts/{id}/opening-balance/correct`): the admin sends
  the values they saw (a mismatch means someone changed it: `409`), the new
  values and a reason; the audit trail keeps before, after and reason. Moving
  the date later is subject to the backdating rule below. Tested in
  `tests/test_opening_correction.py`.
- Opening balances may be negative (an overdrawn account) within
  ±10,000,000,000,000 poisha.
- **Backdating rule:** because the opening balance already includes everything
  before `as_of_date`:
  - a transaction on an account dated **before** its opening date is rejected
    (`422`);
  - an opening balance whose date is **after** an existing non-voided transaction
    on that account is rejected (`409`). Choose a date on or before the earliest
    transaction.

  This prevents double counting. Concurrent "set opening balance" and "create
  backdated transaction" requests are serialised by row locks on the account.
- `GET /accounts/{id}/balance?as_of=D` returns the balance at the **end** of local
  day D. Before the opening date it is 0, since no transactions can exist then.

## Backdated transactions and historical reports

- Transactions may be entered or edited with any past occurrence time (since
  2000-01-01, and not before the account's opening date) and up to one day into
  the future (device-clock tolerance).
- Reports are always computed live from current data. Entering or correcting a
  past transaction **changes historical reports and balances** for that period.
  There is no "closed period" locking yet. The audit trail records every change
  (who, when, before and after values) so a changed historical figure can be
  explained.

## Attribution: household and individual reports

- Every transaction has a **creator** (who entered it) and an **attributed
  member** (whose income or spending it is; defaults to the creator). For
  example, a son can enter a medicine purchase attributed to his mother.
- **Household report**: every non-voided transaction in the household, counted
  exactly once.
- **Individual report** (`member_id=X`): transactions **attributed** to X. It
  ignores who entered the transaction and who owns the account.
- **R9 No double counting:** each transaction has exactly one attributed member,
  so individual reports partition the household report; the members' totals sum to
  the household total. A transaction on a shared account is counted once.
- Account ownership (`owner_user_id`, or `NULL` for shared) describes whose
  account it is. It does not change report attribution.

## Reporting periods

- All date parameters are **local calendar dates** in the household timezone
  (default `Asia/Dhaka`, UTC+6, no DST). Ranges are inclusive and translated to
  the half-open instant range `[from 00:00 local, (to + 1 day) 00:00 local)`.
  Example: 00:30 on 1 Feb in Dhaka (18:30 UTC on 31 Jan) belongs to **February**.
- **Weeks start on Saturday** by default (the first day on Bangladeshi
  calendars). This is configurable per household (`week_start`, Monday = 0 …
  Sunday = 6).
- Months and years are calendar months and years.
- Time series include **every** period in the range, with zero totals where
  there were no transactions.
- `is_partial` is `true` when a bucket is cut off by the requested range, or its
  period has not finished (it ends today or later). Summary `is_partial` is
  `true` when the range includes today or a future date. Clients must not
  present a partial period as complete.
- Default report range: the first of the current month to today.

## Previous-period comparison

Every summary includes a comparison with the previous period, computed with the
same rules (same member filter, transfers excluded, voided entries excluded):

- **Range starting on the 1st of a month** → the same days of the preceding
  month or months. 1–15 Oct is compared with 1–15 Sep. A full month is compared
  with the full previous month, whatever its length (all of March vs all of
  February). Days are clamped to the shorter month (1–30 Mar vs 1–28 Feb). A
  quarter is compared with the previous quarter.
- **Any other range** → a window of equal length immediately before it
  (5–11 Mar vs 26 Feb–4 Mar).
- **`compare_to=previous_year`** (explicit) → the same dates one year earlier
  (1 Jan–15 Oct 2026 vs 1 Jan–15 Oct 2025; 29 Feb becomes 28 Feb). The
  dashboard uses this for year-to-date. Comparing year-to-date with the
  preceding ten months would mix seasons, so it is never chosen automatically.
- Changes are exact integer differences in poisha. Relative changes are given in
  basis points (1/100 of a percent), computed with exact rational arithmetic
  and rounded half-to-even. When the previous value is 0, the relative change is
  `null`, never ∞ or a misleading 100%.
- `category_changes` covers every category used in **either** period, so a
  category that stopped being used shows as a drop to 0.

## Total of account balances

`GET /accounts/balances` returns `total_poisha`, the sum of the listed accounts'
recorded balances. Transfers between listed accounts cancel out. Excluding
archived accounts (the default) also excludes their balances from the total.

## Private accounts

- An account owned by a member can be **private**: only the owner and the
  household's admins see it. A transaction touching it (either side of a
  transfer) is hidden from everyone else in lists, reports, budgets, CSV export
  and the device change feed, and is "not found" when addressed directly.
- Other members' reports therefore exclude that money (owner decision,
  2026-10-16). Admins and the owner see the full figures.
- **Balances stay true:** a visible (e.g. shared) account's balance includes
  every transaction that touches it, even a transfer from someone's private
  account that the viewer cannot see.
- Tested in `tests/test_private_accounts.py`.

## Budgets

- A budget is monthly, for the whole household (all expenses) or for one
  expense category. Only **expenses** count: never transfers (R4) or income.
  Voided transactions do not count (R7).
- A budget change applies **from a month onwards**; past months keep the
  budget they had, and past months cannot be changed.
- `used_basis_points = spent × 10000 ÷ budget`, rounded down (integers only).
  State: `over` when spent > budget, `warning` when spent ≥ the warning level
  (default 80%), else `ok`. Reaching exactly 100% is not "over".
- **Alerts** are checked whenever spending in a month changes or a budget is
  set; each (scope, month, threshold) is recorded at most once (unique index),
  even if a void takes spending back below and it is crossed again. Alerts use
  the household's full spending; what a member *sees* in the budget status
  follows the private-account rule above.
- Tested in `tests/test_budgets.py`.

## Recurring entries

- A recurring rule (rent, salary, bills) **never moves money by itself**. Each
  due date becomes a pending occurrence (unique per rule and date, so
  generating twice is harmless). Money is recorded only when a member confirms
  it ("paid", optionally with the real amount and date) or when the admin
  explicitly enabled **auto-post** for the rule.
- The posted transaction's `client_id` is derived from the occurrence, so a
  repeated confirmation or a concurrent generation can never post twice.
- Dates are computed from the start date (a rule on the 31st falls on the last
  day of shorter months and returns to the 31st). A posted occurrence is dated
  12:00 local time on its day.
- Pausing stops new occurrences; resuming does not catch up on what fell due
  meanwhile. Skipped occurrences record nothing.
- Tested in `tests/test_recurring.py` and `tests/test_recurrence.py`.
