import { useId, useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError } from '../api/client'
import { useAccounts, useCategories } from '../api/queries'
import { useCreateRule, useDue, useResolveOccurrence, useRules, useUpdateRule } from '../api/recurring'
import type { NewRule } from '../api/recurring'
import type { EntryType, Frequency, Occurrence } from '../api/types'
import { useHousehold, useSession } from '../auth/session'
import { Empty, Money, QueryView } from '../components/common'
import { useI18n } from '../i18n/context'
import { formatDate, todayIn } from '../lib/dates'
import { errorKey } from '../lib/errors'
import { checkTakaInput } from '../lib/money'

const FREQUENCIES: Frequency[] = ['monthly', 'weekly', 'yearly']
const TYPES: EntryType[] = ['expense', 'income', 'transfer']

/**
 * Recurring entries. A due item is only a reminder: money is recorded when
 * someone confirms it (or automatically, if the rule says so).
 */
export function RecurringPage() {
  const { t } = useI18n()
  const { role } = useSession()
  const isAdmin = role === 'admin'
  return (
    <div className="page">
      <div className="toolbar">
        <h1>{t('navRecurring')}</h1>
      </div>
      <div className="grid-2">
        <DuePanel canResolve={role !== 'viewer'} />
        <div className="stack">
          <RulesPanel isAdmin={isAdmin} />
          {isAdmin ? <NewRulePanel /> : null}
        </div>
      </div>
    </div>
  )
}

function useMessage(error: unknown): string | null {
  const { t } = useI18n()
  if (!error) return null
  if (error instanceof ApiError && (error.status === 409 || error.status === 422)) return error.message
  return t(errorKey(error))
}

function DuePanel({ canResolve }: { canResolve: boolean }) {
  const { t } = useI18n()
  const due = useDue()
  const resolve = useResolveOccurrence()
  const message = useMessage(resolve.error)
  return (
    <section className="panel">
      <h2>{t('dueNow')}</h2>
      <p className="hint">{t('dueExplain')}</p>
      <QueryView query={due}>
        {(list) =>
          list.length === 0 ? (
            <Empty>{t('nothingDue')}</Empty>
          ) : (
            <ul className="plain-list">
              {list.map((o) => (
                <DueRow key={o.id} occurrence={o} canResolve={canResolve} busy={resolve.isPending} onResolve={(action, amount) => resolve.mutate({ id: o.id, action, ...(amount ? { amount } : {}) })} />
              ))}
            </ul>
          )
        }
      </QueryView>
      {message ? <p className="state-error" role="alert">{message}</p> : null}
    </section>
  )
}

function DueRow({
  occurrence,
  canResolve,
  busy,
  onResolve,
}: {
  occurrence: Occurrence
  canResolve: boolean
  busy: boolean
  onResolve: (action: 'confirm' | 'skip', amount?: string) => void
}) {
  const { t, lang } = useI18n()
  const id = useId()
  const [amount, setAmount] = useState('')
  const [invalid, setInvalid] = useState(false)
  return (
    <li className="due-row">
      <div>
        <strong>{occurrence.rule_name}</strong> — <Money poisha={occurrence.amount_poisha} />
        <div className="hint">
          {t('dueOn')}: {formatDate(occurrence.due_date, lang)}
        </div>
      </div>
      {canResolve ? (
        <div className="row-actions">
          <label htmlFor={`${id}-amount`} className="visually-hidden">
            {t('actualAmount')}
          </label>
          <input
            id={`${id}-amount`}
            className="inline-input"
            inputMode="decimal"
            placeholder={t('actualAmount')}
            value={amount}
            aria-invalid={invalid}
            onChange={(e) => setAmount(e.target.value)}
          />
          <button
            type="button"
            className="primary"
            disabled={busy}
            onClick={() => {
              if (amount.trim()) {
                const check = checkTakaInput(amount)
                setInvalid(!check.ok)
                if (check.ok) onResolve('confirm', check.text)
              } else {
                onResolve('confirm')
              }
            }}
          >
            {t('markPaid')}
          </button>
          <button type="button" disabled={busy} onClick={() => onResolve('skip')}>
            {t('skipThisTime')}
          </button>
          {invalid ? <span className="field-error">{t('amountFormat')}</span> : null}
        </div>
      ) : null}
    </li>
  )
}

function RulesPanel({ isAdmin }: { isAdmin: boolean }) {
  const { t, lang } = useI18n()
  const rules = useRules()
  const update = useUpdateRule()
  const message = useMessage(update.error)
  return (
    <section className="panel">
      <h2>{t('recurringRules')}</h2>
      <QueryView query={rules}>
        {(list) =>
          list.length === 0 ? (
            <Empty>{t('noRules')}</Empty>
          ) : (
            <ul className="plain-list">
              {list.map((r) => (
                <li key={r.id} className="due-row">
                  <div>
                    <strong>{r.name}</strong> — <Money poisha={r.amount_poisha} />{' '}
                    <span className="badge">{t(`freq_${r.frequency}`, { n: r.interval })}</span>
                    {r.auto_post ? <span className="badge">{t('autoPostBadge')}</span> : null}
                    {r.paused ? <span className="badge">{t('pausedBadge')}</span> : null}
                    <div className="hint">
                      {t('nextDue')}: {r.next_due_date ? formatDate(r.next_due_date, lang) : t('ended')}
                    </div>
                  </div>
                  {isAdmin ? (
                    <button type="button" onClick={() => update.mutate({ id: r.id, version: r.version, paused: !r.paused })}>
                      {r.paused ? t('resume') : t('pause')}
                    </button>
                  ) : null}
                </li>
              ))}
            </ul>
          )
        }
      </QueryView>
      {message ? <p className="state-error" role="alert">{message}</p> : null}
    </section>
  )
}

function NewRulePanel() {
  const { t, lang } = useI18n()
  const household = useHousehold()
  const id = useId()
  const accounts = useAccounts()
  const categories = useCategories()
  const create = useCreateRule()
  const [name, setName] = useState('')
  const [type, setType] = useState<EntryType>('expense')
  const [amount, setAmount] = useState('')
  const [category, setCategory] = useState('')
  const [account, setAccount] = useState('')
  const [destination, setDestination] = useState('')
  const [frequency, setFrequency] = useState<Frequency>('monthly')
  const [interval, setInterval] = useState('1')
  const [start, setStart] = useState(todayIn(household.timezone))
  const [end, setEnd] = useState('')
  const [autoPost, setAutoPost] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const [done, setDone] = useState(false)
  const message = useMessage(create.error)
  const usable = (accounts.data ?? []).filter((a) => !a.is_archived)
  const cats = (categories.data ?? []).filter((c) => c.kind === type && !c.is_archived)

  function submit(e: FormEvent) {
    e.preventDefault()
    setDone(false)
    const check = checkTakaInput(amount)
    if (!check.ok) return setProblem(t(check.reason === 'format' ? 'amountFormat' : 'amountRange'))
    const every = Number(interval)
    if (!Number.isInteger(every) || every < 1 || every > 12) return setProblem(t('intervalInvalid'))
    if (!account || (type === 'transfer' ? !destination || destination === account : !category)) {
      return setProblem(t('ruleIncomplete'))
    }
    setProblem(null)
    const rule: NewRule = {
      name: name.trim(),
      type,
      amount: check.text,
      account_id: account,
      frequency,
      interval: every,
      start_date: start,
      auto_post: autoPost,
      ...(type === 'transfer' ? { destination_account_id: destination } : { category_id: category }),
      ...(end ? { end_date: end } : {}),
    }
    create.mutate(rule, {
      onSuccess: () => {
        setDone(true)
        setName('')
        setAmount('')
      },
    })
  }

  return (
    <section className="panel">
      <h2>{t('newRule')}</h2>
      <form className="form-grid" onSubmit={submit}>
        <label htmlFor={`${id}-name`}>
          {t('ruleName')}
          <input id={`${id}-name`} required maxLength={100} value={name} onChange={(e) => setName(e.target.value)} />
        </label>
        <label htmlFor={`${id}-type`}>
          {t('type')}
          <select id={`${id}-type`} value={type} onChange={(e) => { setType(e.target.value as EntryType); setCategory('') }}>
            {TYPES.map((x) => (
              <option key={x} value={x}>
                {t(x)}
              </option>
            ))}
          </select>
        </label>
        <label htmlFor={`${id}-amount`}>
          {t('expectedAmount')}
          <input id={`${id}-amount`} inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} />
        </label>
        {type === 'transfer' ? null : (
          <label htmlFor={`${id}-category`}>
            {t('category')}
            <select id={`${id}-category`} value={category} onChange={(e) => setCategory(e.target.value)}>
              <option value="">—</option>
              {cats.map((c) => (
                <option key={c.id} value={c.id}>
                  {lang === 'bn' ? c.name_bn : c.name_en}
                </option>
              ))}
            </select>
          </label>
        )}
        <label htmlFor={`${id}-account`}>
          {type === 'transfer' ? t('fromAccount') : t('account')}
          <select id={`${id}-account`} value={account} onChange={(e) => setAccount(e.target.value)}>
            <option value="">—</option>
            {usable.map((a) => (
              <option key={a.id} value={a.id}>
                {a.name}
              </option>
            ))}
          </select>
        </label>
        {type === 'transfer' ? (
          <label htmlFor={`${id}-dest`}>
            {t('toAccount')}
            <select id={`${id}-dest`} value={destination} onChange={(e) => setDestination(e.target.value)}>
              <option value="">—</option>
              {usable.filter((a) => a.id !== account).map((a) => (
                <option key={a.id} value={a.id}>
                  {a.name}
                </option>
              ))}
            </select>
          </label>
        ) : null}
        <label htmlFor={`${id}-freq`}>
          {t('frequency')}
          <select id={`${id}-freq`} value={frequency} onChange={(e) => setFrequency(e.target.value as Frequency)}>
            {FREQUENCIES.map((f) => (
              <option key={f} value={f}>
                {t(`freq_${f}`, { n: 1 })}
              </option>
            ))}
          </select>
        </label>
        <label htmlFor={`${id}-every`}>
          {t('every')}
          <input id={`${id}-every`} inputMode="numeric" value={interval} onChange={(e) => setInterval(e.target.value)} />
        </label>
        <label htmlFor={`${id}-start`}>
          {t('startDate')}
          <input id={`${id}-start`} type="date" required value={start} onChange={(e) => setStart(e.target.value)} />
        </label>
        <label htmlFor={`${id}-end`}>
          {t('endDate')}
          <input id={`${id}-end`} type="date" value={end} onChange={(e) => setEnd(e.target.value)} />
        </label>
        <label htmlFor={`${id}-auto`} className="checkbox">
          <input id={`${id}-auto`} type="checkbox" checked={autoPost} onChange={(e) => setAutoPost(e.target.checked)} />
          {t('autoPost')}
        </label>
        <button type="submit" className="primary" disabled={create.isPending || !name.trim()}>
          {t('create')}
        </button>
      </form>
      <p className="hint">{t('autoPostHelp')}</p>
      {problem ? <p className="field-error" role="alert">{problem}</p> : null}
      {message ? <p className="state-error" role="alert">{message}</p> : null}
      {done ? <p className="success" role="status">{t('saved')}</p> : null}
    </section>
  )
}
