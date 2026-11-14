import { useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { ApiClient } from '../api/client'
import type { Household, Me } from '../api/types'
import { SessionContext } from './session'
import type { Session, Status } from './session'

const HOUSEHOLD_KEY = 'fet.household'

export function SessionProvider({ client, children }: { client: ApiClient; children: ReactNode }) {
  const queryClient = useQueryClient()
  const [status, setStatus] = useState<Status>('checking')
  const [expired, setExpired] = useState(false)
  const [me, setMe] = useState<Me | null>(null)
  const [household, setHousehold] = useState<Household | null>(null)

  const reset = useCallback(() => {
    setMe(null)
    setHousehold(null)
    setStatus('anonymous')
    queryClient.clear()
  }, [queryClient])

  const load = useCallback(async () => {
    const profile = await client.request<Me>('GET', '/me', { noHousehold: true })
    const ids = profile.memberships.map((m) => m.household_id)
    const saved = readSaved()
    const chosen = saved && ids.includes(saved) ? saved : ids[0]
    if (!chosen) {
      // Authenticated but not in any (active) household: nothing to show.
      setMe(profile)
      setHousehold(null)
      setStatus('ready')
      return
    }
    client.setHousehold(chosen)
    const current = await client.request<Household>('GET', '/households/current')
    setMe(profile)
    setHousehold(current)
    setStatus('ready')
  }, [client])

  useEffect(() => {
    client.setSessionExpiredHandler(() => {
      setExpired(true)
      reset()
    })
    let cancelled = false
    client
      .resume()
      .then(async (ok) => {
        if (cancelled) return
        if (ok) await load()
        else setStatus('anonymous')
      })
      .catch(() => {
        if (!cancelled) setStatus('anonymous')
      })
    return () => {
      cancelled = true
    }
  }, [client, load, reset])

  const login = useCallback(
    async (username: string, password: string) => {
      await client.login(username, password)
      setExpired(false)
      await load()
    },
    [client, load],
  )

  const logout = useCallback(async () => {
    await client.logout()
    reset()
  }, [client, reset])

  const switchHousehold = useCallback(
    async (householdId: string) => {
      save(householdId)
      queryClient.clear()
      await load()
    },
    [load, queryClient],
  )

  const role =
    me?.memberships.find((m) => m.household_id === household?.id)?.role ?? null

  const value = useMemo<Session>(
    () => ({ status, expired, client, me, household, role, login, logout, switchHousehold }),
    [status, expired, client, me, household, role, login, logout, switchHousehold],
  )
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
}

function readSaved(): string | null {
  try {
    return sessionStorage.getItem(HOUSEHOLD_KEY)
  } catch {
    return null
  }
}

function save(id: string): void {
  try {
    sessionStorage.setItem(HOUSEHOLD_KEY, id)
  } catch {
    // Selection simply will not survive a reload.
  }
}
