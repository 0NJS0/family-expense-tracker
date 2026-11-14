import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { apiError, renderApp, standardApi } from '../test/fakeApi'

const STATUS = {
  month: '2026-10-01',
  is_partial: true,
  items: [
    { category_id: null, name_en: null, name_bn: null, budget_poisha: 100_000_00, warn_percent: 80,
      effective_month: '2026-09-01', spent_poisha: 45_383_00, remaining_poisha: 54_617_00, used_basis_points: 4538, state: 'ok' },
    { category_id: 'cat-groceries', name_en: 'Groceries', name_bn: 'বাজার', budget_poisha: 300_00, warn_percent: 80,
      effective_month: '2026-10-01', spent_poisha: 300_01, remaining_poisha: -1, used_basis_points: 10_000, state: 'over' },
  ],
}

function budgetApi(role: 'admin' | 'member' = 'admin') {
  return standardApi(role)
    .on('GET', '/budgets', STATUS)
    .on('GET', '/budgets/alerts', [
      { category_id: 'cat-groceries', name_en: 'Groceries', name_bn: 'বাজার', month: '2026-10-01', threshold_percent: 100,
        budget_poisha: 300_00, spent_poisha: 300_01, created_at: '2026-10-12T05:00:00Z' },
    ])
}

describe('budgets', () => {
  it('shows the server figures exactly, with the state in words', async () => {
    renderApp(budgetApi(), { path: '/budgets' })
    const overall = (await screen.findByText('Whole family (all expenses)', { selector: 'strong' })).closest('li')!
    expect(within(overall).getByText('✓ Within budget')).toBeInTheDocument()
    expect(overall).toHaveTextContent('৳45,383.00')
    expect(overall).toHaveTextContent('৳1,00,000.00') // lakh grouping, as everywhere in the app
    expect(overall).toHaveTextContent('45.4%')
    const groceries = screen.getAllByText('Groceries', { selector: 'strong' })[0]!.closest('li')!
    expect(within(groceries).getByText('⚠ Over budget')).toBeInTheDocument()
    expect(groceries).toHaveTextContent('Over by: ৳0.01')
    expect(await screen.findByText(/reached the budget/)).toBeInTheDocument()
  })

  it('sets a budget from the shown month with the amount as typed text', async () => {
    const user = userEvent.setup()
    const api = budgetApi().on('PUT', '/budgets', STATUS)
    renderApp(api, { path: '/budgets' })
    const form = (await screen.findByRole('heading', { name: 'Set a budget' })).closest('section')!
    await within(form).findByRole('option', { name: 'Groceries' }) // categories load from the server
    await user.selectOptions(within(form).getByLabelText('Budget for'), 'cat-groceries')
    await user.type(within(form).getByLabelText('Monthly budget (৳)'), '১,২৫০.৫০')
    await user.clear(within(form).getByLabelText('Warn at (% of budget)'))
    await user.type(within(form).getByLabelText('Warn at (% of budget)'), '75')
    await user.click(within(form).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(api.callsTo('PUT', '/budgets')).toHaveLength(1))
    const body = api.callsTo('PUT', '/budgets')[0]?.body as Record<string, unknown>
    expect(body).toEqual({ category_id: 'cat-groceries', amount: '1,250.50', warn_percent: 75, from_month: expect.stringMatching(/^\d{4}-\d{2}$/) })
  })

  it('refuses a bad amount or warning level without sending', async () => {
    const user = userEvent.setup()
    const api = budgetApi()
    renderApp(api, { path: '/budgets' })
    const form = (await screen.findByRole('heading', { name: 'Set a budget' })).closest('section')!
    await user.type(within(form).getByLabelText('Monthly budget (৳)'), '12,50')
    await user.click(within(form).getByRole('button', { name: 'Save' }))
    expect(await within(form).findByRole('alert')).toHaveTextContent('Enter an amount like 120')
    await user.clear(within(form).getByLabelText('Monthly budget (৳)'))
    await user.type(within(form).getByLabelText('Monthly budget (৳)'), '100')
    await user.clear(within(form).getByLabelText('Warn at (% of budget)'))
    await user.type(within(form).getByLabelText('Warn at (% of budget)'), '100')
    await user.click(within(form).getByRole('button', { name: 'Save' }))
    expect(await within(form).findByRole('alert')).toHaveTextContent('1 to 99')
    expect(api.callsTo('PUT', '/budgets')).toHaveLength(0)
  })

  it('shows the server explanation when a budget cannot be removed', async () => {
    const user = userEvent.setup()
    const api = budgetApi().on('PUT', '/budgets', apiError(404, 'not_found', 'There is no budget to remove'))
    renderApp(api, { path: '/budgets' })
    const form = (await screen.findByRole('heading', { name: 'Set a budget' })).closest('section')!
    await user.click(within(form).getByRole('button', { name: 'Remove this budget' }))
    expect(await within(form).findByText('There is no budget to remove')).toBeInTheDocument()
  })

  it('browses earlier months read-only and asks the server for that month', async () => {
    const user = userEvent.setup()
    const api = budgetApi()
    renderApp(api, { path: '/budgets' })
    await screen.findByRole('heading', { name: 'Set a budget' })
    await user.click(screen.getByRole('button', { name: /Previous/ }))
    expect(await screen.findByText(/cannot be changed/)).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Set a budget' })).not.toBeInTheDocument()
    const months = api.callsTo('GET', '/budgets').map((c) => c.query.get('month'))
    expect(new Set(months).size).toBe(2)
  })

  it('lets members see budgets but not change them', async () => {
    renderApp(budgetApi('member'), { path: '/budgets' })
    expect(await screen.findByText('Whole family (all expenses)', { selector: 'strong' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Set a budget' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Budget alerts' })).not.toBeInTheDocument()
  })
})
