import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSession } from '../auth/session'
import type { DateRange } from '../lib/dates'
import type {
  Account,
  AccountBalances,
  Category,
  CompareTo,
  Granularity,
  Member,
  Page,
  Summary,
  Timeseries,
  Transaction,
  TransactionFilters,
  TransactionPatch,
} from './types'

export const PAGE_SIZE = 25

/** Every query key starts with the household id, so switching households never mixes data. */
function useKeyPrefix(): string {
  return useSession().household?.id ?? 'none'
}

export function useSummary(
  range: DateRange,
  memberId?: string,
  compareTo: CompareTo = 'previous_period',
) {
  const { client } = useSession()
  const hh = useKeyPrefix()
  return useQuery({
    queryKey: [hh, 'summary', range.from, range.to, memberId ?? null, compareTo],
    queryFn: () =>
      client.request<Summary>('GET', '/reports/summary', {
        query: {
          date_from: range.from,
          date_to: range.to,
          member_id: memberId,
          compare_to: compareTo,
        },
      }),
    placeholderData: keepPreviousData,
  })
}

export function useTimeseries(range: DateRange, granularity: Granularity, memberId?: string) {
  const { client } = useSession()
  const hh = useKeyPrefix()
  return useQuery({
    queryKey: [hh, 'timeseries', range.from, range.to, granularity, memberId ?? null],
    queryFn: () =>
      client.request<Timeseries>('GET', '/reports/timeseries', {
        query: { date_from: range.from, date_to: range.to, granularity, member_id: memberId },
      }),
    placeholderData: keepPreviousData,
  })
}

export function useBalances() {
  const { client } = useSession()
  const hh = useKeyPrefix()
  return useQuery({
    queryKey: [hh, 'balances'],
    queryFn: () => client.request<AccountBalances>('GET', '/accounts/balances'),
  })
}

export function useTransactions(filters: TransactionFilters, page: number) {
  const { client } = useSession()
  const hh = useKeyPrefix()
  return useQuery({
    queryKey: [hh, 'transactions', filters, page],
    queryFn: () =>
      client.request<Page<Transaction>>('GET', '/transactions', {
        query: { ...filters, limit: PAGE_SIZE, offset: page * PAGE_SIZE },
      }),
    placeholderData: keepPreviousData,
  })
}

/** The largest expenses in a period, largest first (ordered by the backend). */
export function useTopExpenses(range: DateRange, limit = 5) {
  const { client } = useSession()
  const hh = useKeyPrefix()
  return useQuery({
    queryKey: [hh, 'top-expenses', range.from, range.to, limit],
    queryFn: () =>
      client.request<Page<Transaction>>('GET', '/transactions', {
        query: { date_from: range.from, date_to: range.to, type: 'expense', sort: 'largest', limit },
      }),
  })
}

export function useTransaction(id: string | null) {
  const { client } = useSession()
  const hh = useKeyPrefix()
  return useQuery({
    queryKey: [hh, 'transaction', id],
    queryFn: () => client.request<Transaction>('GET', `/transactions/${id}`),
    enabled: id !== null,
  })
}

export function useAccounts() {
  const { client } = useSession()
  const hh = useKeyPrefix()
  return useQuery({
    queryKey: [hh, 'accounts'],
    queryFn: () =>
      client.request<Account[]>('GET', '/accounts', { query: { include_archived: true } }),
    staleTime: 60_000,
  })
}

export function useCategories() {
  const { client } = useSession()
  const hh = useKeyPrefix()
  return useQuery({
    queryKey: [hh, 'categories'],
    queryFn: () =>
      client.request<Category[]>('GET', '/categories', { query: { include_archived: true } }),
    staleTime: 60_000,
  })
}

export function useMembers() {
  const { client } = useSession()
  const hh = useKeyPrefix()
  return useQuery({
    queryKey: [hh, 'members'],
    queryFn: () => client.request<Member[]>('GET', '/members'),
    staleTime: 60_000,
  })
}

/** After any financial change, every report, balance and list may be stale. */
function useInvalidateHousehold() {
  const queryClient = useQueryClient()
  const hh = useKeyPrefix()
  return () => queryClient.invalidateQueries({ queryKey: [hh] })
}

export function useUpdateTransaction() {
  const { client } = useSession()
  const invalidate = useInvalidateHousehold()
  return useMutation({
    mutationFn: ({ id, patch }: { id: string; patch: TransactionPatch }) =>
      client.request<Transaction>('PATCH', `/transactions/${id}`, { body: patch }),
    onSuccess: invalidate,
  })
}

export function useVoidTransaction() {
  const { client } = useSession()
  const invalidate = useInvalidateHousehold()
  return useMutation({
    mutationFn: ({ id, version, reason }: { id: string; version: number; reason: string }) =>
      client.request<Transaction>('POST', `/transactions/${id}/void`, {
        body: { version, reason },
      }),
    onSuccess: invalidate,
  })
}
