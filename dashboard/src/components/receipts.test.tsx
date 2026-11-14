import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { page, renderApp, standardApi, tx } from '../test/fakeApi'

const RECEIPT = {
  id: 'rc-1', transaction_id: 'tx-1', content_type: 'image/jpeg', size_bytes: 1234, uploaded_by_user_id: 'u-admin',
  created_at: '2026-10-15T06:00:00Z', ocr_status: 'done', ocr_amount_poisha: 13000, ocr_date: null,
}

function api(role: 'admin' | 'viewer' = 'admin', receipts: unknown[] = [RECEIPT]) {
  return standardApi(role)
    .on('GET', '/transactions', page([tx()]))
    .on('GET', '/transactions/tx-1', tx())
    .on('GET', '/transactions/tx-1/receipts', receipts)
    .on('GET', '/receipts/rc-1/file', () => new Response(new Blob(['jpeg']), { headers: { 'Content-Type': 'image/jpeg' } }))
}

async function openDrawer(fake: ReturnType<typeof api>) {
  const user = userEvent.setup()
  renderApp(fake, { path: '/transactions' })
  const table = await screen.findByRole('table', { name: 'Transactions' })
  await user.click(within(table).getAllByRole('button')[0]!)
  return { user, dialog: await screen.findByRole('dialog', { name: 'Transaction details' }) }
}

beforeEach(() => {
  vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:receipt')
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
})
afterEach(() => vi.restoreAllMocks())

describe('receipts in the transaction panel', () => {
  it('shows the photo through the authenticated API and applies a suggestion only when asked', async () => {
    const fake = api().on('PATCH', '/transactions/tx-1', tx({ amount_poisha: 13000, version: 2 }))
    const { user, dialog } = await openDrawer(fake)
    const image = await within(dialog).findByRole('img', { name: 'Receipt photo' })
    expect(image).toHaveAttribute('src', 'blob:receipt')
    expect(within(dialog).getByText(/The receipt seems to say/)).toHaveTextContent('৳130.00')
    expect(fake.callsTo('PATCH', '/transactions/tx-1')).toHaveLength(0) // nothing applied by itself
    await user.click(within(dialog).getByRole('button', { name: 'Use this amount' }))
    await waitFor(() => expect(fake.callsTo('PATCH', '/transactions/tx-1')[0]?.body).toEqual({ version: 1, amount: '130.00' }))
  })

  it('uploads the chosen file as raw bytes', async () => {
    const fake = api('admin', []).on('POST', '/transactions/tx-1/receipts', RECEIPT)
    const { user, dialog } = await openDrawer(fake)
    const file = new File(['%PDF-1.4'], 'bill.pdf', { type: 'application/pdf' })
    await user.upload(await within(dialog).findByLabelText('Add a receipt (photo or PDF)'), file)
    await waitFor(() => expect(fake.callsTo('POST', '/transactions/tx-1/receipts')).toHaveLength(1))
    expect(fake.callsTo('POST', '/transactions/tx-1/receipts')[0]?.body).toBe(file)
  })

  it('refuses a file over 10 MB without sending it', async () => {
    const fake = api('admin', [])
    const { user, dialog } = await openDrawer(fake)
    const big = new File([new Uint8Array(10 * 1024 * 1024 + 1)], 'big.jpg', { type: 'image/jpeg' })
    await user.upload(await within(dialog).findByLabelText('Add a receipt (photo or PDF)'), big)
    expect(await within(dialog).findByText('The file is larger than 10 MB.')).toBeInTheDocument()
    expect(fake.callsTo('POST', '/transactions/tx-1/receipts')).toHaveLength(0)
  })

  it('viewers can look but not add, remove or apply', async () => {
    const { dialog } = await openDrawer(api('viewer'))
    await within(dialog).findByRole('img', { name: 'Receipt photo' })
    expect(within(dialog).queryByLabelText('Add a receipt (photo or PDF)')).not.toBeInTheDocument()
    expect(within(dialog).queryByRole('button', { name: 'Use this amount' })).not.toBeInTheDocument()
    expect(within(dialog).queryByRole('button', { name: 'Remove receipt' })).not.toBeInTheDocument()
  })
})
