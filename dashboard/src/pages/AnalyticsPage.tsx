import { useId, useState } from 'react'
import { ApiError } from '../api/client'
import { useMembers, useSummary, useTimeseries } from '../api/queries'
import type { CompareTo, Granularity, Summary } from '../api/types'
import { CashFlowChart, CategoryChart } from '../charts/Charts'
import { Empty, ErrorState, Loading, Money, QueryView } from '../components/common'
import { usePeriod } from '../components/period'
import { PeriodPicker } from '../components/PeriodPicker'
import { useI18n } from '../i18n/context'
import { formatDate } from '../lib/dates'

const GRANULARITIES: Granularity[] = ['day', 'week', 'month', 'year']

export function AnalyticsPage() {
  const { t } = useI18n()
  const id = useId()
  const { choice, setChoice, range } = usePeriod({ preset: 'this_year' })
  const [granularity, setGranularity] = useState<Granularity>('month')
  const [memberId, setMemberId] = useState<string>('')
  const [compareTo, setCompareTo] = useState<CompareTo>('previous_year')
  const members = useMembers()
  const member = memberId || undefined
  const series = useTimeseries(range, granularity, member)
  const summary = useSummary(range, member, compareTo)

  return (
    <div className="page">
      <div className="toolbar">
        <h1>{t('navAnalytics')}</h1>
        <PeriodPicker choice={choice} range={range} onChange={setChoice} />
        <label htmlFor={`${id}-gran`}>{t('granularity')}</label>
        <select
          id={`${id}-gran`}
          value={granularity}
          onChange={(e) => setGranularity(e.target.value as Granularity)}
        >
          {GRANULARITIES.map((g) => (
            <option key={g} value={g}>
              {t(g)}
            </option>
          ))}
        </select>
        <label htmlFor={`${id}-compare`}>{t('compareWith')}</label>
        <select
          id={`${id}-compare`}
          value={compareTo}
          onChange={(e) => setCompareTo(e.target.value as CompareTo)}
        >
          <option value="previous_period">{t('previous_period')}</option>
          <option value="previous_year">{t('previous_year')}</option>
        </select>
        <label htmlFor={`${id}-member`}>{t('person')}</label>
        <select id={`${id}-member`} value={memberId} onChange={(e) => setMemberId(e.target.value)}>
          <option value="">{t('everyone')}</option>
          {(members.data ?? []).map((m) => (
            <option key={m.user_id} value={m.user_id}>
              {m.display_name}
            </option>
          ))}
        </select>
      </div>

      <section className="panel">
        <h2>{t('incomeVsExpense')}</h2>
        {series.data ? (
          <CashFlowChart
            buckets={series.data.buckets}
            granularity={series.data.granularity}
            title={t('incomeVsExpense')}
          />
        ) : series.error instanceof ApiError && series.error.status === 422 ? (
          <p className="state state-error" role="alert">
            {t('tooManyBuckets')}
          </p>
        ) : series.error ? (
          <ErrorState error={series.error} onRetry={() => void series.refetch()} />
        ) : (
          <Loading />
        )}
      </section>

      <div className="grid-2">
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
        <section className="panel">
          <h2>{t('categoryChanges')}</h2>
          <QueryView query={summary}>{(data) => <CategoryChanges summary={data} />}</QueryView>
        </section>
      </div>
    </div>
  )
}

function CategoryChanges({ summary }: { summary: Summary }) {
  const { t, lang } = useI18n()
  const c = summary.comparison
  if (c.category_changes.length === 0) return <Empty />
  return (
    <table className="data-table">
      <caption>
        {formatDate(summary.date_from, lang)} – {formatDate(summary.date_to, lang)} ·{' '}
        {t('previous')}: {formatDate(c.date_from, lang)} – {formatDate(c.date_to, lang)}
      </caption>
      <thead>
        <tr>
          <th scope="col">{t('category')}</th>
          <th scope="col">{t('type')}</th>
          <th scope="col" className="num">{t('current')}</th>
          <th scope="col" className="num">{t('previous')}</th>
          <th scope="col" className="num">{t('change')}</th>
        </tr>
      </thead>
      <tbody>
        {c.category_changes.map((row) => (
          <tr key={row.category_id}>
            <th scope="row">{lang === 'bn' ? row.name_bn : row.name_en}</th>
            <td>{t(row.kind === 'income' ? 'typeIncome' : 'typeExpense')}</td>
            <td className="num"><Money poisha={row.current_poisha} /></td>
            <td className="num"><Money poisha={row.previous_poisha} /></td>
            <td className="num"><Money poisha={row.change_poisha} signed /></td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
