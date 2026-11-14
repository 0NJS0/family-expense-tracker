import type { ReactNode } from 'react'
import { useI18n } from '../i18n/context'
import { errorKey } from '../lib/errors'
import { formatBasisPoints, formatPoisha } from '../lib/money'
import type { FormatOptions } from '../lib/money'

export function Loading() {
  const { t } = useI18n()
  return (
    <p className="state" role="status">
      {t('loading')}
    </p>
  )
}

export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const { t } = useI18n()
  return (
    <div className="state state-error" role="alert">
      <p>{t(errorKey(error))}</p>
      {onRetry ? (
        <button type="button" onClick={onRetry}>
          {t('retry')}
        </button>
      ) : null}
    </div>
  )
}

export function Empty({ children }: { children?: ReactNode }) {
  const { t } = useI18n()
  return <p className="state state-empty">{children ?? t('noData')}</p>
}

/** Render a query's loading/error state, or its content. */
export function QueryView<T>({
  query,
  children,
}: {
  query: { data: T | undefined; error: unknown; isPending: boolean; refetch: () => unknown }
  children: (data: T) => ReactNode
}) {
  if (query.data !== undefined) return <>{children(query.data)}</>
  if (query.error) return <ErrorState error={query.error} onRetry={() => void query.refetch()} />
  return <Loading />
}

export function Money({ poisha, ...options }: { poisha: number } & FormatOptions) {
  const { lang } = useI18n()
  return (
    <span className={`money${poisha < 0 ? ' negative' : ''}`}>
      {formatPoisha(poisha, lang, options)}
    </span>
  )
}

export function Change({ poisha, basisPoints }: { poisha: number; basisPoints: number | null }) {
  const { lang } = useI18n()
  // Direction is shown with an arrow and sign, never colour alone.
  const arrow = poisha > 0 ? '▲' : poisha < 0 ? '▼' : '■'
  return (
    <span className="change">
      <span aria-hidden="true">{arrow} </span>
      {formatPoisha(poisha, lang, { signed: true })}
      {basisPoints !== null ? ` (${formatBasisPoints(basisPoints, lang)})` : ''}
    </span>
  )
}
