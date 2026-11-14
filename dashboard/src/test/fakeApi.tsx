/**
 * A fake backend for component tests. Only the network is replaced: the real
 * ApiClient, session logic, React Query and router run unchanged.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { ApiClient } from '../api/client'
import type { Household, Me, Page, Summary, Transaction } from '../api/types'
import { AppRoutes } from '../App'
import { SessionProvider } from '../auth/SessionContext'
import { I18nProvider } from '../i18n/I18nContext'
import type { Lang } from '../i18n/strings'

export interface Call {
  method: string
  path: string
  query: URLSearchParams
  headers: Record<string, string>
  body: unknown
}

type Handler = (call: Call) => Response | Promise<Response>

export function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  })
}

export function apiError(status: number, code: string, message = code, details: unknown[] = []) {
  return json({ error: { code, message, details } }, status)
}

export class FakeApi {
  calls: Call[] = []
  private routes = new Map<string, Handler>()

  on(method: string, path: string, handler: Handler | unknown): this {
    this.routes.set(
      `${method} ${path}`,
      typeof handler === 'function'
        ? (handler as Handler)
        : handler instanceof Response
          ? () => handler.clone()
          : () => json(handler),
    )
    return this
  }

  fetch: typeof fetch = async (input, init) => {
    const url = new URL(String(input), 'http://test')
    const path = url.pathname.replace(/^\/api\/v1/, '')
    const method = init?.method ?? 'GET'
    const call: Call = {
      method,
      path,
      query: url.searchParams,
      headers: (init?.headers ?? {}) as Record<string, string>,
      body: init?.body instanceof Blob ? init.body : init?.body ? JSON.parse(String(init.body)) : undefined,
    }
    this.calls.push(call)
    const handler = this.routes.get(`${method} ${path}`)
    if (!handler) return apiError(404, 'not_found', `no fake for ${method} ${path}`)
    return handler(call)
  }

  callsTo(method: string, path: string): Call[] {
    return this.calls.filter((c) => c.method === method && c.path === path)
  }
}

export const HOUSEHOLD: Household = {
  id: 'hh-1',
  name: 'Home',
  timezone: 'Asia/Dhaka',
  currency: 'BDT',
  week_start: 5,
  created_at: '2026-01-01T00:00:00Z',
}

export function me(role: 'admin' | 'member' | 'viewer' = 'admin'): Me {
  return {
    user: { id: 'u-admin', username: 'admin', display_name: 'Admin', preferred_language: 'en' },
    memberships: [{ household_id: HOUSEHOLD.id, household_name: HOUSEHOLD.name, role }],
  }
}

export const TOKENS = {
  token_type: 'bearer',
  access_token: 'access-1',
  access_token_expires_at: '2026-10-15T10:15:00Z',
  refresh_token: 'refresh-1',
  refresh_token_expires_at: '2026-12-15T10:00:00Z',
}

export function emptySummary(overrides: Partial<Summary> = {}): Summary {
  return {
    date_from: '2026-10-01',
    date_to: '2026-10-15',
    timezone: 'Asia/Dhaka',
    member_id: null,
    is_partial: true,
    income_poisha: 0,
    expense_poisha: 0,
    net_poisha: 0,
    transfer_poisha: 0,
    transaction_count: 0,
    expense_by_category: [],
    income_by_category: [],
    by_member: [],
    comparison: {
      mode: 'calendar_months',
      date_from: '2026-09-01',
      date_to: '2026-09-15',
      income_poisha: 0,
      expense_poisha: 0,
      net_poisha: 0,
      transfer_poisha: 0,
      transaction_count: 0,
      income_change_poisha: 0,
      expense_change_poisha: 0,
      net_change_poisha: 0,
      income_change_basis_points: null,
      expense_change_basis_points: null,
      category_changes: [],
    },
    ...overrides,
  }
}

export function tx(overrides: Partial<Transaction> = {}): Transaction {
  return {
    id: 'tx-1',
    client_id: 'c-1',
    type: 'expense',
    amount_poisha: 12050,
    category_id: 'cat-groceries',
    account_id: 'acc-cash',
    destination_account_id: null,
    occurred_at: '2026-10-05T06:00:00Z',
    note: 'rice',
    created_by_user_id: 'u-admin',
    attributed_user_id: 'u-admin',
    version: 1,
    created_at: '2026-10-05T06:01:00Z',
    updated_at: '2026-10-05T06:01:00Z',
    voided_at: null,
    voided_by_user_id: null,
    void_reason: null,
    ...overrides,
  }
}

export function page<T>(items: T[], total = items.length, offset = 0): Page<T> {
  return { items, total, limit: 25, offset }
}

/** A signed-in household with reference data and empty reports. */
export function standardApi(role: 'admin' | 'member' | 'viewer' = 'admin'): FakeApi {
  return new FakeApi()
    .on('POST', '/auth/refresh', TOKENS)
    .on('POST', '/auth/login', TOKENS)
    .on('POST', '/auth/logout', () => new Response(null, { status: 204 }))
    .on('GET', '/me', me(role))
    .on('GET', '/households/current', HOUSEHOLD)
    .on('GET', '/accounts', [
      { id: 'acc-cash', name: 'Cash', kind: 'cash', institution: null, owner_user_id: null,
        is_shared: true, is_private: false, is_archived: false, opening_balance: null, created_at: '2026-01-01T00:00:00Z' },
      { id: 'acc-bank', name: 'Bank', kind: 'bank', institution: null, owner_user_id: null,
        is_shared: true, is_private: false, is_archived: false, opening_balance: null, created_at: '2026-01-01T00:00:00Z' },
    ])
    .on('GET', '/categories', [
      { id: 'cat-groceries', kind: 'expense', name_en: 'Groceries', name_bn: 'বাজার', is_default: true, is_archived: false },
      { id: 'cat-transport', kind: 'expense', name_en: 'Transport', name_bn: 'যাতায়াত', is_default: true, is_archived: false },
      { id: 'cat-salary', kind: 'income', name_en: 'Salary', name_bn: 'বেতন', is_default: true, is_archived: false },
    ])
    .on('GET', '/members', [
      { user_id: 'u-admin', username: 'admin', display_name: 'Admin', role: 'admin', is_active: true, joined_at: '2026-01-01T00:00:00Z' },
      { user_id: 'u-amma', username: 'amma', display_name: 'Amma', role: 'member', is_active: true, joined_at: '2026-01-01T00:00:00Z' },
    ])
    .on('GET', '/reports/summary', emptySummary())
    .on('GET', '/reports/timeseries', {
      granularity: 'day', date_from: '2026-10-01', date_to: '2026-10-15', timezone: 'Asia/Dhaka',
      week_start: 5, member_id: null, buckets: [],
    })
    .on('GET', '/accounts/balances', { as_of_date: null, total_poisha: 0, accounts: [] })
    .on('GET', '/transactions', page([]))
}

export function renderApp(
  api: FakeApi,
  { path = '/', signedIn = true, lang = 'en' as Lang } = {},
) {
  const storage = window.sessionStorage
  if (signedIn) storage.setItem('fet.refresh', 'stored-refresh')
  const client = new ApiClient('/api/v1', storage, api.fetch)
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <I18nProvider lang={lang}>
        <SessionProvider client={client}>
          <MemoryRouter initialEntries={[path]}>
            <AppRoutes />
          </MemoryRouter>
        </SessionProvider>
      </I18nProvider>
    </QueryClientProvider>,
  )
  return { ...utils, client, queryClient }
}
