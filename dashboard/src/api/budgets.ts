import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSession } from '../auth/session'
import type { BudgetAlert, BudgetStatus } from './types'

/** "2026-10": a calendar month in the household timezone. */
export type YearMonth = string

export interface BudgetChange {
  category_id?: string
  /** Decimal taka, as typed (validated locally, parsed by the server). */
  amount?: string
  remove?: true
  warn_percent: number
  from_month: YearMonth
}

export function useBudgetStatus(month: YearMonth) {
  const { client, household } = useSession()
  return useQuery({
    queryKey: [household?.id ?? 'none', 'budgets', month],
    queryFn: () => client.request<BudgetStatus>('GET', '/budgets', { query: { month } }),
    placeholderData: keepPreviousData,
  })
}

export function useBudgetAlerts(month: YearMonth, enabled: boolean) {
  const { client, household } = useSession()
  return useQuery({
    queryKey: [household?.id ?? 'none', 'budget-alerts', month],
    queryFn: () => client.request<BudgetAlert[]>('GET', '/budgets/alerts', { query: { month } }),
    enabled,
  })
}

export function useSetBudget() {
  const { client, household } = useSession()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (change: BudgetChange) => client.request<BudgetStatus>('PUT', '/budgets', { body: change }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: [household?.id ?? 'none'] }),
  })
}
