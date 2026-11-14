import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { apiError, renderApp, standardApi } from '../test/fakeApi'

const base = { language: 'bn', facts: null, model: 'qwen', attempts: 1, created_at: '2026-10-01T00:00:00Z', finished_at: null, problem: null }
const REPORTS = [
  { ...base, id: 'r1', period_start: '2026-09-01', status: 'done', text: '<b>এই মাসে খরচ 19500.00 টাকা।</b>' },
  { ...base, id: 'r2', period_start: '2026-08-01', status: 'rejected', text: null, problem: 'numbers not in the facts: 999' },
  { ...base, id: 'r3', period_start: '2026-07-01', status: 'queued', text: null },
]

describe('AI summaries', () => {
  it('shows checked summaries as plain text, explains rejected ones and waits for queued ones', async () => {
    renderApp(standardApi().on('GET', '/ai-reports', REPORTS), { path: '/summaries' })
    // Rendered as text: markup in a reply is never interpreted.
    expect(await screen.findByText('<b>এই মাসে খরচ 19500.00 টাকা।</b>')).toBeInTheDocument()
    expect(document.querySelector('b')).toBeNull()
    expect(screen.getByText(/Every number was checked/)).toBeInTheDocument()
    expect(screen.getByText(/did not match the figures/)).toBeInTheDocument()
    expect(screen.getByText(/Being written on the home server/)).toBeInTheDocument()
  })

  it('asks for a month and language, and explains when AI is not set up', async () => {
    const user = userEvent.setup()
    const api = standardApi()
      .on('GET', '/ai-reports', [])
      .on('POST', '/ai-reports', apiError(422, 'validation_failed', 'AI summaries are not configured on this server'))
    renderApp(api, { path: '/summaries' })
    const form = (await screen.findByRole('heading', { name: 'Ask for a summary' })).closest('section')!
    await user.selectOptions(within(form).getByLabelText('Language'), 'en')
    await user.click(within(form).getByRole('button', { name: 'Ask for this month' }))
    await waitFor(() => expect(api.callsTo('POST', '/ai-reports')).toHaveLength(1))
    expect(api.callsTo('POST', '/ai-reports')[0]?.body).toMatchObject({ language: 'en', month: expect.stringMatching(/^\d{4}-\d{2}$/) })
    expect(await within(form).findByRole('alert')).toHaveTextContent('not configured')
  })

  it('is admin-only', async () => {
    const api = standardApi('member')
    renderApp(api, { path: '/summaries' })
    expect(await screen.findByText('Only family admins can see the AI summaries.')).toBeInTheDocument()
    expect(api.callsTo('GET', '/ai-reports')).toHaveLength(0)
  })
})
