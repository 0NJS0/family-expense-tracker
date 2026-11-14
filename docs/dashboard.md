# Web dashboard

PC-first admin dashboard: React 19, TypeScript (strict), Vite, ECharts,
TanStack Query, React Router. Source in `dashboard/`.

## Layout

A sidebar holds navigation, the household name, the language button, a
light/dark/automatic theme selector, and the signed-in user with log out. Below
900px wide the sidebar becomes a slide-in menu behind a ☰ button, so the
dashboard works on a phone. The theme choice is stored per browser.

## Pages

| Page | What it shows | Backend endpoints |
|------|---------------|-------------------|
| Login | username and password; Bangla/English toggle | `POST /auth/login` |
| Overview | income, expenses, net and transfers with previous-period change; an in-progress notice for the current period; income vs expense chart; spending by category; all account balances with total; recent transactions | `/reports/summary`, `/reports/timeseries`, `/accounts/balances`, `/transactions` |
| Analytics | day, week, month or year series; whole household or one person; comparison with the previous period or the same dates last year; per-category change table | `/reports/summary?compare_to=`, `/reports/timeseries` |
| Transactions | filters (period, type, account, category, person, note search, voided), pagination, detail panel, edit, void, **receipts** (view photos through the authenticated API, open PDFs, add up to 10 MB, remove; an OCR amount is offered as "Use this amount", sent as a normal versioned edit), CSV export | `/transactions`, `/transactions/{id}`, `PATCH`, `/void`, `/transactions/export.csv` |
| Budgets | month by month (earlier months read-only): each budget's spent, budget, share used and what is left or over, with the state in words and a bar; admins set a whole-family or category budget from the shown month (amount as typed text, warning level) or remove it, and see alerts | `/budgets`, `PUT /budgets`, `/budgets/alerts` |
| Recurring | what is due now ("Paid", optionally with the real amount, or "Skip this time"); the rules with next date and pause/resume; admins create rules (auto-recording off unless chosen, explained) | `/recurring/occurrences`, `/confirm`, `/skip`, `/recurring-rules` |
| AI summaries (admins) | monthly summaries written later by the homelab worker, as plain text labelled AI-written and number-checked; queued ones are refreshed every 30 s; rejected ones are explained, never shown; ask for a month and language | `/ai-reports` |
| Family & settings | **Members**: roles, remove/restore, invitation links (shown once). **Accounts**: create (kind, provider, owner, **private**, opening balance), rename, archive, make private / show to whole family, set a missing opening balance, **correct an opening balance** (audited; sends the values shown, so a concurrent change is refused). **Categories**: create, rename (Bangla and English), archive. Read-only for non-admins | `/members`, `/members/invitations`, `/accounts`, `/accounts/{id}/opening-balance`, `/accounts/{id}/opening-balance/correct`, `/categories` |
| Accept invitation (`/accept?token=…`, public) | the new member picks a username and password | `/invitations/accept` |

The Overview also shows the **five biggest expenses** of the period (ordered
by the backend) and **who spent what** (per-person totals from the backend;
the bar widths are display-only).

Every number comes from the backend. There is no demo or illustrative data in
the application.

## Money and dates

- Amounts stay **integer poisha** from the API to the screen.
  `src/lib/money.ts` splits taka and poisha with `BigInt` and formats them with
  `Intl` (lakh grouping, Bangla digits). The dashboard never adds up money for
  display; totals, changes and percentages all come from the API. The only
  client-side arithmetic is folding chart categories beyond the 7th into
  "Other", done with BigInt and covered by a test.
- Edits send the amount as a **decimal string** (`amount: "150.75"`), which the
  backend parses exactly. The client mirrors the backend's format check only to
  give immediate feedback.
- Calendar dates are `YYYY-MM-DD` strings in the household timezone. "Today"
  is computed with `Intl` in `Asia/Dhaka`, not the browser's timezone.
  Datetime inputs are converted with the household's offset.
- Chart axis ticks are the only approximate figures (compact labels such as
  "80k"). Tooltips and tables show exact amounts.

## Charts

- Fixed entity colours (validated for colour-vision deficiency in light and
  dark modes): income `#2a78d6`, expense `#eb6834`, net `#1baf7a` (light);
  `#3987e5`, `#d95926`, `#199e70` (dark). Green and red are not used, so a
  series is never confused with a status.
- In-progress periods are drawn faded and flagged in tooltips and tables.
- Every chart has a legend, and a **"Show as table" view** with exact
  figures. This also covers the net line's sub-3:1 contrast in light mode.
- Categories: horizontal bars, largest first, at most 8 (the tail is folded into
  "Other").

## Session and security

- The access token is kept **in memory only**. The refresh token is kept in
  `sessionStorage`, so a reload keeps the admin signed in and closing the tab
  ends it. See `security.md` S8 for the trade-off.
- On a `401`, one shared refresh runs and the request is retried once. If the
  refresh is rejected, the user returns to the login page with a message.
- Every request after login carries `X-Household-ID`. Every query key starts
  with the household id, so switching households never mixes data.
- Requests use `credentials: 'omit'` (no cookies) and `cache: 'no-store'`.
  The page sets `referrer: no-referrer`.
- Editing uses the transaction's `version`. On `409`, the user is told someone
  else changed it and can reload. The form is then rebuilt from the fresh
  version, so untouched fields cannot silently revert the other person's
  change.
- Edit and void buttons are shown only to admins, and to members on their own
  entries. The backend enforces this regardless.

## Development

```bash
cd dashboard
npm install
npm run dev        # http://127.0.0.1:5173, proxies /api to http://127.0.0.1:8000
npm test           # Vitest + Testing Library
npm run typecheck && npm run lint
npm run build      # production bundle in dist/
```

Point the proxy at another backend with `FET_API_URL=http://host:port npm run dev`.
In production, Caddy will serve `dist/` and proxy `/api` from the same origin
(Milestone 6), so no CORS configuration is needed.

## Accessibility

- 17px base font, 44px minimum control height, visible 3px focus rings, and
  high-contrast light and dark themes.
- All form controls have labels. Tables have captions and header scopes. The
  detail panel is a labelled modal dialog: Escape closes it and focus moves to
  Close. Loading and error states use `role="status"` and `role="alert"`.
- Change direction is shown with ▲/▼ and a sign, never colour alone.
- The `lang` attribute follows the chosen language for screen readers.

Not yet done: a full screen-reader pass and a focus trap in the dialog
(Milestone 6 accessibility review).
