import type { TokenPair } from './types'

/** An error response from the API, using its {"error": {...}} envelope. */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly details: unknown[]

  constructor(status: number, code: string, message: string, details: unknown[] = []) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.details = details
  }
}

/** The request never reached the API (offline, VPN down, DNS…). */
export class NetworkError extends Error {
  constructor(cause: unknown) {
    super('network error', { cause })
    this.name = 'NetworkError'
  }
}

type Query = Record<string, string | number | boolean | undefined | null>

export interface RequestOptions {
  query?: Query
  body?: unknown
  /** Raw bytes (e.g. a receipt photo), sent as application/octet-stream. */
  file?: Blob
  /** Skip the household header (identity endpoints). */
  noHousehold?: boolean
}

/**
 * Token handling:
 * - The access token lives only in memory.
 * - The refresh token is kept in sessionStorage so a page reload does not log
 *   the admin out; it is cleared when the tab closes. See docs/security.md (S8).
 * - On 401, one shared refresh runs and the request is retried once. Parallel
 *   requests share the same refresh so rotation is not triggered repeatedly.
 */
export class ApiClient {
  private accessToken: string | null = null
  private refreshInFlight: Promise<boolean> | null = null
  private householdId: string | null = null
  private onSessionExpired: () => void = () => {}

  private readonly baseUrl: string
  private readonly storage: Storage | null
  private readonly fetchImpl: typeof fetch

  constructor(
    baseUrl = '/api/v1',
    storage: Storage | null = safeSessionStorage(),
    fetchImpl: typeof fetch = (...args) => fetch(...args),
  ) {
    this.baseUrl = baseUrl
    this.storage = storage
    this.fetchImpl = fetchImpl
  }

  setHousehold(id: string | null): void {
    this.householdId = id
  }

  setSessionExpiredHandler(handler: () => void): void {
    this.onSessionExpired = handler
  }

  get hasSession(): boolean {
    return this.accessToken !== null || this.readRefreshToken() !== null
  }

  async login(username: string, password: string): Promise<void> {
    const pair = await this.send<TokenPair>('POST', '/auth/login', {
      body: { username, password, device_name: 'Web dashboard' },
      noHousehold: true,
    })
    this.storeTokens(pair)
  }

  /** Public: create an account from an invitation link (no session needed). */
  async acceptInvitation(body: {
    token: string
    username: string
    password: string
    preferred_language: 'bn' | 'en'
  }): Promise<void> {
    await this.send('POST', '/invitations/accept', { body, noHousehold: true })
  }

  async logout(): Promise<void> {
    const refreshToken = this.readRefreshToken()
    this.clearSession()
    if (refreshToken) {
      // Best effort: the local session is already gone either way.
      await this.send('POST', '/auth/logout', { body: { refresh_token: refreshToken } }).catch(
        () => undefined,
      )
    }
  }

  /** Restore a session after a reload using the stored refresh token. */
  async resume(): Promise<boolean> {
    if (this.accessToken) return true
    if (!this.readRefreshToken()) return false
    return this.refresh()
  }

  clearSession(): void {
    this.accessToken = null
    this.householdId = null
    try {
      this.storage?.removeItem(REFRESH_KEY)
    } catch {
      // Ignore storage errors on clear.
    }
  }

  async request<T>(method: string, path: string, options: RequestOptions = {}): Promise<T> {
    const response = await this.authorized(method, path, options)
    if (response.status === 204) return undefined as T
    return (await response.json()) as T
  }

  /** Fetch a binary/text download (e.g. CSV) with the same auth handling. */
  async download(path: string, query: Query = {}): Promise<{ blob: Blob; filename: string }> {
    const response = await this.authorized('GET', path, { query })
    const disposition = response.headers.get('Content-Disposition') ?? ''
    const filename = /filename="([^"]+)"/.exec(disposition)?.[1] ?? 'export.csv'
    return { blob: await response.blob(), filename }
  }

  private async authorized(method: string, path: string, options: RequestOptions) {
    if (!this.accessToken && !(await this.refresh())) throw this.expired()
    let response = await this.raw(method, path, options)
    if (response.status === 401) {
      if (!(await this.refresh())) throw this.expired()
      response = await this.raw(method, path, options)
      if (response.status === 401) throw this.expired()
    }
    if (!response.ok) throw await toApiError(response)
    return response
  }

  private expired(): ApiError {
    this.clearSession()
    this.onSessionExpired()
    return new ApiError(401, 'unauthorized', 'session expired')
  }

  private refresh(): Promise<boolean> {
    this.refreshInFlight ??= this.doRefresh().finally(() => {
      this.refreshInFlight = null
    })
    return this.refreshInFlight
  }

  private async doRefresh(): Promise<boolean> {
    const refreshToken = this.readRefreshToken()
    if (!refreshToken) return false
    try {
      const pair = await this.send<TokenPair>('POST', '/auth/refresh', {
        body: { refresh_token: refreshToken },
        noHousehold: true,
      })
      this.storeTokens(pair)
      return true
    } catch (error) {
      // A network failure keeps the refresh token for a later retry; a
      // rejected token means the session is over.
      if (error instanceof ApiError) this.clearSession()
      return false
    }
  }

  /** Unauthenticated request with envelope error handling. */
  private async send<T>(method: string, path: string, options: RequestOptions): Promise<T> {
    const response = await this.raw(method, path, { ...options, noHousehold: true }, false)
    if (!response.ok) throw await toApiError(response)
    if (response.status === 204) return undefined as T
    return (await response.json()) as T
  }

  private async raw(
    method: string,
    path: string,
    options: RequestOptions,
    withAuth = true,
  ): Promise<Response> {
    const headers: Record<string, string> = { Accept: 'application/json' }
    if (options.body !== undefined) headers['Content-Type'] = 'application/json'
    if (options.file !== undefined) headers['Content-Type'] = 'application/octet-stream'
    if (withAuth && this.accessToken) headers.Authorization = `Bearer ${this.accessToken}`
    if (withAuth && !options.noHousehold && this.householdId) {
      headers['X-Household-ID'] = this.householdId
    }
    const init: RequestInit = { method, headers, credentials: 'omit', cache: 'no-store' }
    if (options.body !== undefined) init.body = JSON.stringify(options.body)
    // A file is sent as raw bytes; the server decides its type from them.
    if (options.file !== undefined) init.body = options.file
    try {
      return await this.fetchImpl(this.url(path, options.query), init)
    } catch (error) {
      throw new NetworkError(error)
    }
  }

  private url(path: string, query: Query = {}): string {
    const params = new URLSearchParams()
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined && value !== null && value !== '' && value !== false) {
        params.set(key, String(value))
      }
    }
    const qs = params.toString()
    return `${this.baseUrl}${path}${qs ? `?${qs}` : ''}`
  }

  private storeTokens(pair: TokenPair): void {
    if (typeof pair?.access_token !== 'string' || typeof pair?.refresh_token !== 'string') {
      throw new ApiError(502, 'bad_token_response', 'the server returned no tokens')
    }
    this.accessToken = pair.access_token
    try {
      this.storage?.setItem(REFRESH_KEY, pair.refresh_token)
    } catch {
      // Without storage the session simply will not survive a reload.
    }
  }

  private readRefreshToken(): string | null {
    try {
      return this.storage?.getItem(REFRESH_KEY) ?? null
    } catch {
      return null
    }
  }
}

const REFRESH_KEY = 'fet.refresh'

function safeSessionStorage(): Storage | null {
  try {
    return typeof sessionStorage === 'undefined' ? null : sessionStorage
  } catch {
    return null
  }
}

async function toApiError(response: Response): Promise<ApiError> {
  try {
    const body = (await response.json()) as {
      error?: { code?: string; message?: string; details?: unknown[] }
    }
    const err = body.error ?? {}
    return new ApiError(
      response.status,
      err.code ?? 'http_error',
      err.message ?? response.statusText,
      err.details ?? [],
    )
  } catch {
    return new ApiError(response.status, 'http_error', response.statusText)
  }
}
