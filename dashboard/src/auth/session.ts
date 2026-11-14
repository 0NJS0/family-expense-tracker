import { createContext, useContext } from 'react'
import type { ApiClient } from '../api/client'
import type { Household, Me, Role } from '../api/types'

export type Status = 'checking' | 'anonymous' | 'ready'

export interface Session {
  status: Status
  /** True when the user was sent back to the login page by an expired session. */
  expired: boolean
  client: ApiClient
  me: Me | null
  household: Household | null
  role: Role | null
  login: (username: string, password: string) => Promise<void>
  logout: () => Promise<void>
  switchHousehold: (householdId: string) => Promise<void>
}

export const SessionContext = createContext<Session | null>(null)

export function useSession(): Session {
  const ctx = useContext(SessionContext)
  if (!ctx) throw new Error('useSession must be used inside SessionProvider')
  return ctx
}

/** For pages rendered only when signed in to a household. */
export function useHousehold(): Household {
  const { household } = useSession()
  if (!household) throw new Error('useHousehold used without an active household')
  return household
}
