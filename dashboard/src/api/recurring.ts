import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSession } from '../auth/session'
import type { EntryType, Frequency, Occurrence, RecurringRule } from './types'

export interface NewRule {
  name: string
  type: EntryType
  /** Decimal taka as typed (checked locally, parsed by the server). */
  amount: string
  category_id?: string
  account_id: string
  destination_account_id?: string
  frequency: Frequency
  interval: number
  start_date: string
  end_date?: string
  auto_post: boolean
}

function useInvalidate() {
  const queryClient = useQueryClient()
  const { household } = useSession()
  return () => queryClient.invalidateQueries({ queryKey: [household?.id ?? 'none'] })
}

export function useRules() {
  const { client, household } = useSession()
  return useQuery({
    queryKey: [household?.id ?? 'none', 'recurring-rules'],
    queryFn: () => client.request<RecurringRule[]>('GET', '/recurring-rules'),
  })
}

export function useDue() {
  const { client, household } = useSession()
  return useQuery({
    queryKey: [household?.id ?? 'none', 'recurring-due'],
    queryFn: () => client.request<Occurrence[]>('GET', '/recurring/occurrences', { query: { status: 'pending' } }),
  })
}

export function useCreateRule() {
  const { client } = useSession()
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: (rule: NewRule) => client.request<RecurringRule>('POST', '/recurring-rules', { body: rule }),
    onSuccess: invalidate,
  })
}

export function useUpdateRule() {
  const { client } = useSession()
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: (v: { id: string; version: number; paused?: boolean }) => {
      const { id, ...body } = v
      return client.request<RecurringRule>('PATCH', `/recurring-rules/${id}`, { body })
    },
    onSuccess: invalidate,
  })
}

export function useResolveOccurrence() {
  const { client } = useSession()
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: (v: { id: string; action: 'confirm' | 'skip'; amount?: string }) =>
      client.request<Occurrence>('POST', `/recurring/occurrences/${v.id}/${v.action}`, {
        body: v.action === 'confirm' ? (v.amount ? { amount: v.amount } : {}) : undefined,
      }),
    onSuccess: invalidate,
  })
}
