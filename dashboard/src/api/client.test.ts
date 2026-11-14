import { describe, expect, it, vi } from 'vitest'
import { FakeApi, TOKENS, apiError, json } from '../test/fakeApi'
import { ApiClient, ApiError, NetworkError } from './client'

function setup(api: FakeApi, refresh: string | null = 'stored-refresh') {
  sessionStorage.clear()
  if (refresh) sessionStorage.setItem('fet.refresh', refresh)
  const client = new ApiClient('/api/v1', sessionStorage, api.fetch)
  const expired = vi.fn()
  client.setSessionExpiredHandler(expired)
  return { client, expired }
}

describe('ApiClient', () => {
  it('restores the session with the refresh token and sends auth and household headers', async () => {
    const api = new FakeApi().on('POST', '/auth/refresh', TOKENS).on('GET', '/members', [])
    const { client } = setup(api)
    client.setHousehold('hh-1')
    await client.request('GET', '/members')
    const call = api.callsTo('GET', '/members')[0]
    expect(call?.headers.Authorization).toBe('Bearer access-1')
    expect(call?.headers['X-Household-ID']).toBe('hh-1')
    // The rotated refresh token replaces the old one; the access token is never stored.
    expect(sessionStorage.getItem('fet.refresh')).toBe('refresh-1')
    expect(JSON.stringify(sessionStorage)).not.toContain('access-1')
  })

  it('refreshes once on 401 and retries the request', async () => {
    let first = true
    const api = new FakeApi()
      .on('POST', '/auth/refresh', TOKENS)
      .on('GET', '/members', () => {
        if (first) {
          first = false
          return apiError(401, 'unauthorized')
        }
        return json([])
      })
    const { client } = setup(api)
    await client.resume()
    await expect(client.request('GET', '/members')).resolves.toEqual([])
    expect(api.callsTo('POST', '/auth/refresh')).toHaveLength(2)
    expect(api.callsTo('GET', '/members')).toHaveLength(2)
  })

  it('shares one refresh between parallel requests', async () => {
    const api = new FakeApi()
      .on('POST', '/auth/refresh', async () => {
        await new Promise((r) => setTimeout(r, 10))
        return json(TOKENS)
      })
      .on('GET', '/a', [])
      .on('GET', '/b', [])
    const { client } = setup(api)
    await Promise.all([client.request('GET', '/a'), client.request('GET', '/b')])
    expect(api.callsTo('POST', '/auth/refresh')).toHaveLength(1)
  })

  it('ends the session when the refresh token is rejected', async () => {
    const api = new FakeApi().on('POST', '/auth/refresh', apiError(401, 'unauthorized'))
    const { client, expired } = setup(api)
    await expect(client.request('GET', '/members')).rejects.toMatchObject({ status: 401 })
    expect(expired).toHaveBeenCalledOnce()
    expect(sessionStorage.getItem('fet.refresh')).toBeNull()
  })

  it('rejects a token response without tokens', async () => {
    const api = new FakeApi().on('POST', '/auth/login', {})
    const { client } = setup(api, null)
    await expect(client.login('a', 'b')).rejects.toMatchObject({ code: 'bad_token_response' })
    expect(client.hasSession).toBe(false)
  })

  it('keeps the refresh token when the network is down', async () => {
    const api = new FakeApi()
    const failing = new ApiClient('/api/v1', sessionStorage, () => Promise.reject(new TypeError('offline')))
    sessionStorage.setItem('fet.refresh', 'keep-me')
    await expect(failing.resume()).resolves.toBe(false)
    expect(sessionStorage.getItem('fet.refresh')).toBe('keep-me')
    expect(api.calls).toHaveLength(0)
  })

  it('turns error envelopes into ApiError and fetch failures into NetworkError', async () => {
    const api = new FakeApi()
      .on('POST', '/auth/refresh', TOKENS)
      .on('PATCH', '/transactions/x', apiError(409, 'conflict', 'changed', [{ current_version: 3 }]))
    const { client } = setup(api)
    const error = await client.request('PATCH', '/transactions/x', { body: {} }).catch((e) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 409, code: 'conflict', details: [{ current_version: 3 }] })

    const offline = new ApiClient('/api/v1', sessionStorage, () => Promise.reject(new TypeError('x')))
    await expect(offline.login('a', 'b')).rejects.toBeInstanceOf(NetworkError)
  })

  it('omits empty query parameters', async () => {
    const api = new FakeApi().on('POST', '/auth/refresh', TOKENS).on('GET', '/transactions', [])
    const { client } = setup(api)
    await client.request('GET', '/transactions', {
      query: { type: 'expense', q: '', include_voided: false, member_id: undefined, offset: 0 },
    })
    const query = api.callsTo('GET', '/transactions')[0]?.query
    expect(Object.fromEntries(query ?? [])).toEqual({ type: 'expense', offset: '0' })
  })

  it('logs out on the server and locally', async () => {
    const api = new FakeApi()
      .on('POST', '/auth/refresh', TOKENS)
      .on('POST', '/auth/logout', () => new Response(null, { status: 204 }))
    const { client } = setup(api)
    await client.resume()
    await client.logout()
    expect(api.callsTo('POST', '/auth/logout')[0]?.body).toEqual({ refresh_token: 'refresh-1' })
    expect(client.hasSession).toBe(false)
  })

  it('downloads files with the server-provided filename', async () => {
    const api = new FakeApi()
      .on('POST', '/auth/refresh', TOKENS)
      .on('GET', '/transactions/export.csv', () =>
        new Response('﻿a,b\r\n', {
          headers: { 'Content-Disposition': 'attachment; filename="transactions-2026-10-15.csv"' },
        }),
      )
    const { client } = setup(api)
    const { blob, filename } = await client.download('/transactions/export.csv', { type: 'expense' })
    expect(filename).toBe('transactions-2026-10-15.csv')
    expect(await blob.text()).toContain('a,b')
  })
})
