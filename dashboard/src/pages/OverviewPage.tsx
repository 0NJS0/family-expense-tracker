import { useState } from 'react'
import type React from 'react'
import { Link } from 'react-router'
import { useBalances, useSummary, useTimeseries, useTopExpenses, useTransactions } from '../api/queries'
import type { Comparison, MemberTotal, Summary } from '../api/types'
import { useHousehold } from '../auth/session'
import { CashFlowChart, CategoryChart } from '../charts/Charts'
import { Change, Empty, Money, QueryView } from '../components/common'
import { usePeriod } from '../components/period'
import { PeriodPicker } from '../components/PeriodPicker'
import { TransactionDrawer } from '../components/TransactionDrawer'
import { TransactionTable } from '../components/TransactionTable'
import { IconArrowDown, IconArrowUp, IconSwap, IconWallet } from '../components/icons'
import { useLookups } from '../components/lookups'
import { useI18n } from '../i18n/context'
import { formatDate, formatDateTime, overviewGranularity } from '../lib/dates'
import { formatPoisha, toLocalDigits } from '../lib/money'

export function OverviewPage() {
  const { t, lang } = useI18n()
  const household = useHousehold()
  const { choice, setChoice, range } = usePeriod()
  // Year to date is compared with the same dates last year; shorter periods
  // with the period just before them.
  const summary = useSummary(
    range,
    undefined,
    choice.preset === 'this_year' ? 'previous_year' : 'previous_period',
  )
  const granularity = overviewGranularity(range)
  const series = useTimeseries(range, granularity)
  const [openId, setOpenId] = useState<string | null>(null)

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>{t('navOverview')}</h1>
          <p className="subtitle">
            {household.name} · {formatDate(range.from, lang)} – {formatDate(range.to, lang)}
          </p>
        </div>
        <PeriodPicker choice={choice} range={range} onChange={setChoice} />
      </div>

      <QueryView query={summary}>{(data) => <Headline summary={data} />}</QueryView>

      <div className="grid-2">
        <section className="panel">
          <h2>{t('incomeVsExpense')}</h2>
          <QueryView query={series}>
            {(data) =>
              data.buckets.every((b) => b.income_poisha === 0 && b.expense_poisha === 0) ? (
                <Empty />
              ) : (
                <CashFlowChart
                  buckets={data.buckets}
                  granularity={granularity}
                  title={t('incomeVsExpense')}
                />
              )
            }
          </QueryView>
        </section>
        <section className="panel">
          <h2>{t('spendingByCategory')}</h2>
          <QueryView query={summary}>
            {(data) =>
              data.expense_by_category.length === 0 ? (
                <Empty />
              ) : (
                <CategoryChart items={data.expense_by_category} title={t('spendingByCategory')} />
              )
            }
          </QueryView>
        </section>
      </div>

      <div className="grid-2">
        <section className="panel">
          <h2>{t('biggestExpenses')}</h2>
          <TopExpenses from={range.from} to={range.to} onOpen={setOpenId} />
        </section>
        <section className="panel">
          <h2>{t('whoSpent')}</h2>
          <QueryView query={summary}>
            {(data) => <WhoSpent people={data.by_member} total={data.expense_poisha} />}
          </QueryView>
        </section>
      </div>

      <div className="grid-2">
        <Balances />
        <Recent onOpen={setOpenId} />
      </div>
      {openId ? <TransactionDrawer transactionId={openId} onClose={() => setOpenId(null)} /> : null}
    </div>
  )
}

function Headline({ summary }: { summary: Summary }) {
  const { t, lang } = useI18n()
  const c = summary.comparison
  const previous = `${formatDate(c.date_from, lang)} – ${formatDate(c.date_to, lang)}`
  return (
    <>
      {summary.is_partial ? (
        <p className="notice" role="note">
          {t('partialPeriod')}
        </p>
      ) : null}
      <div className="stats">
        <Stat
          variant="income"
          icon={<IconArrowDown />}
          label={t('income')}
          poisha={summary.income_poisha}
          change={c.income_change_poisha}
          bp={c.income_change_basis_points}
          comparison={c}
          previous={previous}
        />
        <Stat
          variant="expense"
          icon={<IconArrowUp />}
          label={t('expense')}
          poisha={summary.expense_poisha}
          change={c.expense_change_poisha}
          bp={c.expense_change_basis_points}
          comparison={c}
          previous={previous}
        />
        <Stat
          variant="net"
          icon={<IconWallet />}
          label={t('net')}
          poisha={summary.net_poisha}
          change={c.net_change_poisha}
          bp={null}
          comparison={c}
          previous={previous}
        />
        <div className="stat">
          <span className="stat-top">
            <span className="stat-label">{t('transfers')}</span>
            <span className="stat-icon">
              <IconSwap />
            </span>
          </span>
          <span className="stat-value">
            <Money poisha={summary.transfer_poisha} />
          </span>
        </div>
      </div>
    </>
  )
}

function Stat(props: {
  variant: 'income' | 'expense' | 'net'
  icon: React.ReactNode
  label: string
  poisha: number
  change: number
  bp: number | null
  comparison: Comparison
  previous: string
}) {
  const { t } = useI18n()
  const hasPrevious = props.comparison.transaction_count > 0
  return (
    <div className={`stat stat-${props.variant}`}>
      <span className="stat-top">
        <span className="stat-label">{props.label}</span>
        <span className="stat-icon">{props.icon}</span>
      </span>
      <span className="stat-value">
        <Money poisha={props.poisha} />
      </span>
      <span className="stat-change">
        {hasPrevious ? (
          <>
            <Change poisha={props.change} basisPoints={props.bp} />{' '}
            <span className="muted">{t('vsPrevious', { period: props.previous })}</span>
          </>
        ) : (
          <span className="muted">{t('noPrevious')}</span>
        )}
      </span>
    </div>
  )
}

function Balances() {
  const { t } = useI18n()
  const balances = useBalances()
  return (
    <section className="panel">
      <h2>{t('balances')}</h2>
      <QueryView query={balances}>
        {(data) =>
          data.accounts.length === 0 ? (
            <Empty>{t('noAccounts')}</Empty>
          ) : (
            <>
              <table className="data-table">
                <caption className="visually-hidden">{t('balances')}</caption>
                <tbody>
                  {data.accounts.map((a) => (
                    <tr key={a.account_id}>
                      <th scope="row">
                        {a.name}
                        {a.is_shared ? <span className="badge">{t('shared')}</span> : null}
                      </th>
                      <td className="num">
                        <Money poisha={a.balance_poisha} />
                      </td>
                    </tr>
                  ))}
                </tbody>
                <tfoot>
                  <tr>
                    <th scope="row">{t('totalBalance')}</th>
                    <td className="num">
                      <Money poisha={data.total_poisha} />
                    </td>
                  </tr>
                </tfoot>
              </table>
              <p className="hint">{t('balancesNote')}</p>
            </>
          )
        }
      </QueryView>
    </section>
  )
}

function Recent({ onOpen }: { onOpen: (id: string) => void }) {
  const { t } = useI18n()
  const recent = useTransactions({}, 0)
  return (
    <section className="panel">
      <div className="panel-head">
        <h2>{t('recent')}</h2>
        <Link to="/transactions">{t('viewAll')}</Link>
      </div>
      <QueryView query={recent}>
        {(data) =>
          data.items.length === 0 ? (
            <Empty />
          ) : (
            <TransactionTable
              items={data.items.slice(0, 8)}
              caption={t('recent')}
              compact
              onOpen={(tx) => onOpen(tx.id)}
            />
          )
        }
      </QueryView>
    </section>
  )
}

function TopExpenses({ from, to, onOpen }: { from: string; to: string; onOpen: (id: string) => void }) {
  const { t, lang } = useI18n()
  const household = useHousehold()
  const lookups = useLookups()
  const top = useTopExpenses({ from, to })
  return (
    <QueryView query={top}>
      {(data) =>
        data.items.length === 0 ? (
          <Empty />
        ) : (
          <ol className="bar-list">
            {data.items.map((tx) => (
              <li key={tx.id}>
                <div className="bar-row-top">
                  <button type="button" className="link" onClick={() => onOpen(tx.id)}>
                    {lookups.categoryName(tx.category_id) || t('expense')}
                  </button>
                  <Money poisha={tx.amount_poisha} />
                </div>
                <span className="hint">
                  {formatDateTime(tx.occurred_at, lang, household.timezone)}
                  {tx.note ? ` · ${tx.note}` : ''}
                </span>
              </li>
            ))}
          </ol>
        )
      }
    </QueryView>
  )
}

/** Share of the household's spending per person; widths are display-only. */
function WhoSpent({ people, total }: { people: MemberTotal[]; total: number }) {
  const { t, lang } = useI18n()
  const spenders = people.filter((p) => p.expense_poisha > 0)
  if (spenders.length === 0 || total <= 0) return <Empty />
  return (
    <ul className="bar-list">
      {spenders.map((p) => {
        const tenths = Math.round((p.expense_poisha * 1000) / total)
        const share = `${Math.floor(tenths / 10)}.${tenths % 10}%`
        return (
          <li key={p.user_id}>
            <div className="bar-row-top">
              <strong>{p.display_name}</strong>
              <span>{formatPoisha(p.expense_poisha, lang)}</span>
            </div>
            <div
              className="bar-track"
              role="img"
              aria-label={t('share', { share: toLocalDigits(share, lang) })}
            >
              <div className="bar-fill" style={{ width: `${Math.max(2, tenths / 10)}%` }} />
            </div>
            <span className="hint">{t('share', { share: toLocalDigits(share, lang) })}</span>
          </li>
        )
      })}
    </ul>
  )
}
