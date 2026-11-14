import { useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import type { CategoryTotal, Granularity, TimeseriesBucket } from '../api/types'
import { Money } from '../components/common'
import { useColorScheme } from '../hooks/useColorScheme'
import { useI18n } from '../i18n/context'
import { toLocalDigits } from '../lib/money'
import { EChart } from './EChart'
import { bucketLabel, categoryBars, categoryOption, timeseriesOption } from './options'

/** Toggle that reveals the exact figures behind a chart (accessibility relief). */
function TableToggle({ children }: { children: ReactNode }) {
  const { t } = useI18n()
  const [open, setOpen] = useState(false)
  return (
    <div className="chart-table">
      <button type="button" className="link" aria-expanded={open} onClick={() => setOpen(!open)}>
        {open ? t('hideTable') : t('showTable')}
      </button>
      {open ? children : null}
    </div>
  )
}

export function CashFlowChart({
  buckets,
  granularity,
  title,
}: {
  buckets: TimeseriesBucket[]
  granularity: Granularity
  title: string
}) {
  const { t, lang } = useI18n()
  const scheme = useColorScheme()
  const option = useMemo(
    () =>
      timeseriesOption(
        buckets,
        granularity,
        lang,
        { income: t('income'), expense: t('expense'), net: t('net'), partial: t('partialPeriod') },
        scheme,
      ),
    [buckets, granularity, lang, scheme, t],
  )
  return (
    <>
      <EChart option={option} height={300} label={title} />
      <TableToggle>
        <table className="data-table">
          <caption className="visually-hidden">{title}</caption>
          <thead>
            <tr>
              <th scope="col">{t('period')}</th>
              <th scope="col" className="num">{t('income')}</th>
              <th scope="col" className="num">{t('expense')}</th>
              <th scope="col" className="num">{t('net')}</th>
            </tr>
          </thead>
          <tbody>
            {buckets.map((b) => (
              <tr key={b.start}>
                <th scope="row">
                  {bucketLabel(b.start, granularity, lang)}
                  {b.is_partial ? ' *' : ''}
                </th>
                <td className="num"><Money poisha={b.income_poisha} /></td>
                <td className="num"><Money poisha={b.expense_poisha} /></td>
                <td className="num"><Money poisha={b.net_poisha} /></td>
              </tr>
            ))}
          </tbody>
        </table>
        {buckets.some((b) => b.is_partial) ? (
          <p className="hint">* {t('partialPeriod')}</p>
        ) : null}
      </TableToggle>
    </>
  )
}

export function CategoryChart({ items, title }: { items: CategoryTotal[]; title: string }) {
  const { t, lang } = useI18n()
  const scheme = useColorScheme()
  const bars = useMemo(() => categoryBars(items, lang, t('other')), [items, lang, t])
  const option = useMemo(() => categoryOption(bars, lang, scheme), [bars, lang, scheme])
  return (
    <>
      <EChart option={option} height={Math.max(120, bars.length * 36)} label={title} />
      <TableToggle>
        <table className="data-table">
          <caption className="visually-hidden">{title}</caption>
          <thead>
            <tr>
              <th scope="col">{t('category')}</th>
              <th scope="col" className="num">{t('expense')}</th>
              <th scope="col" className="num">{t('transactionsCount')}</th>
            </tr>
          </thead>
          <tbody>
            {[...items]
              .sort((a, b) => b.total_poisha - a.total_poisha)
              .map((c) => (
                <tr key={c.category_id}>
                  <th scope="row">{lang === 'bn' ? c.name_bn : c.name_en}</th>
                  <td className="num"><Money poisha={c.total_poisha} /></td>
                  <td className="num">{toLocalDigits(String(c.transaction_count), lang)}</td>
                </tr>
              ))}
          </tbody>
        </table>
      </TableToggle>
    </>
  )
}
