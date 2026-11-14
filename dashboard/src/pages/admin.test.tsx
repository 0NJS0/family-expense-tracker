import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { apiError, emptySummary, json, page, renderApp, standardApi, tx } from '../test/fakeApi'

describe('overview extras', () => {
  it('lists the biggest expenses using the backend ordering', async () => {
    const api = standardApi().on('GET', '/transactions', (call: { query: URLSearchParams }) =>
      call.query.get('sort') === 'largest'
        ? json(page([tx({ id: 'big', amount_poisha: 500_000, note: 'rent' }), tx({ id: 'small', amount_poisha: 120_00 })]))
        : json(page([])),
    )
    renderApp(api)
    const panel = (await screen.findByRole('heading', { name: 'Biggest expenses' })).closest('section')!
    expect(await within(panel).findByText('৳5,000.00')).toBeInTheDocument()
    const call = api.callsTo('GET', '/transactions').find((c) => c.query.get('sort') === 'largest')!
    expect(call.query.get('type')).toBe('expense')
    expect(call.query.get('limit')).toBe('5')
  })

  it('shows who spent what with exact amounts and shares', async () => {
    const api = standardApi().on(
      'GET',
      '/reports/summary',
      emptySummary({
        expense_poisha: 400_00,
        by_member: [
          { user_id: 'u-amma', display_name: 'Amma', income_poisha: 0, expense_poisha: 300_00, transaction_count: 3 },
          { user_id: 'u-admin', display_name: 'Admin', income_poisha: 0, expense_poisha: 100_00, transaction_count: 1 },
        ],
      }),
    )
    renderApp(api)
    const panel = (await screen.findByRole('heading', { name: 'Who spent what' })).closest('section')!
    expect(await within(panel).findByText('Amma')).toBeInTheDocument()
    expect(within(panel).getByText('৳300.00')).toBeInTheDocument()
    expect(within(panel).getAllByText('75.0% of spending')).not.toHaveLength(0)
    expect(within(panel).getAllByText('25.0% of spending')).not.toHaveLength(0)
  })
})

describe('layout', () => {
  it('switches theme and remembers it', async () => {
    const user = userEvent.setup()
    renderApp(standardApi())
    await user.selectOptions(await screen.findByLabelText('Theme'), 'dark')
    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(localStorage.getItem('fet.theme')).toBe('dark')
    await user.selectOptions(screen.getByLabelText('Theme'), 'system')
    expect(document.documentElement.dataset.theme).toBeUndefined()
  })

  it('has a menu button for small screens', async () => {
    const user = userEvent.setup()
    renderApp(standardApi())
    const menu = await screen.findByRole('button', { name: 'Menu' })
    expect(menu).toHaveAttribute('aria-expanded', 'false')
    await user.click(menu)
    expect(menu).toHaveAttribute('aria-expanded', 'true')
    await user.click(screen.getByRole('link', { name: /Analytics/ }))
    expect(menu).toHaveAttribute('aria-expanded', 'false')
  })
})

describe('family & settings', () => {
  it('is read-only for viewers', async () => {
    renderApp(standardApi('viewer'), { path: '/admin' })
    expect(await screen.findByText('Only family admins can change these settings.')).toBeInTheDocument()
    expect(await screen.findByText('Amma')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Create invitation' })).toBeNull()
    expect(screen.queryByRole('combobox', { name: /Role: Amma/ })).toBeNull()
  })

  it('creates an invitation link shown once', async () => {
    const user = userEvent.setup()
    const api = standardApi().on('POST', '/members/invitations', {
      id: 'inv-1', role: 'member', display_name: 'Nana', expires_at: '2026-10-22T00:00:00Z', token: 'secret token',
    })
    renderApp(api, { path: '/admin' })
    await user.type(await screen.findByLabelText('Name'), 'Nana')
    await user.click(screen.getByRole('button', { name: 'Create invitation' }))
    const link = await screen.findByLabelText('Invitation link')
    expect(link).toHaveValue(`${window.location.origin}/accept?token=secret%20token`)
    expect(api.callsTo('POST', '/members/invitations')[0]?.body).toEqual({ display_name: 'Nana', role: 'member' })
  })

  it('changes roles and removes members, explaining the last-admin rule', async () => {
    const user = userEvent.setup()
    let fail = false
    const api = standardApi().on('PATCH', '/members/u-amma', () =>
      fail ? apiError(409, 'conflict', 'The household must keep at least one active admin') : json({}),
    )
    renderApp(api, { path: '/admin' })
    await user.selectOptions(await screen.findByRole('combobox', { name: 'Role: Amma' }), 'viewer')
    await waitFor(() => expect(api.callsTo('PATCH', '/members/u-amma')[0]?.body).toEqual({ role: 'viewer' }))
    fail = true
    await user.click(screen.getByRole('button', { name: 'Remove from family' }))
    expect(await screen.findByText('The family must keep at least one active admin.')).toBeInTheDocument()
    expect(api.callsTo('PATCH', '/members/u-amma')[1]?.body).toEqual({ is_active: false })
    // You cannot remove yourself from this screen.
    expect(screen.getAllByRole('button', { name: 'Remove from family' })).toHaveLength(1)
  })

  it('creates an account with an exact (negative) opening balance', async () => {
    const user = userEvent.setup()
    const api = standardApi().on('POST', '/accounts', { id: 'acc-new' })
    renderApp(api, { path: '/admin' })
    await user.click(await screen.findByRole('tab', { name: 'Accounts' }))
    const form = (await screen.findByRole('heading', { name: 'Add account' })).closest('section')!
    await user.type(within(form).getByLabelText('Account name'), 'bKash')
    await user.selectOptions(within(form).getByLabelText('Kind'), 'mobile_wallet')
    await user.type(within(form).getByLabelText('Balance on the start date (৳)'), '-1,250.75')
    await user.click(within(form).getByRole('button', { name: 'Create' }))
    await waitFor(() => expect(api.callsTo('POST', '/accounts')).toHaveLength(1))
    const body = api.callsTo('POST', '/accounts')[0]?.body as Record<string, unknown>
    expect(body).toMatchObject({ name: 'bKash', kind: 'mobile_wallet', opening_balance: { amount_poisha: -125075 } })
    expect(body).not.toHaveProperty('owner_user_id')
  })

  it('creates a private account only together with an owner', async () => {
    const user = userEvent.setup()
    const api = standardApi().on('POST', '/accounts', { id: 'acc-new' })
    renderApp(api, { path: '/admin' })
    await user.click(await screen.findByRole('tab', { name: 'Accounts' }))
    const form = (await screen.findByRole('heading', { name: 'Add account' })).closest('section')!
    const privateBox = within(form).getByLabelText('Private')
    expect(privateBox).toBeDisabled() // a shared account cannot be private
    await user.type(within(form).getByLabelText('Account name'), "Amma's bKash")
    await user.selectOptions(within(form).getByLabelText('Owner'), 'u-amma')
    await user.click(privateBox)
    await user.click(within(form).getByRole('button', { name: 'Create' }))
    await waitFor(() => expect(api.callsTo('POST', '/accounts')).toHaveLength(1))
    expect(api.callsTo('POST', '/accounts')[0]?.body).toMatchObject({ owner_user_id: 'u-amma', is_private: true })
    // Choosing "shared" again clears the private choice.
    await user.selectOptions(within(form).getByLabelText('Owner'), '')
    expect(privateBox).not.toBeChecked()
  })

  it('switches an owned account between private and visible', async () => {
    const user = userEvent.setup()
    const api = standardApi()
      .on('GET', '/accounts', [
        { id: 'acc-own', name: 'Abba wallet', kind: 'cash', institution: null, owner_user_id: 'u-amma',
          is_shared: false, is_private: true, is_archived: false, opening_balance: null, created_at: '2026-01-01T00:00:00Z' },
      ])
      .on('PATCH', '/accounts/acc-own', { id: 'acc-own' })
    renderApp(api, { path: '/admin' })
    await user.click(await screen.findByRole('tab', { name: 'Accounts' }))
    const row = (await screen.findByText('Abba wallet')).closest('tr')!
    expect(within(row).getByText(/Private/)).toBeInTheDocument()
    await user.click(within(row).getByRole('button', { name: 'Show to whole family' }))
    await waitFor(() => expect(api.callsTo('PATCH', '/accounts/acc-own')[0]?.body).toEqual({ is_private: false }))
  })

  it('refuses an ambiguous opening amount without sending anything', async () => {
    const user = userEvent.setup()
    const api = standardApi()
    renderApp(api, { path: '/admin' })
    await user.click(await screen.findByRole('tab', { name: 'Accounts' }))
    const form = (await screen.findByRole('heading', { name: 'Add account' })).closest('section')!
    await user.type(within(form).getByLabelText('Account name'), 'Bank')
    await user.type(within(form).getByLabelText('Balance on the start date (৳)'), '12,50')
    await user.click(within(form).getByRole('button', { name: 'Create' }))
    expect(await within(form).findByText(/use a minus sign/)).toBeInTheDocument()
    expect(api.callsTo('POST', '/accounts')).toHaveLength(0)
  })

  it('reports a duplicate account name clearly', async () => {
    const user = userEvent.setup()
    const api = standardApi().on('POST', '/accounts', apiError(409, 'conflict', 'An account with this name already exists'))
    renderApp(api, { path: '/admin' })
    await user.click(await screen.findByRole('tab', { name: 'Accounts' }))
    const form = (await screen.findByRole('heading', { name: 'Add account' })).closest('section')!
    await user.type(within(form).getByLabelText('Account name'), 'Cash')
    await user.click(within(form).getByRole('button', { name: 'Create' }))
    expect(await within(form).findByText('That name is already used.')).toBeInTheDocument()
  })

  it('corrects an opening balance, sending the values that were shown', async () => {
    const user = userEvent.setup()
    const api = standardApi()
      .on('GET', '/accounts', [
        { id: 'acc-bank', name: 'Bank', kind: 'bank', institution: null, owner_user_id: null, is_shared: true,
          is_private: false, is_archived: false, opening_balance: { amount_poisha: 100_000, as_of_date: '2026-01-01', created_at: '2026-01-01T00:00:00Z' },
          created_at: '2026-01-01T00:00:00Z' },
      ])
      .on('POST', '/accounts/acc-bank/opening-balance/correct', apiError(409, 'conflict', 'The opening balance was changed by someone else; reload and try again'))
    renderApp(api, { path: '/admin' })
    await user.click(await screen.findByRole('tab', { name: 'Accounts' }))
    const row = (await screen.findByText('Bank', { selector: 'th' })).closest('tr')!
    await user.click(within(row).getByRole('button', { name: 'Correct opening balance' }))
    const amount = await screen.findByDisplayValue('1000.00')
    await user.clear(amount)
    await user.type(amount, '-250.50')
    await user.type(screen.getByLabelText('Why is it being corrected?'), 'Wrong figure')
    await user.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(api.callsTo('POST', '/accounts/acc-bank/opening-balance/correct')).toHaveLength(1))
    expect(api.callsTo('POST', '/accounts/acc-bank/opening-balance/correct')[0]?.body).toEqual({
      previous_amount_poisha: 100_000, previous_as_of_date: '2026-01-01',
      amount_poisha: -25_050, as_of_date: '2026-01-01', reason: 'Wrong figure',
    })
    expect(await screen.findByText(/changed by someone else/)).toBeInTheDocument()
  })

  it('creates and archives categories', async () => {
    const user = userEvent.setup()
    const api = standardApi()
      .on('POST', '/categories', { id: 'c-new' })
      .on('PATCH', '/categories/cat-transport', { id: 'cat-transport' })
    renderApp(api, { path: '/admin' })
    await user.click(await screen.findByRole('tab', { name: 'Categories' }))
    const form = (await screen.findByRole('heading', { name: 'Add category' })).closest('section')!
    await user.type(within(form).getByLabelText('Name (Bangla)'), 'পোষা প্রাণী')
    await user.type(within(form).getByLabelText('Name (English)'), 'Pets')
    await user.click(within(form).getByRole('button', { name: 'Create' }))
    await waitFor(() =>
      expect(api.callsTo('POST', '/categories')[0]?.body).toEqual({ kind: 'expense', name_bn: 'পোষা প্রাণী', name_en: 'Pets' }),
    )
    const row = screen.getByText('যাতায়াত').closest('tr')!
    await user.click(within(row).getByRole('button', { name: 'Archive' }))
    await waitFor(() => expect(api.callsTo('PATCH', '/categories/cat-transport')[0]?.body).toEqual({ is_archived: true }))
  })
})

describe('accepting an invitation', () => {
  it('validates locally, then creates the account', async () => {
    const user = userEvent.setup()
    const api = standardApi().on('POST', '/invitations/accept', json({ id: 'u-new' }, 201))
    renderApp(api, { path: '/accept?token=abc', signedIn: false })
    await user.type(await screen.findByLabelText('Username'), 'Nana')
    await user.type(screen.getByLabelText('Password'), 'long enough pass')
    await user.type(screen.getByLabelText('Repeat password'), 'different pass!')
    await user.click(screen.getByRole('button', { name: 'Create my account' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('The passwords do not match.')
    expect(api.callsTo('POST', '/invitations/accept')).toHaveLength(0)

    await user.clear(screen.getByLabelText('Repeat password'))
    await user.type(screen.getByLabelText('Repeat password'), 'long enough pass')
    await user.click(screen.getByRole('button', { name: 'Create my account' }))
    expect(await screen.findByRole('status')).toHaveTextContent('Your account is ready.')
    expect(api.callsTo('POST', '/invitations/accept')[0]?.body).toEqual({
      token: 'abc', username: 'nana', password: 'long enough pass', preferred_language: 'en',
    })
  })

  it.each([
    [404, 'This invitation is invalid, already used or expired.'],
    [409, 'That username is already taken.'],
  ])('explains a %i from the server', async (status, message) => {
    const user = userEvent.setup()
    const api = standardApi().on('POST', '/invitations/accept', apiError(status, 'x'))
    renderApp(api, { path: '/accept?token=abc', signedIn: false })
    await user.type(await screen.findByLabelText('Username'), 'nana')
    await user.type(screen.getByLabelText('Password'), 'long enough pass')
    await user.type(screen.getByLabelText('Repeat password'), 'long enough pass')
    await user.click(screen.getByRole('button', { name: 'Create my account' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(message)
  })
})
