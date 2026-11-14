import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSession } from '../auth/session'
import type { Receipt } from './types'

export const MAX_RECEIPT_BYTES = 10 * 1024 * 1024

export function useReceipts(transactionId: string) {
  const { client, household } = useSession()
  return useQuery({
    queryKey: [household?.id ?? 'none', 'receipts', transactionId],
    queryFn: () => client.request<Receipt[]>('GET', `/transactions/${transactionId}/receipts`),
  })
}

function useInvalidate() {
  const queryClient = useQueryClient()
  const { household } = useSession()
  return () => queryClient.invalidateQueries({ queryKey: [household?.id ?? 'none'] })
}

export function useAddReceipt(transactionId: string) {
  const { client } = useSession()
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: (file: Blob) =>
      client.request<Receipt>('POST', `/transactions/${transactionId}/receipts`, { file }),
    onSuccess: invalidate,
  })
}

export function useRemoveReceipt() {
  const { client } = useSession()
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: (id: string) => client.request<void>('DELETE', `/receipts/${id}`),
    onSuccess: invalidate,
  })
}

/**
 * The receipt file as a short-lived object URL. Files are only served through
 * the authenticated API, so a plain <img src> to the server cannot be used.
 */
export function useReceiptUrl(receiptId: string): string | null {
  const { client } = useSession()
  const [url, setUrl] = useState<string | null>(null)
  useEffect(() => {
    let objectUrl: string | null = null
    let cancelled = false
    client
      .download(`/receipts/${receiptId}/file`)
      .then(({ blob }) => {
        if (cancelled) return
        objectUrl = URL.createObjectURL(blob)
        setUrl(objectUrl)
      })
      .catch(() => setUrl(null))
    return () => {
      cancelled = true
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [client, receiptId])
  return url
}
