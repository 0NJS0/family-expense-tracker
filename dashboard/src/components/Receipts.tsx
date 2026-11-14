import { useId, useState } from 'react'
import type { ChangeEvent } from 'react'
import { ApiError } from '../api/client'
import { MAX_RECEIPT_BYTES, useAddReceipt, useReceiptUrl, useReceipts, useRemoveReceipt } from '../api/receipts'
import { useUpdateTransaction } from '../api/queries'
import type { Receipt, Transaction } from '../api/types'
import { useSession } from '../auth/session'
import { useI18n } from '../i18n/context'
import { errorKey } from '../lib/errors'
import { poishaToInputText } from '../lib/money'
import { Money } from './common'

/** Receipts of one transaction: view, add, remove, and an OCR suggestion to confirm. */
export function Receipts({ tx, editable }: { tx: Transaction; editable: boolean }) {
  const { t } = useI18n()
  const id = useId()
  const receipts = useReceipts(tx.id)
  const add = useAddReceipt(tx.id)
  const [tooBig, setTooBig] = useState(false)

  function choose(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    e.target.value = ''
    if (!file) return
    setTooBig(file.size > MAX_RECEIPT_BYTES)
    if (file.size <= MAX_RECEIPT_BYTES) add.mutate(file)
  }

  const list = receipts.data ?? []
  if (list.length === 0 && !editable) return null
  return (
    <section className="receipts" aria-labelledby={`${id}-title`}>
      <h3 id={`${id}-title`}>{t('receipts')}</h3>
      {list.map((r) => (
        <ReceiptItem key={r.id} receipt={r} tx={tx} editable={editable} />
      ))}
      {editable ? (
        <label htmlFor={`${id}-file`} className="file-button">
          {t('addReceipt')}
          <input id={`${id}-file`} type="file" accept="image/jpeg,image/png,image/webp,application/pdf" onChange={choose} disabled={add.isPending} />
        </label>
      ) : null}
      {tooBig ? <p className="field-error">{t('receiptTooBig')}</p> : null}
      {add.error ? (
        <p className="state-error" role="alert">
          {add.error instanceof ApiError && add.error.status === 422 ? add.error.message : t(errorKey(add.error))}
        </p>
      ) : null}
    </section>
  )
}

function ReceiptItem({ receipt, tx, editable }: { receipt: Receipt; tx: Transaction; editable: boolean }) {
  const { t } = useI18n()
  const { me, role } = useSession()
  const url = useReceiptUrl(receipt.id)
  const remove = useRemoveReceipt()
  const update = useUpdateTransaction()
  const canRemove = editable && (role === 'admin' || receipt.uploaded_by_user_id === me?.user.id)
  const suggestion =
    receipt.ocr_status === 'done' && receipt.ocr_amount_poisha !== null && receipt.ocr_amount_poisha !== tx.amount_poisha
      ? receipt.ocr_amount_poisha
      : null
  return (
    <div className="receipt">
      {url === null ? (
        <span className="hint">{t('loading')}</span>
      ) : receipt.content_type === 'image/jpeg' ? (
        <a href={url} target="_blank" rel="noopener noreferrer">
          <img src={url} alt={t('receiptImage')} className="receipt-thumb" />
        </a>
      ) : (
        <a href={url} target="_blank" rel="noopener noreferrer">
          {t('openPdf')}
        </a>
      )}
      <div className="receipt-meta">
        {receipt.ocr_status === 'pending' ? <span className="hint">{t('ocrPending')}</span> : null}
        {suggestion !== null ? (
          <p className="notice">
            {t('ocrSuggests')} <Money poisha={suggestion} />
            {editable ? (
              <button
                type="button"
                disabled={update.isPending}
                // A suggestion only: applying it is a normal, versioned correction.
                onClick={() => update.mutate({ id: tx.id, patch: { version: tx.version, amount: poishaToInputText(suggestion) } })}
              >
                {t('useThisAmount')}
              </button>
            ) : null}
          </p>
        ) : null}
        {canRemove ? (
          <button type="button" className="danger" disabled={remove.isPending} onClick={() => remove.mutate(receipt.id)}>
            {t('removeReceipt')}
          </button>
        ) : null}
        {update.error || remove.error ? <p className="state-error" role="alert">{t(errorKey(update.error ?? remove.error))}</p> : null}
      </div>
    </div>
  )
}
