import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import {
  apiError,
  emptySummary,
  json,
  page,
  renderApp,
  standardApi,
  tx,
} from '../test/fakeApi'

describe('login', () => {
  it('shows a clear message for wrong credentials and signs in on success', async () => {
    const user = userEvent.setup()
    let attempts = 0
    const api = standardApi().on('POST', '/auth/login', () =>
      ++attempts === 1 ? apiError(401, 'unauthorized') : json({
        token_type: 'bearer', access_token: 'a', access_token_expires_at: 'x',
        refresh_token: 'r', refresh_token_expires_at: 'y',
      }),
    )
    renderApp(api, { signedIn: false })

    await user.type(await screen.findByLabelText('Username'), 'admin')
    await user.type(screen.getByLabelText('Password'), 'wrong-password')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Wrong username or password.')
    expect(screen.getByLabelText('Password')).toHaveValue('')

    await user.type(screen.getByLabelText('Password'), 'right-password')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByRole('heading', { name: 'Overview' })).toBeInTheDocument()
    expect(api.callsTo('POST', '/auth/login')[1]?.body).toMatchObject({ username: 'admin' })
  })

  it('explains when the server is unreachable', async () => {
    const user = userEvent.setup()
    const api = standardApi().on('POST', '/auth/login', () => {
      throw new TypeError('offline')
    })
    renderApp(api, { signedIn: false })
    await user.type(await screen.findByLabelText('Username'), 'admin')
    await user.type(screen.getByLabelText('Password'), 'pw')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Cannot reach the server')
  })

  it('returns to the login page with a message when the session expires', async () => {
    const api = standardApi().on('POST', '/auth/refresh', apiError(401, 'unauthorized'))
    renderApp(api)
    expect(await screen.findByLabelText('Username')).toBeInTheDocument()
  })
})

describe('overview', () => {
  it('shows exact totals, the comparison and the partial-period notice', async () => {
    const api = standardApi()
      .on('GET', '/reports/summary', emptySummary({
        income_poisha: 5_000_000,
        expense_poisha: 1_234_567,
        net_poisha: 3_765_433,
        transfer_poisha: 50_000,
        transaction_count: 7,
        comparison: {
          ...emptySummary().comparison,
          transaction_count: 3,
          expense_change_poisha: 234_567,
          expense_change_basis_points: 2346,
        },
      }))
      .on('GET', '/accounts/balances', {
        as_of_date: null,
        total_poisha: 987_654,
        accounts: [{
          account_id: 'acc-cash', name: 'Cash', kind: 'cash', is_shared: true, is_archived: false,
          as_of_date: null, has_opening_balance: false, opening_balance_poisha: 0,
          inflow_poisha: 0, outflow_poisha: 0, balance_poisha: 987_654,
        }],
      })
    renderApp(api)

    expect(await screen.findByText('৳50,000.00')).toBeInTheDocument()
    expect(screen.getByText('৳12,345.67')).toBeInTheDocument()
    expect(screen.getByText('৳37,654.33')).toBeInTheDocument()
    expect(screen.getByText(/\+৳2,345.67 \(\+23.5%\)/)).toBeInTheDocument()
    expect(screen.getByRole('note')).toHaveTextContent('In progress')
    expect(await screen.findAllByText('৳9,876.54')).toHaveLength(2) // account row + total
  })

  it('shows empty states when nothing is recorded', async () => {
    renderApp(standardApi())
    expect(await screen.findByText('No accounts yet.')).toBeInTheDocument()
    expect(await screen.findAllByText('Nothing recorded for this period.')).not.toHaveLength(0)
  })

  it('shows an error with a retry button, and recovers', async () => {
    const user = userEvent.setup()
    let fail = true
    const api = standardApi().on('GET', '/accounts/balances', () =>
      fail ? apiError(500, 'internal_error') : json({ as_of_date: null, total_poisha: 0, accounts: [] }),
    )
    renderApp(api)
    const panel = (await screen.findByRole('heading', { name: 'Account balances' })).closest('section')!
    expect(await within(panel).findByRole('alert')).toHaveTextContent('Could not load data.')
    fail = false
    await user.click(within(panel).getByRole('button', { name: 'Try again' }))
    expect(await within(panel).findByText('No accounts yet.')).toBeInTheDocument()
  })

  it('reports missing permission instead of a generic failure', async () => {
    const api = standardApi().on('GET', '/accounts/balances', apiError(403, 'forbidden'))
    renderApp(api)
    expect(await screen.findByText('You do not have permission to do this.')).toBeInTheDocument()
  })

  it('renders in Bangla', async () => {
    const api = standardApi().on('GET', '/reports/summary', emptySummary({ expense_poisha: 12_050 }))
    renderApp(api, { lang: 'bn' })
    expect(await screen.findByRole('heading', { name: 'সারসংক্ষেপ' })).toBeInTheDocument()
    expect(await screen.findByText('৳১২০.৫০')).toBeInTheDocument()
  })
})

describe('period comparison', () => {
  it('compares this month with the previous period and this year with last year', async () => {
    const user = userEvent.setup()
    const api = standardApi()
    renderApp(api)
    await screen.findByRole('heading', { name: 'Overview' })
    await waitFor(() => expect(api.callsTo('GET', '/reports/summary')).not.toHaveLength(0))
    expect(api.callsTo('GET', '/reports/summary').at(-1)?.query.get('compare_to')).toBe('previous_period')
    await user.selectOptions(screen.getByLabelText('Period'), 'this_year')
    await waitFor(() =>
      expect(api.callsTo('GET', '/reports/summary').at(-1)?.query.get('compare_to')).toBe('previous_year'),
    )
    expect(api.callsTo('GET', '/reports/summary').at(-1)?.query.get('date_from')).toMatch(/-01-01$/)
  })

  it('lets the analytics page choose the comparison', async () => {
    const user = userEvent.setup()
    const api = standardApi()
    renderApp(api, { path: '/analytics' })
    const select = await screen.findByLabelText('Compare with')
    await waitFor(() =>
      expect(api.callsTo('GET', '/reports/summary').at(-1)?.query.get('compare_to')).toBe('previous_year'),
    )
    await user.selectOptions(select, 'previous_period')
    await waitFor(() =>
      expect(api.callsTo('GET', '/reports/summary').at(-1)?.query.get('compare_to')).toBe('previous_period'),
    )
  })
})

describe('transactions', () => {
  it('lists transactions and sends filters to the API', async () => {
    const user = userEvent.setup()
    const api = standardApi().on('GET', '/transactions', page([tx()]))
    renderApp(api, { path: '/transactions' })

    const table = await screen.findByRole('table', { name: 'Transactions' })
    expect(within(table).getByText('Groceries')).toBeInTheDocument()
    expect(within(table).getByText('Expense')).toBeInTheDocument()
    expect(within(table).getByText('−৳120.50')).toBeInTheDocument()

    await user.selectOptions(screen.getByLabelText('Type'), 'expense')
    await user.selectOptions(screen.getByLabelText('Account'), 'acc-cash')
    await user.selectOptions(screen.getByLabelText('Category'), 'cat-groceries')
    await user.type(screen.getByLabelText('Search notes'), 'rice')
    await waitFor(() => {
      const last = api.callsTo('GET', '/transactions').at(-1)?.query
      expect(last?.get('q')).toBe('rice')
    })
    const query = api.callsTo('GET', '/transactions').at(-1)!.query
    expect(query.get('type')).toBe('expense')
    expect(query.get('account_id')).toBe('acc-cash')
    expect(query.get('category_id')).toBe('cat-groceries')
    expect(query.get('date_from')).toMatch(/^\d{4}-\d{2}-01$/)
    expect(query.get('include_voided')).toBeNull()
  })

  it('paginates and returns to page 1 when a filter changes', async () => {
    const user = userEvent.setup()
    const api = standardApi().on('GET', '/transactions', (call: { query: URLSearchParams }) =>
      json(page([tx({ id: `tx-${call.query.get('offset')}` })], 60, Number(call.query.get('offset')))),
    )
    renderApp(api, { path: '/transactions' })
    expect(await screen.findByText('Page 1 of 3')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Next' }))
    expect(await screen.findByText('Page 2 of 3')).toBeInTheDocument()
    expect(api.callsTo('GET', '/transactions').at(-1)?.query.get('offset')).toBe('25')

    await user.click(screen.getByLabelText('Show voided'))
    expect(await screen.findByText('Page 1 of 3')).toBeInTheDocument()
    const last = api.callsTo('GET', '/transactions').at(-1)?.query
    expect(last?.get('offset')).toBe('0')
    expect(last?.get('include_voided')).toBe('true')
  })

  it('rejects an invalid custom date range without querying', async () => {
    const user = userEvent.setup()
    renderApp(standardApi(), { path: '/transactions' })
    await user.selectOptions(await screen.findByLabelText('Period'), 'custom')
    const before = await screen.findByLabelText('From')
    await user.clear(before)
    await user.type(before, '2026-12-31')
    expect(await screen.findByText('The start date must be on or before the end date.')).toBeInTheDocument()
  })

  it('exports CSV with the current filters', async () => {
    const user = userEvent.setup()
    const createObjectURL = vi.fn(() => 'blob:csv')
    const revokeObjectURL = vi.fn()
    Object.assign(URL, { createObjectURL, revokeObjectURL })
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    const api = standardApi().on('GET', '/transactions/export.csv', () =>
      new Response('﻿x\r\n', { headers: { 'Content-Disposition': 'attachment; filename="t.csv"' } }),
    )
    renderApp(api, { path: '/transactions' })
    await user.selectOptions(await screen.findByLabelText('Type'), 'income')
    await user.click(screen.getByRole('button', { name: 'Export CSV' }))
    await waitFor(() => expect(click).toHaveBeenCalled())
    expect(api.callsTo('GET', '/transactions/export.csv')[0]?.query.get('type')).toBe('income')
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:csv')
    click.mockRestore()
  })

  it('exports Excel with the current filters', async () => {
    const user = userEvent.setup()
    Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:xlsx'), revokeObjectURL: vi.fn() })
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    const api = standardApi().on('GET', '/transactions/export.xlsx', () =>
      new Response(new Blob(['PK']), { headers: { 'Content-Disposition': 'attachment; filename="transactions-2026-10-16.xlsx"' } }),
    )
    renderApp(api, { path: '/transactions' })
    await user.selectOptions(await screen.findByLabelText('Type'), 'expense')
    await user.click(screen.getByRole('button', { name: 'Export Excel' }))
    await waitFor(() => expect(click).toHaveBeenCalled())
    expect(api.callsTo('GET', '/transactions/export.xlsx')[0]?.query.get('type')).toBe('expense')
    expect(api.callsTo('GET', '/transactions/export.csv')).toHaveLength(0)
    click.mockRestore()
  })
})

describe('correcting a transaction', () => {
  async function openEditor(api: ReturnType<typeof standardApi>) {
    const user = userEvent.setup()
    renderApp(api, { path: '/transactions' })
    const table = await screen.findByRole('table', { name: 'Transactions' })
    await user.click(within(table).getAllByRole('button')[0]!)
    const dialog = await screen.findByRole('dialog', { name: 'Transaction details' })
    return { user, dialog }
  }

  it('sends only the changed fields with the loaded version', async () => {
    const api = standardApi()
      .on('GET', '/transactions', page([tx()]))
      .on('GET', '/transactions/tx-1', tx())
      .on('PATCH', '/transactions/tx-1', tx({ amount_poisha: 15075, version: 2 }))
    const { user, dialog } = await openEditor(api)
    await user.click(await within(dialog).findByRole('button', { name: 'Edit' }))
    const amount = within(dialog).getByLabelText('Amount (৳)')
    expect(amount).toHaveValue('120.50')
    await user.clear(amount)
    await user.type(amount, '১৫০.৭৫')
    await user.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(api.callsTo('PATCH', '/transactions/tx-1')).toHaveLength(1))
    expect(api.callsTo('PATCH', '/transactions/tx-1')[0]?.body).toEqual({ version: 1, amount: '150.75' })
    expect(await within(dialog).findByText('Saved.')).toBeInTheDocument()
  })

  it('validates the amount before sending', async () => {
    const api = standardApi()
      .on('GET', '/transactions', page([tx()]))
      .on('GET', '/transactions/tx-1', tx())
    const { user, dialog } = await openEditor(api)
    await user.click(await within(dialog).findByRole('button', { name: 'Edit' }))
    const amount = within(dialog).getByLabelText('Amount (৳)')
    await user.clear(amount)
    await user.type(amount, '12,50')
    await user.click(within(dialog).getByRole('button', { name: 'Save' }))
    expect(within(dialog).getByText(/at most two decimals/)).toBeInTheDocument()
    expect(amount).toHaveAttribute('aria-invalid', 'true')
    expect(api.callsTo('PATCH', '/transactions/tx-1')).toHaveLength(0)
  })

  it('on a version conflict, reloads fresh values instead of overwriting', async () => {
    let version = 1
    const api = standardApi()
      .on('GET', '/transactions', page([tx()]))
      .on('GET', '/transactions/tx-1', () =>
        json(tx(version === 1 ? {} : { version: 2, note: 'changed elsewhere', amount_poisha: 99_00 })),
      )
      .on('PATCH', '/transactions/tx-1', apiError(409, 'conflict', 'stale', [{ current_version: 2 }]))
    const { user, dialog } = await openEditor(api)
    await user.click(await within(dialog).findByRole('button', { name: 'Edit' }))
    await user.clear(within(dialog).getByLabelText('Note'))
    await user.type(within(dialog).getByLabelText('Note'), 'my note')
    await user.click(within(dialog).getByRole('button', { name: 'Save' }))
    expect(await within(dialog).findByText(/Someone else changed this transaction/)).toBeInTheDocument()

    version = 2
    await user.click(within(dialog).getByRole('button', { name: 'Reload' }))
    // The form is rebuilt from the new version: the other person's amount is
    // shown, so saving cannot silently revert it.
    await waitFor(() => expect(within(dialog).getByLabelText('Amount (৳)')).toHaveValue('99.00'))
    expect(within(dialog).getByLabelText('Note')).toHaveValue('changed elsewhere')
  })

  it('voids with a required reason', async () => {
    const api = standardApi()
      .on('GET', '/transactions', page([tx()]))
      .on('GET', '/transactions/tx-1', tx())
      .on('POST', '/transactions/tx-1/void', tx({ version: 2, voided_at: '2026-10-15T00:00:00Z', void_reason: 'Entered twice' }))
    const { user, dialog } = await openEditor(api)
    await user.click(await within(dialog).findByRole('button', { name: 'Void' }))
    const confirm = within(dialog).getByRole('button', { name: 'Void this transaction' })
    expect(confirm).toBeDisabled()
    await user.type(within(dialog).getByLabelText('Reason for voiding'), 'Entered twice')
    await user.click(confirm)
    await waitFor(() =>
      expect(api.callsTo('POST', '/transactions/tx-1/void')[0]?.body).toEqual({
        version: 1,
        reason: 'Entered twice',
      }),
    )
  })

  it('does not offer edits to viewers', async () => {
    const api = standardApi('viewer')
      .on('GET', '/transactions', page([tx()]))
      .on('GET', '/transactions/tx-1', tx())
    const { dialog } = await openEditor(api)
    expect(await within(dialog).findByText('rice')).toBeInTheDocument()
    expect(within(dialog).queryByRole('button', { name: 'Edit' })).toBeNull()
    expect(within(dialog).queryByRole('button', { name: 'Void' })).toBeNull()
  })

  it('does not offer edits on voided transactions', async () => {
    const voided = tx({ voided_at: '2026-10-06T00:00:00Z', void_reason: 'dup', voided_by_user_id: 'u-admin' })
    const api = standardApi()
      .on('GET', '/transactions', page([voided]))
      .on('GET', '/transactions/tx-1', voided)
    const { dialog } = await openEditor(api)
    expect(await within(dialog).findByText(/dup/)).toBeInTheDocument()
    expect(within(dialog).queryByRole('button', { name: 'Edit' })).toBeNull()
  })
})
