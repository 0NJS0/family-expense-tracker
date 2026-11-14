import { useId, useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError } from '../api/client'
import {
  useCreateAccount,
  useCreateCategory,
  useCorrectOpeningBalance,
  useCreateInvitation,
  useSetOpeningBalance,
  useUpdateAccount,
  useUpdateCategory,
  useUpdateMember,
} from '../api/admin'
import type { InvitationCreated, NewAccount } from '../api/admin'
import { useAccounts, useBalances, useCategories, useMembers } from '../api/queries'
import type { Account, AccountKind, Category, CategoryKind, Member, Role } from '../api/types'
import { useHousehold, useSession } from '../auth/session'
import { Empty, Money, QueryView } from '../components/common'
import { useI18n } from '../i18n/context'
import { todayIn } from '../lib/dates'
import { errorKey } from '../lib/errors'
import { parseSignedTaka } from '../lib/money'

type Tab = 'members' | 'accounts' | 'categories'
const ROLES: Role[] = ['admin', 'member', 'viewer']
const KINDS: AccountKind[] = ['cash', 'bank', 'mobile_wallet', 'other']

export function AdminPage() {
  const { t } = useI18n()
  const { role } = useSession()
  const [tab, setTab] = useState<Tab>('members')
  const isAdmin = role === 'admin'
  const tabs: [Tab, string][] = [
    ['members', t('membersTitle')],
    ['accounts', t('accountsTitle')],
    ['categories', t('categoriesTitle')],
  ]

  return (
    <div className="page">
      <div className="page-head">
        <h1>{t('navAdmin')}</h1>
      </div>
      {!isAdmin ? <p className="notice">{t('adminOnly')}</p> : null}
      <div className="tabs" role="tablist" aria-label={t('navAdmin')}>
        {tabs.map(([id, label]) => (
          <button
            key={id}
            type="button"
            role="tab"
            aria-selected={tab === id}
            onClick={() => setTab(id)}
          >
            {label}
          </button>
        ))}
      </div>
      <div role="tabpanel">
        {tab === 'members' ? <MembersTab isAdmin={isAdmin} /> : null}
        {tab === 'accounts' ? <AccountsTab isAdmin={isAdmin} /> : null}
        {tab === 'categories' ? <CategoriesTab isAdmin={isAdmin} /> : null}
      </div>
    </div>
  )
}

/** Error from a mutation, with the specific messages the admin needs. */
function MutationMessage({ error }: { error: unknown }) {
  const { t } = useI18n()
  if (!error) return null
  let message = t(errorKey(error))
  if (error instanceof ApiError) {
    if (error.status === 409 && /admin/i.test(error.message)) message = t('lastAdmin')
    else if (error.status === 409 && /already exists/i.test(error.message)) message = t('nameExists')
    // Other conflicts (stale values, dates before transactions) explain themselves.
    else if (error.status === 409) message = error.message
    else if (error.status === 422) message = error.message
  }
  return (
    <p className="state-error" role="alert">
      {message}
    </p>
  )
}

// --- Members -----------------------------------------------------------------

function MembersTab({ isAdmin }: { isAdmin: boolean }) {
  const { t } = useI18n()
  const members = useMembers()
  const update = useUpdateMember()
  const { me } = useSession()

  return (
    <div className="grid-2">
      <section className="panel">
        <h2>{t('membersTitle')}</h2>
        <QueryView query={members}>
          {(list) => (
            <div className="table-wrap">
              <table>
                <caption className="visually-hidden">{t('membersTitle')}</caption>
                <thead>
                  <tr>
                    <th scope="col">{t('displayName')}</th>
                    <th scope="col">{t('role')}</th>
                    <th scope="col">{t('status')}</th>
                    {isAdmin ? <th scope="col"><span className="visually-hidden">{t('edit')}</span></th> : null}
                  </tr>
                </thead>
                <tbody>
                  {list.map((m) => (
                    <MemberRow
                      key={m.user_id}
                      member={m}
                      isAdmin={isAdmin}
                      isMe={m.user_id === me?.user.id}
                      onChange={(change) => update.mutate({ userId: m.user_id, ...change })}
                    />
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </QueryView>
        <MutationMessage error={update.error} />
      </section>
      {isAdmin ? <InvitePanel /> : null}
    </div>
  )
}

function MemberRow({
  member,
  isAdmin,
  isMe,
  onChange,
}: {
  member: Member
  isAdmin: boolean
  isMe: boolean
  onChange: (change: { role?: Role; is_active?: boolean }) => void
}) {
  const { t } = useI18n()
  return (
    <tr>
      <th scope="row">
        {member.display_name}
        <div className="hint">@{member.username}</div>
      </th>
      <td>
        {isAdmin ? (
          <select
            aria-label={`${t('role')}: ${member.display_name}`}
            value={member.role}
            onChange={(e) => onChange({ role: e.target.value as Role })}
          >
            {ROLES.map((r) => (
              <option key={r} value={r}>
                {t(`role_${r}`)}
              </option>
            ))}
          </select>
        ) : (
          t(`role_${member.role}`)
        )}
      </td>
      <td>
        <span className={`badge ${member.is_active ? 'badge-ok' : ''}`}>
          {member.is_active ? t('active') : t('inactive')}
        </span>
      </td>
      {isAdmin ? (
        <td className="row-actions">
          {isMe ? null : (
            <button type="button" onClick={() => onChange({ is_active: !member.is_active })}>
              {member.is_active ? t('deactivate') : t('reactivate')}
            </button>
          )}
        </td>
      ) : null}
    </tr>
  )
}

function InvitePanel() {
  const { t } = useI18n()
  const id = useId()
  const create = useCreateInvitation()
  const [name, setName] = useState('')
  const [role, setRole] = useState<Role>('member')
  const [created, setCreated] = useState<InvitationCreated | null>(null)
  const [copied, setCopied] = useState(false)

  function submit(e: FormEvent) {
    e.preventDefault()
    if (!name.trim()) return
    create.mutate(
      { display_name: name.trim(), role },
      {
        onSuccess: (inv) => {
          setCreated(inv)
          setCopied(false)
          setName('')
        },
      },
    )
  }

  const link = created ? `${window.location.origin}/accept?token=${encodeURIComponent(created.token)}` : ''

  return (
    <section className="panel">
      <h2>{t('invite')}</h2>
      <form className="form-grid" onSubmit={submit}>
        <label htmlFor={`${id}-name`}>
          {t('displayName')}
          <input id={`${id}-name`} value={name} maxLength={100} onChange={(e) => setName(e.target.value)} required />
        </label>
        <label htmlFor={`${id}-role`}>
          {t('role')}
          <select id={`${id}-role`} value={role} onChange={(e) => setRole(e.target.value as Role)}>
            {ROLES.map((r) => (
              <option key={r} value={r}>
                {t(`role_${r}`)}
              </option>
            ))}
          </select>
        </label>
        <button type="submit" className="primary" disabled={create.isPending || !name.trim()}>
          {t('createInvite')}
        </button>
      </form>
      <MutationMessage error={create.error} />
      {created ? (
        <div className="success" role="status" style={{ marginTop: '1rem' }}>
          <p style={{ marginTop: 0 }}>
            <strong>{created.display_name}</strong> — {t('inviteHelp')}
          </p>
          <div className="copy-row">
            <input readOnly aria-label={t('inviteLink')} value={link} onFocus={(e) => e.target.select()} />
            <button
              type="button"
              onClick={() => {
                void navigator.clipboard?.writeText(link).then(() => setCopied(true))
              }}
            >
              {copied ? t('copied') : t('copy')}
            </button>
          </div>
        </div>
      ) : null}
    </section>
  )
}

// --- Accounts ----------------------------------------------------------------

function AccountsTab({ isAdmin }: { isAdmin: boolean }) {
  const { t } = useI18n()
  const accounts = useAccounts()
  const balances = useBalances()
  const update = useUpdateAccount()
  const balanceById = new Map((balances.data?.accounts ?? []).map((b) => [b.account_id, b.balance_poisha]))

  return (
    <div className="grid-2">
      <section className="panel">
        <h2>{t('accountsTitle')}</h2>
        <QueryView query={accounts}>
          {(list) =>
            list.length === 0 ? (
              <Empty>{t('noAccounts')}</Empty>
            ) : (
              <div className="table-wrap">
                <table>
                  <caption className="visually-hidden">{t('accountsTitle')}</caption>
                  <thead>
                    <tr>
                      <th scope="col">{t('accountName')}</th>
                      <th scope="col">{t('kind')}</th>
                      <th scope="col" className="num">{t('balance')}</th>
                      {isAdmin ? <th scope="col"><span className="visually-hidden">{t('edit')}</span></th> : null}
                    </tr>
                  </thead>
                  <tbody>
                    {list.map((a) => (
                      <AccountRow
                        key={a.id}
                        account={a}
                        balance={balanceById.get(a.id)}
                        isAdmin={isAdmin}
                        onRename={(name) => update.mutate({ id: a.id, name })}
                        onArchive={() => update.mutate({ id: a.id, is_archived: !a.is_archived })}
                        onPrivacy={() => update.mutate({ id: a.id, is_private: !a.is_private })}
                      />
                    ))}
                  </tbody>
                </table>
              </div>
            )
          }
        </QueryView>
        <MutationMessage error={update.error} />
        <p className="hint">{t('balancesNote')}</p>
      </section>
      {isAdmin ? <NewAccountPanel /> : null}
    </div>
  )
}

function AccountRow({
  account,
  balance,
  isAdmin,
  onRename,
  onArchive,
  onPrivacy,
}: {
  account: Account
  balance: number | undefined
  isAdmin: boolean
  onRename: (name: string) => void
  onArchive: () => void
  onPrivacy: () => void
}) {
  const { t } = useI18n()
  const [editing, setEditing] = useState(false)
  const [name, setName] = useState(account.name)
  const [opening, setOpening] = useState(false)
  return (
    <>
      <tr className={account.is_archived ? 'voided' : undefined}>
        <th scope="row">
          {editing ? (
            <input
              className="inline-input"
              aria-label={t('accountName')}
              value={name}
              onChange={(e) => setName(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && name.trim()) {
                  onRename(name.trim())
                  setEditing(false)
                }
              }}
            />
          ) : (
            account.name
          )}
          {account.is_shared ? <span className="badge">{t('shared')}</span> : null}
          {account.is_private ? <span className="badge">🔒 {t('privateAccount')}</span> : null}
          {account.is_archived ? <span className="badge">{t('archived')}</span> : null}
          {!account.opening_balance ? <div className="hint">{t('openingBalance')}: {t('noOpening')}</div> : null}
        </th>
        <td>{t(`kind_${account.kind}`)}</td>
        <td className="num">{balance === undefined ? '—' : <Money poisha={balance} />}</td>
        {isAdmin ? (
          <td className="row-actions">
            {editing ? (
              <button
                type="button"
                className="primary"
                disabled={!name.trim()}
                onClick={() => {
                  onRename(name.trim())
                  setEditing(false)
                }}
              >
                {t('save')}
              </button>
            ) : (
              <button type="button" onClick={() => setEditing(true)}>
                {t('rename')}
              </button>
            )}
            {!account.opening_balance && !account.is_archived ? (
              <button type="button" onClick={() => setOpening(!opening)}>
                {t('setOpening')}
              </button>
            ) : null}
            {account.opening_balance ? (
              <button type="button" onClick={() => setOpening(!opening)}>
                {t('correctOpening')}
              </button>
            ) : null}
            {account.owner_user_id ? (
              <button type="button" onClick={onPrivacy}>
                {account.is_private ? t('makeVisible') : t('makePrivate')}
              </button>
            ) : null}
            <button type="button" onClick={onArchive}>
              {account.is_archived ? t('unarchive') : t('archive')}
            </button>
          </td>
        ) : null}
      </tr>
      {opening ? (
        <tr>
          <td colSpan={4}>
            {account.opening_balance ? (
              <CorrectOpeningForm
                accountId={account.id}
                current={account.opening_balance}
                onDone={() => setOpening(false)}
              />
            ) : (
              <OpeningForm accountId={account.id} onDone={() => setOpening(false)} />
            )}
          </td>
        </tr>
      ) : null}
    </>
  )
}

function CorrectOpeningForm({
  accountId,
  current,
  onDone,
}: {
  accountId: string
  current: { amount_poisha: number; as_of_date: string }
  onDone: () => void
}) {
  const { t } = useI18n()
  const id = useId()
  const correct = useCorrectOpeningBalance()
  const [amount, setAmount] = useState(signedInputText(current.amount_poisha))
  const [date, setDate] = useState(current.as_of_date)
  const [reason, setReason] = useState('')
  const [invalid, setInvalid] = useState(false)

  function submit(e: FormEvent) {
    e.preventDefault()
    const poisha = parseSignedTaka(amount)
    if (poisha === null) {
      setInvalid(true)
      return
    }
    setInvalid(false)
    correct.mutate(
      {
        id: accountId,
        previous_amount_poisha: current.amount_poisha,
        previous_as_of_date: current.as_of_date,
        amount_poisha: poisha,
        as_of_date: date,
        reason: reason.trim(),
      },
      { onSuccess: onDone },
    )
  }

  return (
    <form className="form-grid" onSubmit={submit}>
      <p className="hint">
        {t('correctOpeningNow')}: <Money poisha={current.amount_poisha} /> ({current.as_of_date})
      </p>
      <label htmlFor={`${id}-amount`}>
        {t('openingBalance')}
        <input id={`${id}-amount`} inputMode="decimal" value={amount} aria-invalid={invalid} onChange={(e) => setAmount(e.target.value)} />
      </label>
      <label htmlFor={`${id}-date`}>
        {t('openingDate')}
        <input id={`${id}-date`} type="date" value={date} onChange={(e) => setDate(e.target.value)} />
      </label>
      <label htmlFor={`${id}-reason`}>
        {t('correctionReason')}
        <input id={`${id}-reason`} required maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
      </label>
      <button type="submit" className="primary" disabled={correct.isPending || !reason.trim()}>
        {t('save')}
      </button>
      {invalid ? <span className="field-error">{t('openingInvalid')}</span> : null}
      <MutationMessage error={correct.error} />
    </form>
  )
}

/** "-1250.75" for a signed poisha amount (Latin digits, no grouping). */
function signedInputText(poisha: number): string {
  const sign = poisha < 0 ? '-' : ''
  const abs = Math.abs(poisha)
  return `${sign}${Math.floor(abs / 100)}.${String(abs % 100).padStart(2, '0')}`
}

function OpeningForm({ accountId, onDone }: { accountId: string; onDone: () => void }) {
  const { t } = useI18n()
  const household = useHousehold()
  const id = useId()
  const setOpening = useSetOpeningBalance()
  const [amount, setAmount] = useState('')
  const [date, setDate] = useState(todayIn(household.timezone))
  const [invalid, setInvalid] = useState(false)

  function submit(e: FormEvent) {
    e.preventDefault()
    const poisha = parseSignedTaka(amount)
    if (poisha === null) {
      setInvalid(true)
      return
    }
    setInvalid(false)
    setOpening.mutate({ id: accountId, amount_poisha: poisha, as_of_date: date }, { onSuccess: onDone })
  }

  return (
    <form className="form-grid" onSubmit={submit}>
      <label htmlFor={`${id}-amount`}>
        {t('openingBalance')}
        <input id={`${id}-amount`} inputMode="decimal" value={amount} aria-invalid={invalid} onChange={(e) => setAmount(e.target.value)} />
      </label>
      <label htmlFor={`${id}-date`}>
        {t('openingDate')}
        <input id={`${id}-date`} type="date" value={date} onChange={(e) => setDate(e.target.value)} />
      </label>
      <button type="submit" className="primary" disabled={setOpening.isPending}>
        {t('save')}
      </button>
      {invalid ? <span className="field-error">{t('openingInvalid')}</span> : null}
      <MutationMessage error={setOpening.error} />
    </form>
  )
}

function NewAccountPanel() {
  const { t } = useI18n()
  const household = useHousehold()
  const members = useMembers()
  const create = useCreateAccount()
  const id = useId()
  const [name, setName] = useState('')
  const [kind, setKind] = useState<AccountKind>('cash')
  const [institution, setInstitution] = useState('')
  const [owner, setOwner] = useState('')
  const [isPrivate, setIsPrivate] = useState(false)
  const [opening, setOpening] = useState('')
  const [date, setDate] = useState(todayIn(household.timezone))
  const [invalid, setInvalid] = useState(false)
  const [done, setDone] = useState(false)

  function submit(e: FormEvent) {
    e.preventDefault()
    const body: NewAccount = { name: name.trim(), kind }
    if (institution.trim()) body.institution = institution.trim()
    if (owner) body.owner_user_id = owner
    // Only an owned account can be private.
    if (owner && isPrivate) body.is_private = true
    if (opening.trim()) {
      const poisha = parseSignedTaka(opening)
      if (poisha === null) {
        setInvalid(true)
        return
      }
      body.opening_balance = { amount_poisha: poisha, as_of_date: date }
    }
    setInvalid(false)
    create.mutate(body, {
      onSuccess: () => {
        setDone(true)
        setName('')
        setInstitution('')
        setOpening('')
      },
    })
  }

  return (
    <section className="panel">
      <h2>{t('addAccount')}</h2>
      <form className="form-grid" onSubmit={submit}>
        <label htmlFor={`${id}-name`}>
          {t('accountName')}
          <input id={`${id}-name`} required maxLength={100} value={name} onChange={(e) => { setName(e.target.value); setDone(false) }} />
        </label>
        <label htmlFor={`${id}-kind`}>
          {t('kind')}
          <select id={`${id}-kind`} value={kind} onChange={(e) => setKind(e.target.value as AccountKind)}>
            {KINDS.map((k) => (
              <option key={k} value={k}>
                {t(`kind_${k}`)}
              </option>
            ))}
          </select>
        </label>
        <label htmlFor={`${id}-inst`}>
          {t('institution')}
          <input id={`${id}-inst`} maxLength={100} value={institution} onChange={(e) => setInstitution(e.target.value)} />
        </label>
        <label htmlFor={`${id}-owner`}>
          {t('owner')}
          <select
            id={`${id}-owner`}
            value={owner}
            onChange={(e) => {
              setOwner(e.target.value)
              if (!e.target.value) setIsPrivate(false)
            }}
          >
            <option value="">{t('sharedAccount')}</option>
            {(members.data ?? []).filter((m) => m.is_active).map((m) => (
              <option key={m.user_id} value={m.user_id}>
                {m.display_name}
              </option>
            ))}
          </select>
        </label>
        <label htmlFor={`${id}-private`} className="checkbox">
          <input
            id={`${id}-private`}
            type="checkbox"
            checked={isPrivate}
            disabled={!owner}
            onChange={(e) => setIsPrivate(e.target.checked)}
          />
          {t('privateAccount')}
        </label>
        <label htmlFor={`${id}-opening`}>
          {t('openingBalance')}
          <input id={`${id}-opening`} inputMode="decimal" value={opening} aria-invalid={invalid} onChange={(e) => setOpening(e.target.value)} />
        </label>
        <label htmlFor={`${id}-date`}>
          {t('openingDate')}
          <input id={`${id}-date`} type="date" value={date} onChange={(e) => setDate(e.target.value)} />
        </label>
        <button type="submit" className="primary" disabled={create.isPending || !name.trim()}>
          {t('create')}
        </button>
      </form>
      <p className="hint">{t('openingHelp')}</p>
      <p className="hint">{t('privateHelp')}</p>
      {invalid ? <p className="field-error">{t('openingInvalid')}</p> : null}
      <MutationMessage error={create.error} />
      {done ? <p className="success" role="status">{t('saved')}</p> : null}
    </section>
  )
}

// --- Categories ----------------------------------------------------------------

function CategoriesTab({ isAdmin }: { isAdmin: boolean }) {
  const { t } = useI18n()
  const categories = useCategories()
  const update = useUpdateCategory()
  return (
    <div className="grid-2">
      <QueryView query={categories}>
        {(list) => (
          <>
            {(['expense', 'income'] as CategoryKind[]).map((kind) => (
              <section className="panel" key={kind}>
                <h2>{kind === 'expense' ? t('expenseCategories') : t('incomeCategories')}</h2>
                <div className="table-wrap">
                  <table>
                    <caption className="visually-hidden">{kind}</caption>
                    <thead>
                      <tr>
                        <th scope="col">{t('nameBn')}</th>
                        <th scope="col">{t('nameEn')}</th>
                        {isAdmin ? <th scope="col"><span className="visually-hidden">{t('edit')}</span></th> : null}
                      </tr>
                    </thead>
                    <tbody>
                      {list
                        .filter((c) => c.kind === kind)
                        .map((c) => (
                          <CategoryRow
                            key={c.id}
                            category={c}
                            isAdmin={isAdmin}
                            onSave={(names) => update.mutate({ id: c.id, ...names })}
                            onArchive={() => update.mutate({ id: c.id, is_archived: !c.is_archived })}
                          />
                        ))}
                    </tbody>
                  </table>
                </div>
              </section>
            ))}
          </>
        )}
      </QueryView>
      {isAdmin ? <NewCategoryPanel /> : null}
      <MutationMessage error={update.error} />
    </div>
  )
}

function CategoryRow({
  category,
  isAdmin,
  onSave,
  onArchive,
}: {
  category: Category
  isAdmin: boolean
  onSave: (names: { name_bn: string; name_en: string }) => void
  onArchive: () => void
}) {
  const { t } = useI18n()
  const [editing, setEditing] = useState(false)
  const [bn, setBn] = useState(category.name_bn)
  const [en, setEn] = useState(category.name_en)
  return (
    <tr className={category.is_archived ? 'voided' : undefined}>
      <th scope="row">
        {editing ? (
          <input className="inline-input" aria-label={t('nameBn')} value={bn} onChange={(e) => setBn(e.target.value)} />
        ) : (
          category.name_bn
        )}
        {category.is_default ? <span className="badge">{t('defaultCategory')}</span> : null}
        {category.is_archived ? <span className="badge">{t('archived')}</span> : null}
      </th>
      <td>
        {editing ? (
          <input className="inline-input" aria-label={t('nameEn')} value={en} onChange={(e) => setEn(e.target.value)} />
        ) : (
          category.name_en
        )}
      </td>
      {isAdmin ? (
        <td className="row-actions">
          {editing ? (
            <button
              type="button"
              className="primary"
              disabled={!bn.trim() || !en.trim()}
              onClick={() => {
                onSave({ name_bn: bn.trim(), name_en: en.trim() })
                setEditing(false)
              }}
            >
              {t('save')}
            </button>
          ) : (
            <button type="button" onClick={() => setEditing(true)}>
              {t('rename')}
            </button>
          )}
          <button type="button" onClick={onArchive}>
            {category.is_archived ? t('unarchive') : t('archive')}
          </button>
        </td>
      ) : null}
    </tr>
  )
}

function NewCategoryPanel() {
  const { t } = useI18n()
  const id = useId()
  const create = useCreateCategory()
  const [kind, setKind] = useState<CategoryKind>('expense')
  const [bn, setBn] = useState('')
  const [en, setEn] = useState('')
  const [done, setDone] = useState(false)

  function submit(e: FormEvent) {
    e.preventDefault()
    create.mutate(
      { kind, name_bn: bn.trim(), name_en: en.trim() },
      {
        onSuccess: () => {
          setBn('')
          setEn('')
          setDone(true)
        },
      },
    )
  }

  return (
    <section className="panel">
      <h2>{t('addCategory')}</h2>
      <form className="form-grid" onSubmit={submit}>
        <label htmlFor={`${id}-kind`}>
          {t('type')}
          <select id={`${id}-kind`} value={kind} onChange={(e) => setKind(e.target.value as CategoryKind)}>
            <option value="expense">{t('typeExpense')}</option>
            <option value="income">{t('typeIncome')}</option>
          </select>
        </label>
        <label htmlFor={`${id}-bn`}>
          {t('nameBn')}
          <input id={`${id}-bn`} required maxLength={100} value={bn} onChange={(e) => { setBn(e.target.value); setDone(false) }} />
        </label>
        <label htmlFor={`${id}-en`}>
          {t('nameEn')}
          <input id={`${id}-en`} required maxLength={100} value={en} onChange={(e) => setEn(e.target.value)} />
        </label>
        <button type="submit" className="primary" disabled={create.isPending || !bn.trim() || !en.trim()}>
          {t('create')}
        </button>
      </form>
      <MutationMessage error={create.error} />
      {done ? <p className="success" role="status">{t('saved')}</p> : null}
    </section>
  )
}

