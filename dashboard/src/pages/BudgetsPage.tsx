import { useId, useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError } from '../api/client'
import { useBudgetAlerts, useBudgetStatus, useSetBudget } from '../api/budgets'
import type { BudgetChange } from '../api/budgets'
import { useCategories } from '../api/queries'
import type { BudgetItem } from '../api/types'
import { useHousehold, useSession } from '../auth/session'
import { Empty, Money, QueryView } from '../components/common'
import { useI18n } from '../i18n/context'
import { addMonths, formatDate, formatMonth, todayIn } from '../lib/dates'
import { errorKey } from '../lib/errors'
import { checkTakaInput, formatBasisPoints } from '../lib/money'

/**
 * Budgets against actual spending, month by month. Every figure comes from the
 * server; the bar only visualises the server's basis points.
 */
export function BudgetsPage() {
  const { t, lang } = useI18n()
  const { role } = useSession()
  const household = useHousehold()
  const thisMonth = todayIn(household.timezone).slice(0, 7)
  const [month, setMonth] = useState(thisMonth)
  const status = useBudgetStatus(month)
  const isAdmin = role === 'admin'
  const alerts = useBudgetAlerts(month, isAdmin)

  return (
    <div className="page">
      <header className="toolbar">
        <h1>{t('navBudgets')}</h1>
        <div className="month-nav" role="group" aria-label={t('month')}>
          <button type="button" onClick={() => setMonth(addMonths(month, -1))}>
            ‹ {t('previousMonth')}
          </button>
          <strong aria-live="polite">{formatMonth(month, lang)}</strong>
          <button type="button" onClick={() => setMonth(addMonths(month, 1))} disabled={month > thisMonth}>
            {t('nextMonth')} ›
          </button>
        </div>
      </header>

      <div className="grid-2">
        <section className="panel">
          <h2>{t('budgetVsActual')}</h2>
          <QueryView query={status}>
            {(s) =>
              s.items.length === 0 ? (
                <Empty>{t('noBudgets')}</Empty>
              ) : (
                <ul className="budget-list">
                  {s.items.map((item) => (
                    <BudgetRow key={item.category_id ?? 'overall'} item={item} />
                  ))}
                </ul>
              )
            }
          </QueryView>
          {status.data?.is_partial ? <p className="hint">{t('budgetPartial')}</p> : null}
          <p className="hint">{t('budgetExpensesOnly')}</p>
        </section>

        {isAdmin ? (
          <div className="stack">
            {month >= thisMonth ? <BudgetForm month={month} /> : <p className="panel hint">{t('budgetPastReadOnly')}</p>}
            <section className="panel">
              <h2>{t('budgetAlerts')}</h2>
              <QueryView query={alerts}>
                {(list) =>
                  list.length === 0 ? (
                    <Empty>{t('noBudgetAlerts')}</Empty>
                  ) : (
                    <ul className="plain-list">
                      {list.map((a) => (
                        <li key={`${a.category_id ?? 'overall'}-${a.threshold_percent}`}>
                          <strong>{scopeName(a.category_id, a.name_en, a.name_bn, lang, t('budgetOverall'))}</strong>
                          {': '}
                          {a.threshold_percent === 100 ? t('alertReached') : t('alertWarning', { percent: a.threshold_percent })}
                          {' — '}
                          <Money poisha={a.spent_poisha} /> / <Money poisha={a.budget_poisha} />
                          <span className="hint"> ({formatDate(a.created_at.slice(0, 10), lang)})</span>
                        </li>
                      ))}
                    </ul>
                  )
                }
              </QueryView>
            </section>
          </div>
        ) : null}
      </div>
    </div>
  )
}

function scopeName(id: string | null, en: string | null, bn: string | null, lang: string, overall: string): string {
  if (id === null) return overall
  return (lang === 'bn' ? bn : en) ?? ''
}

function BudgetRow({ item }: { item: BudgetItem }) {
  const { t, lang } = useI18n()
  const name = scopeName(item.category_id, item.name_en, item.name_bn, lang, t('budgetOverall'))
  const percent = formatBasisPoints(item.used_basis_points, lang).replace(/^\+/, '')
  // The state is said in words and a symbol, not only by colour.
  const stateText = item.state === 'over' ? `⚠ ${t('budgetOver')}` : item.state === 'warning' ? `● ${t('budgetWarning')}` : `✓ ${t('budgetOk')}`
  return (
    <li className={`budget budget-${item.state}`}>
      <div className="budget-head">
        <strong>{name}</strong>
        <span className="budget-state">{stateText}</span>
      </div>
      <div className="budget-bar" aria-hidden="true">
        <span style={{ width: `${Math.min(item.used_basis_points, 10_000) / 100}%` }} />
      </div>
      <div className="budget-figures">
        <span>
          {t('spent')}: <Money poisha={item.spent_poisha} /> {t('of')} <Money poisha={item.budget_poisha} /> ({percent})
        </span>
        <span>
          {item.remaining_poisha >= 0 ? t('remaining') : t('overBy')}:{' '}
          <Money poisha={Math.abs(item.remaining_poisha)} />
        </span>
      </div>
    </li>
  )
}

function BudgetForm({ month }: { month: string }) {
  const { t, lang } = useI18n()
  const id = useId()
  const categories = useCategories()
  const setBudget = useSetBudget()
  const [scope, setScope] = useState('')
  const [amount, setAmount] = useState('')
  const [warn, setWarn] = useState('80')
  const [problem, setProblem] = useState<string | null>(null)
  const [done, setDone] = useState(false)
  const expenseCategories = (categories.data ?? []).filter((c) => c.kind === 'expense' && !c.is_archived)

  function send(change: Omit<BudgetChange, 'from_month' | 'warn_percent'>) {
    const warnPercent = Number(warn)
    if (!Number.isInteger(warnPercent) || warnPercent < 1 || warnPercent > 99) {
      setProblem(t('warnInvalid'))
      return
    }
    setProblem(null)
    setDone(false)
    setBudget.mutate(
      { ...change, ...(scope ? { category_id: scope } : {}), warn_percent: warnPercent, from_month: month },
      { onSuccess: () => setDone(true) },
    )
  }

  function submit(e: FormEvent) {
    e.preventDefault()
    const check = checkTakaInput(amount)
    if (!check.ok) {
      setProblem(t(check.reason === 'format' ? 'amountFormat' : 'amountRange'))
      return
    }
    send({ amount: check.text })
  }

  const serverMessage =
    setBudget.error instanceof ApiError && (setBudget.error.status === 422 || setBudget.error.status === 404)
      ? setBudget.error.message
      : setBudget.error
        ? t(errorKey(setBudget.error))
        : null

  return (
    <section className="panel">
      <h2>{t('setBudget')}</h2>
      <p className="hint">{t('budgetFromMonth', { month: formatMonth(month, lang) })}</p>
      <form className="form-grid" onSubmit={submit}>
        <label htmlFor={`${id}-scope`}>
          {t('budgetFor')}
          <select id={`${id}-scope`} value={scope} onChange={(e) => setScope(e.target.value)}>
            <option value="">{t('budgetOverall')}</option>
            {expenseCategories.map((c) => (
              <option key={c.id} value={c.id}>
                {lang === 'bn' ? c.name_bn : c.name_en}
              </option>
            ))}
          </select>
        </label>
        <label htmlFor={`${id}-amount`}>
          {t('budgetAmount')}
          <input id={`${id}-amount`} inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} />
        </label>
        <label htmlFor={`${id}-warn`}>
          {t('warnAt')}
          <input id={`${id}-warn`} inputMode="numeric" value={warn} onChange={(e) => setWarn(e.target.value)} />
        </label>
        <button type="submit" className="primary" disabled={setBudget.isPending || !amount.trim()}>
          {t('save')}
        </button>
        <button type="button" disabled={setBudget.isPending} onClick={() => send({ remove: true })}>
          {t('removeBudget')}
        </button>
      </form>
      {problem ? <p className="field-error" role="alert">{problem}</p> : null}
      {serverMessage ? <p className="state-error" role="alert">{serverMessage}</p> : null}
      {done ? <p className="success" role="status">{t('saved')}</p> : null}
    </section>
  )
}
