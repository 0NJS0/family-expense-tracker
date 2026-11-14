import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { apiError, renderApp, standardApi } from '../test/fakeApi'

const DUE = [
  { id: 'occ-1', rule_id: 'r-1', rule_name: 'House rent', type: 'expense', amount_poisha: 15_000_00,
    category_id: 'cat-groceries', account_id: 'acc-bank', destination_account_id: null,
    due_date: '2026-10-01', status: 'pending', transaction_id: null, resolved_at: null },
]
const RULES = [
  { id: 'r-1', name: 'House rent', type: 'expense', amount_poisha: 15_000_00, category_id: 'cat-groceries',
    account_id: 'acc-bank', destination_account_id: null, attributed_user_id: 'u-admin', note: null,
    frequency: 'monthly', interval: 1, start_date: '2026-01-01', end_date: null, auto_post: false, paused: false,
    next_due_date: '2026-11-01', last_processed_date: '2026-10-01', version: 3 },
]

function recurringApi(role: 'admin' | 'member' | 'viewer' = 'admin') {
  return standardApi(role).on('GET', '/recurring/occurrences', DUE).on('GET', '/recurring-rules', RULES)
}

describe('recurring entries', () => {
  it('marks a due item paid with the real amount, as typed text', async () => {
    const user = userEvent.setup()
    const api = recurringApi().on('POST', '/recurring/occurrences/occ-1/confirm', { ...DUE[0], status: 'posted' })
    renderApp(api, { path: '/recurring' })
    const row = (await screen.findAllByText('House rent'))[0]!.closest('li')!
    await user.type(within(row).getByLabelText('Amount paid (if different)'), '১৫,২৫০.৫০')
    await user.click(within(row).getByRole('button', { name: 'Paid' }))
    await waitFor(() => expect(api.callsTo('POST', '/recurring/occurrences/occ-1/confirm')[0]?.body).toEqual({ amount: '15,250.50' }))
  })

  it('confirms with the expected amount when none is typed, and can skip', async () => {
    const user = userEvent.setup()
    const api = recurringApi()
      .on('POST', '/recurring/occurrences/occ-1/confirm', DUE[0])
      .on('POST', '/recurring/occurrences/occ-1/skip', apiError(409, 'conflict', 'This occurrence was already recorded; void the transaction instead'))
    renderApp(api, { path: '/recurring' })
    const row = (await screen.findAllByText('House rent'))[0]!.closest('li')!
    await user.click(within(row).getByRole('button', { name: 'Paid' }))
    await waitFor(() => expect(api.callsTo('POST', '/recurring/occurrences/occ-1/confirm')[0]?.body).toEqual({}))
    await user.click(within(row).getByRole('button', { name: 'Skip this time' }))
    expect(await screen.findByText(/already recorded/)).toBeInTheDocument()
  })

  it('pauses a rule with its version', async () => {
    const user = userEvent.setup()
    const api = recurringApi().on('PATCH', '/recurring-rules/r-1', { ...RULES[0], paused: true })
    renderApp(api, { path: '/recurring' })
    const panel = (await screen.findByRole('heading', { name: 'Recurring entries' })).closest('section')!
    await user.click(await within(panel).findByRole('button', { name: 'Pause' }))
    await waitFor(() => expect(api.callsTo('PATCH', '/recurring-rules/r-1')[0]?.body).toEqual({ version: 3, paused: true }))
  })

  it('creates a rule that is not recorded automatically unless chosen', async () => {
    const user = userEvent.setup()
    const api = recurringApi().on('POST', '/recurring-rules', RULES[0])
    renderApp(api, { path: '/recurring' })
    const form = (await screen.findByRole('heading', { name: 'New recurring entry' })).closest('section')!
    await user.type(within(form).getByLabelText('Name (e.g. House rent)'), 'Rent')
    await user.type(within(form).getByLabelText('Expected amount (৳)'), '15000')
    await within(form).findByRole('option', { name: 'Groceries' })
    await user.selectOptions(within(form).getByLabelText('Category'), 'cat-groceries')
    await user.selectOptions(within(form).getByLabelText('Account'), 'acc-bank')
    await user.click(within(form).getByRole('button', { name: 'Create' }))
    await waitFor(() => expect(api.callsTo('POST', '/recurring-rules')).toHaveLength(1))
    expect(api.callsTo('POST', '/recurring-rules')[0]?.body).toMatchObject({
      name: 'Rent', type: 'expense', amount: '15000', category_id: 'cat-groceries', account_id: 'acc-bank',
      frequency: 'monthly', interval: 1, auto_post: false,
    })
  })

  it('refuses an incomplete rule locally', async () => {
    const user = userEvent.setup()
    const api = recurringApi()
    renderApp(api, { path: '/recurring' })
    const form = (await screen.findByRole('heading', { name: 'New recurring entry' })).closest('section')!
    await user.type(within(form).getByLabelText('Name (e.g. House rent)'), 'Rent')
    await user.type(within(form).getByLabelText('Expected amount (৳)'), '15000')
    await user.click(within(form).getByRole('button', { name: 'Create' }))
    expect(await within(form).findByRole('alert')).toHaveTextContent('Choose the account')
    expect(api.callsTo('POST', '/recurring-rules')).toHaveLength(0)
  })

  it('viewers see what is due but cannot resolve or create', async () => {
    renderApp(recurringApi('viewer'), { path: '/recurring' })
    expect((await screen.findAllByText('House rent')).length).toBeGreaterThan(0)
    expect(screen.queryByRole('button', { name: 'Paid' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'New recurring entry' })).not.toBeInTheDocument()
  })
})
