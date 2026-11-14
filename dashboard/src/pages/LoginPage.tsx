import { useId, useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError } from '../api/client'
import { useSession } from '../auth/session'
import { errorKey } from '../lib/errors'
import { useI18n } from '../i18n/context'

export function LoginPage() {
  const { t, lang, setLang } = useI18n()
  const { login, expired } = useSession()
  const id = useId()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setPending(true)
    setError(null)
    try {
      await login(username, password)
    } catch (err) {
      setError(
        err instanceof ApiError && (err.status === 401 || err.status === 422)
          ? t('loginFailed')
          : t(errorKey(err)),
      )
      setPassword('')
    } finally {
      setPending(false)
    }
  }

  return (
    <main className="login">
      <form className="form login-card" onSubmit={submit}>
        <h1>{t('appName')}</h1>
        <h2>{t('loginTitle')}</h2>
        {expired && !error ? <p className="notice">{t('sessionExpired')}</p> : null}
        <label htmlFor={`${id}-user`}>{t('username')}</label>
        <input
          id={`${id}-user`}
          autoComplete="username"
          autoCapitalize="none"
          required
          value={username}
          onChange={(e) => setUsername(e.target.value)}
        />
        <label htmlFor={`${id}-pass`}>{t('password')}</label>
        <input
          id={`${id}-pass`}
          type="password"
          autoComplete="current-password"
          required
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        {error ? (
          <p className="field-error" role="alert">
            {error}
          </p>
        ) : null}
        <button type="submit" className="primary" disabled={pending}>
          {pending ? t('signingIn') : t('signIn')}
        </button>
        <button
          type="button"
          className="link"
          lang={lang === 'bn' ? 'en' : 'bn'}
          onClick={() => setLang(lang === 'bn' ? 'en' : 'bn')}
        >
          {t('language')}
        </button>
      </form>
    </main>
  )
}
