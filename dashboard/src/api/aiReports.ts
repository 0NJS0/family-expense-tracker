import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSession } from '../auth/session'
import type { AiReport } from './types'

/** Refreshes every 30 s while the homelab is still writing a summary. */
export function useAiReports(enabled: boolean) {
  const { client, household } = useSession()
  return useQuery({
    queryKey: [household?.id ?? 'none', 'ai-reports'],
    queryFn: () => client.request<AiReport[]>('GET', '/ai-reports'),
    enabled,
    refetchInterval: (query) =>
      query.state.data?.some((r) => r.status === 'queued' || r.status === 'running') ? 30_000 : false,
  })
}

export function useRequestAiReport() {
  const { client, household } = useSession()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (v: { month: string; language: 'bn' | 'en' }) => client.request<AiReport>('POST', '/ai-reports', { body: v }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: [household?.id ?? 'none', 'ai-reports'] }),
  })
}
