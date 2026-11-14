import { useMemo } from 'react'
import { useAccounts, useCategories, useMembers } from '../api/queries'
import type { Account, Category, Member } from '../api/types'
import { useI18n } from '../i18n/context'

/** Id → display name maps for tables and forms. */
export function useLookups() {
  const { lang } = useI18n()
  const accounts = useAccounts()
  const categories = useCategories()
  const members = useMembers()
  return useMemo(() => {
    const accountById = new Map<string, Account>((accounts.data ?? []).map((a) => [a.id, a]))
    const categoryById = new Map<string, Category>((categories.data ?? []).map((c) => [c.id, c]))
    const memberById = new Map<string, Member>((members.data ?? []).map((m) => [m.user_id, m]))
    return {
      accounts: accounts.data ?? [],
      categories: categories.data ?? [],
      members: members.data ?? [],
      accountName: (id: string | null) => (id ? (accountById.get(id)?.name ?? '—') : ''),
      categoryName: (id: string | null) => {
        const c = id ? categoryById.get(id) : undefined
        return c ? (lang === 'bn' ? c.name_bn : c.name_en) : ''
      },
      memberName: (id: string) => memberById.get(id)?.display_name ?? '—',
      categoryLabel: (c: Category) => (lang === 'bn' ? c.name_bn : c.name_en),
    }
  }, [accounts.data, categories.data, members.data, lang])
}
