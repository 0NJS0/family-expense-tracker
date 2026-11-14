import { ApiError, NetworkError } from '../api/client'
import type { MessageKey } from '../i18n/strings'

export function errorKey(error: unknown): MessageKey {
  if (error instanceof NetworkError) return 'networkError'
  if (error instanceof ApiError) {
    if (error.status === 403) return 'forbidden'
    if (error.status === 429) return 'rateLimited'
    if (error.status === 409) return 'conflict'
  }
  return 'loadFailed'
}
