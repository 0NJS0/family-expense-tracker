import { QueryClient } from '@tanstack/react-query'
import { ApiError } from './client'

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 15_000,
        refetchOnWindowFocus: true,
        // Client errors (403, 404, 422) will not fix themselves; retry only transient ones.
        retry: (count, error) =>
          count < 2 && !(error instanceof ApiError && error.status < 500),
      },
    },
  })
}
