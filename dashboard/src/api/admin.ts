import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useSession } from '../auth/session'
import type { Account, AccountKind, Category, CategoryKind, Member, Role } from './types'

export interface InvitationCreated {
  id: string
  role: Role
  display_name: string
  expires_at: string
  token: string
}

/** Mutations for the admin screens. Every success refreshes the household's cached data. */
function useAdminMutation<TVars, TResult>(run: (vars: TVars) => Promise<TResult>) {
  const queryClient = useQueryClient()
  const { household } = useSession()
  return useMutation({
    mutationFn: run,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: [household?.id ?? 'none'] }),
  })
}

export function useUpdateMember() {
  const { client } = useSession()
  return useAdminMutation((v: { userId: string; role?: Role; is_active?: boolean }) =>
    client.request<Member>('PATCH', `/members/${v.userId}`, {
      body: { ...(v.role ? { role: v.role } : {}), ...(v.is_active !== undefined ? { is_active: v.is_active } : {}) },
    }),
  )
}

export function useCreateInvitation() {
  const { client } = useSession()
  return useAdminMutation((v: { display_name: string; role: Role }) =>
    client.request<InvitationCreated>('POST', '/members/invitations', { body: v }),
  )
}

export interface NewAccount {
  name: string
  kind: AccountKind
  institution?: string
  owner_user_id?: string
  is_private?: boolean
  opening_balance?: { amount_poisha: number; as_of_date: string }
}

export function useCreateAccount() {
  const { client } = useSession()
  return useAdminMutation((v: NewAccount) => client.request<Account>('POST', '/accounts', { body: v }))
}

export function useUpdateAccount() {
  const { client } = useSession()
  return useAdminMutation((v: { id: string; name?: string; is_archived?: boolean; is_private?: boolean }) => {
    const { id, ...body } = v
    return client.request<Account>('PATCH', `/accounts/${id}`, { body })
  })
}

export function useSetOpeningBalance() {
  const { client } = useSession()
  return useAdminMutation((v: { id: string; amount_poisha: number; as_of_date: string }) =>
    client.request<Account>('POST', `/accounts/${v.id}/opening-balance`, {
      body: { amount_poisha: v.amount_poisha, as_of_date: v.as_of_date },
    }),
  )
}

export interface OpeningCorrection {
  id: string
  previous_amount_poisha: number
  previous_as_of_date: string
  amount_poisha: number
  as_of_date: string
  reason: string
}

/** Audited fix of a wrong opening balance; sends the values shown so a concurrent change is refused. */
export function useCorrectOpeningBalance() {
  const { client } = useSession()
  return useAdminMutation((v: OpeningCorrection) => {
    const { id, ...body } = v
    return client.request<Account>('POST', `/accounts/${id}/opening-balance/correct`, { body })
  })
}

export function useCreateCategory() {
  const { client } = useSession()
  return useAdminMutation((v: { kind: CategoryKind; name_en: string; name_bn: string }) =>
    client.request<Category>('POST', '/categories', { body: v }),
  )
}

export function useUpdateCategory() {
  const { client } = useSession()
  return useAdminMutation((v: { id: string; name_en?: string; name_bn?: string; is_archived?: boolean }) => {
    const { id, ...body } = v
    return client.request<Category>('PATCH', `/categories/${id}`, { body })
  })
}
