import { useEffect, useId, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError } from '../api/client'
import { useTransaction, useUpdateTransaction, useVoidTransaction } from '../api/queries'
import { Receipts } from './Receipts'
import type { Transaction, TransactionPatch } from '../api/types'
import { useHousehold, useSession } from '../auth/session'
import { useI18n } from '../i18n/context'
import { formatDateTime, fromLocalInput, toLocalInput } from '../lib/dates'
import { checkTakaInput, poishaToInputText } from '../lib/money'
import { errorKey } from '../lib/errors'
import { ErrorState, Loading, Money } from './common'
import { useLookups } from './lookups'

interface Props {
  transactionId: string
  onClose: () => void
}

export function TransactionDrawer({ transactionId, onClose }: Props) {
  const { t } = useI18n()
  const query = useTransaction(transactionId)
  const closeRef = useRef<HTMLButtonElement>(null)
  const titleId = useId()

  useEffect(() => {
    closeRef.current?.focus()
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div className="drawer-backdrop" onClick={onClose}>
      <section
        className="drawer"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        onClick={(e) => e.stopPropagation()}
      >
        <header className="drawer-head">
          <h2 id={titleId}>{t('details')}</h2>
          <button ref={closeRef} type="button" onClick={onClose}>
            {t('close')}
          </button>
        </header>
        {query.data ? (
          <Details tx={query.data} onReload={() => void query.refetch()} />
        ) : query.error ? (
          <ErrorState error={query.error} onRetry={() => void query.refetch()} />
        ) : (
          <Loading />
        )}
      </section>
    </div>
  )
}

function canChange(tx: Transaction, role: string | null, userId: string | undefined): boolean {
  if (tx.voided_at) return false
  return role === 'admin' || (role === 'member' && tx.created_by_user_id === userId)
}

function Details({ tx, onReload }: { tx: Transaction; onReload: () => void }) {
  const { t, lang } = useI18n()
  const { role, me } = useSession()
  const household = useHousehold()
  const lookups = useLookups()
  const [mode, setMode] = useState<'view' | 'edit' | 'void'>('view')
  const [notice, setNotice] = useState<string | null>(null)
  const editable = canChange(tx, role, me?.user.id)

  return (
    <div className="drawer-body">
      <dl className="facts">
        <dt>{t('amount')}</dt>
        <dd>
          <Money poisha={tx.amount_poisha} />
        </dd>
        <dt>{t('date')}</dt>
        <dd>{formatDateTime(tx.occurred_at, lang, household.timezone)}</dd>
        {tx.category_id ? (
          <>
            <dt>{t('category')}</dt>
            <dd>{lookups.categoryName(tx.category_id)}</dd>
          </>
        ) : null}
        <dt>{t('account')}</dt>
        <dd>
          {lookups.accountName(tx.account_id)}
          {tx.destination_account_id ? ` → ${lookups.accountName(tx.destination_account_id)}` : ''}
        </dd>
        <dt>{t('attributedTo')}</dt>
        <dd>{lookups.memberName(tx.attributed_user_id)}</dd>
        <dt>{t('enteredBy')}</dt>
        <dd>{lookups.memberName(tx.created_by_user_id)}</dd>
        <dt>{t('createdAt')}</dt>
        <dd>{formatDateTime(tx.created_at, lang, household.timezone)}</dd>
        {tx.note ? (
          <>
            <dt>{t('note')}</dt>
            <dd className="note">{tx.note}</dd>
          </>
        ) : null}
        {tx.voided_at ? (
          <>
            <dt>{t('voided')}</dt>
            <dd>
              {formatDateTime(tx.voided_at, lang, household.timezone)} — {tx.void_reason}
            </dd>
          </>
        ) : null}
      </dl>

      <Receipts tx={tx} editable={editable} />

      {notice ? (
        <p className="notice" role="status">
          {notice}
        </p>
      ) : null}

      {editable && mode === 'view' ? (
        <div className="actions">
          <button type="button" onClick={() => setMode('edit')}>
            {t('edit')}
          </button>
          <button type="button" className="danger" onClick={() => setMode('void')}>
            {t('voidAction')}
          </button>
        </div>
      ) : null}
      {mode === 'edit' ? (
        <EditForm
          // Remount on a new version: after a conflict reload, untouched fields
          // must show the fresh values, or saving would silently revert the
          // other person's change.
          key={tx.version}
          tx={tx}
          onDone={() => {
            setMode('view')
            setNotice(t('saved'))
          }}
          onCancel={() => setMode('view')}
          onReload={onReload}
        />
      ) : null}
      {mode === 'void' ? (
        <VoidForm
          tx={tx}
          onDone={() => {
            setMode('view')
            setNotice(t('saved'))
          }}
          onCancel={() => setMode('view')}
          onReload={onReload}
        />
      ) : null}
    </div>
  )
}

function MutationError({ error, onReload }: { error: unknown; onReload: () => void }) {
  const { t } = useI18n()
  const key = errorKey(error)
  const message =
    error instanceof ApiError && error.status === 422 ? error.message : t(key)
  return (
    <div className="state state-error" role="alert">
      <p>{message}</p>
      {key === 'conflict' ? (
        <button type="button" onClick={onReload}>
          {t('reload')}
        </button>
      ) : null}
    </div>
  )
}

interface FormProps {
  tx: Transaction
  onDone: () => void
  onCancel: () => void
  onReload: () => void
}

function EditForm({ tx, onDone, onCancel, onReload }: FormProps) {
  const { t } = useI18n()
  const household = useHousehold()
  const lookups = useLookups()
  const mutation = useUpdateTransaction()
  const id = useId()
  const [amount, setAmount] = useState(poishaToInputText(tx.amount_poisha))
  const [categoryId, setCategoryId] = useState(tx.category_id ?? '')
  const [accountId, setAccountId] = useState(tx.account_id)
  const [destinationId, setDestinationId] = useState(tx.destination_account_id ?? '')
  const [when, setWhen] = useState(toLocalInput(tx.occurred_at, household.timezone))
  const [note, setNote] = useState(tx.note ?? '')
  const [attributed, setAttributed] = useState(tx.attributed_user_id)
  const [amountError, setAmountError] = useState<string | null>(null)

  const categories = lookups.categories.filter(
    (c) => c.kind === tx.type && (!c.is_archived || c.id === tx.category_id),
  )
  const accounts = lookups.accounts.filter(
    (a) => !a.is_archived || a.id === tx.account_id || a.id === tx.destination_account_id,
  )
  const members = lookups.members.filter((m) => m.is_active || m.user_id === tx.attributed_user_id)

  function submit(e: FormEvent) {
    e.preventDefault()
    const checked = checkTakaInput(amount)
    if (!checked.ok) {
      setAmountError(t(checked.reason === 'format' ? 'amountFormat' : 'amountRange'))
      return
    }
    setAmountError(null)
    // Send only what changed, plus the version this form was loaded from.
    const patch: TransactionPatch = { version: tx.version }
    if (checked.text !== poishaToInputText(tx.amount_poisha)) patch.amount = checked.text
    if (tx.type !== 'transfer' && categoryId !== tx.category_id) patch.category_id = categoryId
    if (accountId !== tx.account_id) patch.account_id = accountId
    if (tx.type === 'transfer' && destinationId !== tx.destination_account_id) {
      patch.destination_account_id = destinationId
    }
    if (when !== toLocalInput(tx.occurred_at, household.timezone)) {
      patch.occurred_at = fromLocalInput(when, household.timezone)
    }
    if (note.trim() !== (tx.note ?? '')) patch.note = note.trim() || null
    if (attributed !== tx.attributed_user_id) patch.attributed_user_id = attributed
    if (Object.keys(patch).length === 1) {
      onCancel()
      return
    }
    mutation.mutate({ id: tx.id, patch }, { onSuccess: onDone })
  }

  return (
    <form className="form" onSubmit={submit} noValidate>
      <label htmlFor={`${id}-amount`}>{t('amount')} (৳)</label>
      <input
        id={`${id}-amount`}
        inputMode="decimal"
        autoComplete="off"
        value={amount}
        aria-invalid={amountError !== null}
        aria-describedby={amountError ? `${id}-amount-error` : undefined}
        onChange={(e) => setAmount(e.target.value)}
      />
      {amountError ? (
        <span id={`${id}-amount-error`} className="field-error">
          {amountError}
        </span>
      ) : null}

      {tx.type !== 'transfer' ? (
        <>
          <label htmlFor={`${id}-category`}>{t('category')}</label>
          <select
            id={`${id}-category`}
            value={categoryId}
            onChange={(e) => setCategoryId(e.target.value)}
          >
            {categories.map((c) => (
              <option key={c.id} value={c.id}>
                {lookups.categoryLabel(c)}
              </option>
            ))}
          </select>
        </>
      ) : null}

      <label htmlFor={`${id}-account`}>{t('account')}</label>
      <select id={`${id}-account`} value={accountId} onChange={(e) => setAccountId(e.target.value)}>
        {accounts.map((a) => (
          <option key={a.id} value={a.id}>
            {a.name}
          </option>
        ))}
      </select>

      {tx.type === 'transfer' ? (
        <>
          <label htmlFor={`${id}-dest`}>{t('toAccount')}</label>
          <select
            id={`${id}-dest`}
            value={destinationId}
            onChange={(e) => setDestinationId(e.target.value)}
          >
            {accounts.map((a) => (
              <option key={a.id} value={a.id}>
                {a.name}
              </option>
            ))}
          </select>
        </>
      ) : null}

      <label htmlFor={`${id}-when`}>{t('date')}</label>
      <input
        id={`${id}-when`}
        type="datetime-local"
        value={when}
        onChange={(e) => setWhen(e.target.value)}
      />

      <label htmlFor={`${id}-who`}>{t('attributedTo')}</label>
      <select id={`${id}-who`} value={attributed} onChange={(e) => setAttributed(e.target.value)}>
        {members.map((m) => (
          <option key={m.user_id} value={m.user_id}>
            {m.display_name}
          </option>
        ))}
      </select>

      <label htmlFor={`${id}-note`}>{t('note')}</label>
      <textarea
        id={`${id}-note`}
        maxLength={500}
        value={note}
        onChange={(e) => setNote(e.target.value)}
      />

      {mutation.error ? <MutationError error={mutation.error} onReload={onReload} /> : null}
      <div className="actions">
        <button type="submit" className="primary" disabled={mutation.isPending}>
          {mutation.isPending ? t('saving') : t('save')}
        </button>
        <button type="button" onClick={onCancel}>
          {t('cancel')}
        </button>
      </div>
    </form>
  )
}

function VoidForm({ tx, onDone, onCancel, onReload }: FormProps) {
  const { t } = useI18n()
  const mutation = useVoidTransaction()
  const id = useId()
  const [reason, setReason] = useState('')

  function submit(e: FormEvent) {
    e.preventDefault()
    if (!reason.trim()) return
    mutation.mutate({ id: tx.id, version: tx.version, reason: reason.trim() }, { onSuccess: onDone })
  }

  return (
    <form className="form" onSubmit={submit}>
      <p>{t('voidHelp')}</p>
      <label htmlFor={`${id}-reason`}>{t('voidReason')}</label>
      <input
        id={`${id}-reason`}
        required
        maxLength={500}
        value={reason}
        onChange={(e) => setReason(e.target.value)}
      />
      {mutation.error ? <MutationError error={mutation.error} onReload={onReload} /> : null}
      <div className="actions">
        <button
          type="submit"
          className="danger"
          disabled={mutation.isPending || !reason.trim()}
        >
          {t('voidConfirm')}
        </button>
        <button type="button" onClick={onCancel}>
          {t('cancel')}
        </button>
      </div>
    </form>
  )
}
