import type { Transaction } from '../api/types'
import { useHousehold } from '../auth/session'
import { useI18n } from '../i18n/context'
import type { MessageKey } from '../i18n/strings'
import { formatDateTime } from '../lib/dates'
import { Money } from './common'
import { useLookups } from './lookups'

const TYPE_KEY: Record<Transaction['type'], MessageKey> = {
  income: 'typeIncome',
  expense: 'typeExpense',
  transfer: 'transfer',
}

interface Props {
  items: Transaction[]
  onOpen?: (transaction: Transaction) => void
  caption: string
  /** Narrow layout: drop the account and note columns. */
  compact?: boolean
}

export function TransactionTable({ items, onOpen, caption, compact = false }: Props) {
  const { t, lang } = useI18n()
  const household = useHousehold()
  const lookups = useLookups()

  return (
    <div className="table-wrap">
      <table className="tx-table">
        <caption className="visually-hidden">{caption}</caption>
        <thead>
          <tr>
            <th scope="col">{t('date')}</th>
            <th scope="col">{t('type')}</th>
            <th scope="col">{t('category')}</th>
            {compact ? null : <th scope="col">{t('account')}</th>}
            <th scope="col">{t('attributedTo')}</th>
            {compact ? null : <th scope="col">{t('note')}</th>}
            <th scope="col" className="num">
              {t('amount')}
            </th>
          </tr>
        </thead>
        <tbody>
          {items.map((tx) => {
            const voided = tx.voided_at !== null
            return (
              <tr key={tx.id} className={voided ? 'voided' : undefined}>
                <td className="nowrap">
                  {onOpen ? (
                    <button type="button" className="link" onClick={() => onOpen(tx)}>
                      {formatDateTime(tx.occurred_at, lang, household.timezone)}
                    </button>
                  ) : (
                    formatDateTime(tx.occurred_at, lang, household.timezone)
                  )}
                </td>
                <td>
                  <span className={`badge badge-${tx.type}`}>{t(TYPE_KEY[tx.type])}</span>
                  {voided ? <span className="badge badge-voided">{t('voided')}</span> : null}
                </td>
                <td>{lookups.categoryName(tx.category_id)}</td>
                {compact ? null : (
                  <td>
                    {lookups.accountName(tx.account_id)}
                    {tx.destination_account_id
                      ? ` → ${lookups.accountName(tx.destination_account_id)}`
                      : ''}
                  </td>
                )}
                <td>{lookups.memberName(tx.attributed_user_id)}</td>
                {compact ? null : <td className="note">{tx.note ?? ''}</td>}
                <td className="num">
                  <Money poisha={tx.type === 'expense' ? -tx.amount_poisha : tx.amount_poisha} />
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
