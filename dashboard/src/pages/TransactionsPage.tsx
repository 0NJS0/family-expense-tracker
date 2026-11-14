import { useEffect, useId, useMemo, useState } from 'react'
import { PAGE_SIZE, useTransactions } from '../api/queries'
import type { TransactionFilters, TransactionType } from '../api/types'
import { useSession } from '../auth/session'
import { Empty, ErrorState, QueryView } from '../components/common'
import { useLookups } from '../components/lookups'
import { usePeriod } from '../components/period'
import { PeriodPicker } from '../components/PeriodPicker'
import { TransactionDrawer } from '../components/TransactionDrawer'
import { TransactionTable } from '../components/TransactionTable'
import { useI18n } from '../i18n/context'
import { toLocalDigits } from '../lib/money'

export function TransactionsPage() {
  const { t, lang } = useI18n()
  const id = useId()
  const lookups = useLookups()
  const { choice, setChoice, range } = usePeriod()
  const [type, setType] = useState<TransactionType | ''>('')
  const [accountId, setAccountId] = useState('')
  const [categoryId, setCategoryId] = useState('')
  const [memberId, setMemberId] = useState('')
  const [search, setSearch] = useState('')
  const [debouncedSearch, setDebouncedSearch] = useState('')
  const [includeVoided, setIncludeVoided] = useState(false)
  const [openId, setOpenId] = useState<string | null>(null)

  useEffect(() => {
    const timer = setTimeout(() => setDebouncedSearch(search.trim()), 300)
    return () => clearTimeout(timer)
  }, [search])

  const filters = useMemo(() => {
    const f: TransactionFilters = { date_from: range.from, date_to: range.to }
    if (type) f.type = type
    if (accountId) f.account_id = accountId
    if (categoryId) f.category_id = categoryId
    if (memberId) f.member_id = memberId
    if (debouncedSearch) f.q = debouncedSearch
    if (includeVoided) f.include_voided = true
    return f
  }, [range.from, range.to, type, accountId, categoryId, memberId, debouncedSearch, includeVoided])

  // The page belongs to one set of filters; any filter change returns to page 1.
  const filtersKey = JSON.stringify(filters)
  const [paging, setPaging] = useState({ key: filtersKey, page: 0 })
  const page = paging.key === filtersKey ? paging.page : 0
  const setPage = (next: number) => setPaging({ key: filtersKey, page: next })

  const query = useTransactions(filters, page)
  const total = query.data?.total ?? 0
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE))
  const categories = lookups.categories.filter((c) => !type || type === c.kind)

  return (
    <div className="page">
      <div className="toolbar">
        <h1>{t('navTransactions')}</h1>
        <ExportButton filters={filters} />
      </div>
      <form className="filters" onSubmit={(e) => e.preventDefault()}>
        <PeriodPicker choice={choice} range={range} onChange={setChoice} />
        <label htmlFor={`${id}-type`}>{t('type')}</label>
        <select
          id={`${id}-type`}
          value={type}
          onChange={(e) => {
            setType(e.target.value as TransactionType | '')
            setCategoryId('')
          }}
        >
          <option value="">{t('all')}</option>
          <option value="expense">{t('expense')}</option>
          <option value="income">{t('income')}</option>
          <option value="transfer">{t('transfer')}</option>
        </select>
        <label htmlFor={`${id}-account`}>{t('account')}</label>
        <select id={`${id}-account`} value={accountId} onChange={(e) => setAccountId(e.target.value)}>
          <option value="">{t('all')}</option>
          {lookups.accounts.map((a) => (
            <option key={a.id} value={a.id}>
              {a.name}
            </option>
          ))}
        </select>
        <label htmlFor={`${id}-category`}>{t('category')}</label>
        <select
          id={`${id}-category`}
          value={categoryId}
          disabled={type === 'transfer'}
          onChange={(e) => setCategoryId(e.target.value)}
        >
          <option value="">{t('all')}</option>
          {categories.map((c) => (
            <option key={c.id} value={c.id}>
              {lookups.categoryLabel(c)}
            </option>
          ))}
        </select>
        <label htmlFor={`${id}-member`}>{t('attributedTo')}</label>
        <select id={`${id}-member`} value={memberId} onChange={(e) => setMemberId(e.target.value)}>
          <option value="">{t('everyone')}</option>
          {lookups.members.map((m) => (
            <option key={m.user_id} value={m.user_id}>
              {m.display_name}
            </option>
          ))}
        </select>
        <label htmlFor={`${id}-q`}>{t('search')}</label>
        <input
          id={`${id}-q`}
          type="search"
          maxLength={100}
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
        <label className="checkbox">
          <input
            type="checkbox"
            checked={includeVoided}
            onChange={(e) => setIncludeVoided(e.target.checked)}
          />
          {t('includeVoided')}
        </label>
      </form>

      <section className="panel">
        <QueryView query={query}>
          {(data) =>
            data.items.length === 0 ? (
              <Empty />
            ) : (
              <>
                <p className="muted" role="status">
                  {t('showingCount', { count: toLocalDigits(String(data.total), lang) })}
                </p>
                <TransactionTable
                  items={data.items}
                  caption={t('navTransactions')}
                  onOpen={(tx) => setOpenId(tx.id)}
                />
                <nav className="pager" aria-label={t('page', { page: page + 1, pages })}>
                  <button type="button" disabled={page === 0} onClick={() => setPage(page - 1)}>
                    {t('previousPage')}
                  </button>
                  <span>
                    {t('page', {
                      page: toLocalDigits(String(page + 1), lang),
                      pages: toLocalDigits(String(pages), lang),
                    })}
                  </span>
                  <button
                    type="button"
                    disabled={page + 1 >= pages}
                    onClick={() => setPage(page + 1)}
                  >
                    {t('nextPage')}
                  </button>
                </nav>
              </>
            )
          }
        </QueryView>
      </section>
      {openId ? <TransactionDrawer transactionId={openId} onClose={() => setOpenId(null)} /> : null}
    </div>
  )
}

function ExportButton({ filters }: { filters: TransactionFilters }) {
  const { t } = useI18n()
  const { client } = useSession()
  const [pending, setPending] = useState<'csv' | 'xlsx' | null>(null)
  const [error, setError] = useState<unknown>(null)

  async function download(format: 'csv' | 'xlsx') {
    setPending(format)
    setError(null)
    try {
      const { blob, filename } = await client.download(`/transactions/export.${format}`, { ...filters })
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = filename
      anchor.click()
      URL.revokeObjectURL(url)
    } catch (err) {
      setError(err)
    } finally {
      setPending(null)
    }
  }

  return (
    <div className="export">
      <button type="button" onClick={() => void download('csv')} disabled={pending !== null}>
        {pending === 'csv' ? t('exporting') : t('exportCsv')}
      </button>
      <button type="button" onClick={() => void download('xlsx')} disabled={pending !== null}>
        {pending === 'xlsx' ? t('exporting') : t('exportXlsx')}
      </button>
      {error ? <ErrorState error={error} /> : null}
    </div>
  )
}
