import { useId, useState } from 'react'
import type { FormEvent } from 'react'
import { useAiReports, useRequestAiReport } from '../api/aiReports'
import { ApiError } from '../api/client'
import type { AiReport } from '../api/types'
import { useHousehold, useSession } from '../auth/session'
import { Empty, QueryView } from '../components/common'
import { useI18n } from '../i18n/context'
import { addMonths, formatMonth, todayIn } from '../lib/dates'
import { errorKey } from '../lib/errors'

/**
 * Monthly summaries written by the local AI on the homelab. Slow by nature:
 * a request is queued and the page checks back. Every number in a shown
 * summary was verified against the figures the backend computed.
 */
export function SummariesPage() {
  const { t } = useI18n()
  const { role } = useSession()
  const isAdmin = role === 'admin'
  const reports = useAiReports(isAdmin)
  return (
    <div className="page">
      <div className="toolbar">
        <h1>{t('navSummaries')}</h1>
      </div>
      {!isAdmin ? (
        <p className="state">{t('summariesAdminOnly')}</p>
      ) : (
        <div className="grid-2">
          <section className="panel">
            <h2>{t('summariesList')}</h2>
            <QueryView query={reports}>
              {(list) =>
                list.length === 0 ? (
                  <Empty>{t('noSummaries')}</Empty>
                ) : (
                  <ul className="plain-list">
                    {list.map((r) => (
                      <SummaryItem key={r.id} report={r} />
                    ))}
                  </ul>
                )
              }
            </QueryView>
          </section>
          <RequestPanel />
        </div>
      )}
    </div>
  )
}

function SummaryItem({ report }: { report: AiReport }) {
  const { t, lang } = useI18n()
  const month = formatMonth(report.period_start.slice(0, 7), lang)
  return (
    <li className="summary">
      <h3>
        {month} <span className="badge">{report.language === 'bn' ? 'বাংলা' : 'English'}</span>
      </h3>
      {report.status === 'done' && report.text ? (
        <>
          {/* Plain text: rendered as text, never as HTML. */}
          <p className="summary-text" lang={report.language}>
            {report.text}
          </p>
          <p className="hint">🤖 {t('aiWrittenChecked')}</p>
        </>
      ) : null}
      {report.status === 'queued' || report.status === 'running' ? (
        <p className="hint">⏳ {t('summaryWaiting')}</p>
      ) : null}
      {report.status === 'rejected' ? (
        <p className="state-error">⚠ {t('summaryRejected')}</p>
      ) : null}
      {report.status === 'failed' ? (
        <p className="state-error">
          ⚠ {t('summaryFailed')} {report.problem ? `(${report.problem})` : ''}
        </p>
      ) : null}
    </li>
  )
}

function RequestPanel() {
  const { t } = useI18n()
  const id = useId()
  const household = useHousehold()
  const thisMonth = todayIn(household.timezone).slice(0, 7)
  const [month, setMonth] = useState(addMonths(thisMonth, -1))
  const [language, setLanguage] = useState<'bn' | 'en'>('bn')
  const request = useRequestAiReport()
  const [done, setDone] = useState(false)

  function submit(e: FormEvent) {
    e.preventDefault()
    setDone(false)
    request.mutate({ month, language }, { onSuccess: () => setDone(true) })
  }

  return (
    <section className="panel">
      <h2>{t('requestSummary')}</h2>
      <p className="hint">{t('summaryExplain')}</p>
      <form className="form-grid" onSubmit={submit}>
        <label htmlFor={`${id}-month`}>
          {t('month')}
          <input id={`${id}-month`} type="month" max={thisMonth} required value={month} onChange={(e) => setMonth(e.target.value)} />
        </label>
        <label htmlFor={`${id}-lang`}>
          {t('summaryLanguage')}
          <select id={`${id}-lang`} value={language} onChange={(e) => setLanguage(e.target.value as 'bn' | 'en')}>
            <option value="bn">বাংলা</option>
            <option value="en">English</option>
          </select>
        </label>
        <button type="submit" className="primary" disabled={request.isPending || !month}>
          {t('requestSummaryButton')}
        </button>
      </form>
      {request.error ? (
        <p className="state-error" role="alert">
          {request.error instanceof ApiError && request.error.status === 422 ? request.error.message : t(errorKey(request.error))}
        </p>
      ) : null}
      {done ? <p className="success" role="status">{t('summaryQueued')}</p> : null}
    </section>
  )
}
