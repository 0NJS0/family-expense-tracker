import { useId, useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError } from '../api/client'
import { useSession } from '../auth/session'
import { IconWallet } from '../components/icons'
import { useI18n } from '../i18n/context'
import { errorKey } from '../lib/errors'

const USERNAME = /^[a-z0-9][a-z0-9._@-]{2,63}$/

/**
 * Public page reached from an invitation link (/accept?token=…). The new
 * member chooses their own username and password; the admin never sees it.
 */
export function AcceptInvitePage({ token, onDone }: { token: string; onDone: () => void }) {
  const { t, lang, setLang } = useI18n()
  const { client } = useSession()
  const id = useId()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    const name = username.trim().toLowerCase()
    if (!USERNAME.test(name)) return setError(t('usernameHelp'))
    if (password.length < 8) return setError(t('passwordShort'))
    if (password !== confirm) return setError(t('passwordMismatch'))
    setPending(true)
    setError(null)
    try {
      await client.acceptInvitation({ token, username: name, password, preferred_language: lang })
      setDone(true)
      setPassword('')
      setConfirm('')
    } catch (err) {
      if (err instanceof ApiError && err.status === 404) setError(t('inviteInvalid'))
      else if (err instanceof ApiError && err.status === 409) setError(t('usernameTaken'))
      else if (err instanceof ApiError && err.status === 422) setError(t('usernameHelp'))
      else setError(t(errorKey(err)))
    } finally {
      setPending(false)
    }
  }

  return (
    <main className="login">
      <div className="login-card">
        <h1>
          <IconWallet />
          {t('appName')}
        </h1>
        <h2>{t('acceptTitle')}</h2>
        {done ? (
          <>
            <p className="success" role="status">
              {t('accepted')}
            </p>
            <div className="actions">
              <button type="button" className="primary" onClick={onDone}>
                {t('goToSignIn')}
              </button>
            </div>
          </>
        ) : (
          <form className="form" onSubmit={(e) => void submit(e)}>
            <p className="muted">{t('acceptIntro')}</p>
            <label htmlFor={`${id}-user`}>{t('username')}</label>
            <input
              id={`${id}-user`}
              autoComplete="username"
              autoCapitalize="none"
              required
              value={username}
              aria-describedby={`${id}-help`}
              onChange={(e) => setUsername(e.target.value)}
            />
            <span id={`${id}-help`} className="hint">
              {t('usernameHelp')}
            </span>
            <label htmlFor={`${id}-pass`}>{t('password')}</label>
            <input
              id={`${id}-pass`}
              type="password"
              autoComplete="new-password"
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
            <label htmlFor={`${id}-confirm`}>{t('confirmPassword')}</label>
            <input
              id={`${id}-confirm`}
              type="password"
              autoComplete="new-password"
              required
              value={confirm}
              onChange={(e) => setConfirm(e.target.value)}
            />
            {error ? (
              <p className="field-error" role="alert">
                {error}
              </p>
            ) : null}
            <button type="submit" className="primary" disabled={pending}>
              {t('acceptButton')}
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
        )}
      </div>
    </main>
  )
}
