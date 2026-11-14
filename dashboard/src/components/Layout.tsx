import { useId, useState } from 'react'
import { NavLink, Outlet } from 'react-router'
import { useSession } from '../auth/session'
import { useThemePref } from '../hooks/useColorScheme'
import { useI18n } from '../i18n/context'
import { setThemePref } from '../lib/theme'
import type { ThemePref } from '../lib/theme'
import {
  IconAnalytics,
  IconBudget,
  IconRepeat,
  IconSpark,
  IconFamily,
  IconLogout,
  IconMenu,
  IconOverview,
  IconTransactions,
  IconWallet,
} from './icons'

export function Layout() {
  const { t, lang, setLang } = useI18n()
  const { me, household, role, switchHousehold, logout } = useSession()
  const theme = useThemePref()
  const memberships = me?.memberships ?? []
  const [open, setOpen] = useState(false)
  const navId = useId()

  const initials = (me?.user.display_name ?? '?').trim().slice(0, 1).toUpperCase()

  return (
    <div className={`shell${open ? ' nav-open' : ''}`}>
      <header className="mobilebar">
        <button
          type="button"
          className="icon-button"
          aria-expanded={open}
          aria-controls={navId}
          onClick={() => setOpen(!open)}
        >
          <IconMenu />
          <span className="visually-hidden">{t('menu')}</span>
        </button>
        <span className="brand-name">{t('appName')}</span>
      </header>

      <aside className="sidebar" id={navId}>
        <div className="brand">
          <span className="brand-mark" aria-hidden="true">
            <IconWallet />
          </span>
          <span className="brand-name">{t('appName')}</span>
        </div>

        {memberships.length > 1 ? (
          <label className="household-switch">
            <span className="visually-hidden">{t('household')}</span>
            <select value={household?.id ?? ''} onChange={(e) => void switchHousehold(e.target.value)}>
              {memberships.map((m) => (
                <option key={m.household_id} value={m.household_id}>
                  {m.household_name}
                </option>
              ))}
            </select>
          </label>
        ) : (
          <p className="household-name">{household?.name}</p>
        )}

        {/* Choosing a page closes the mobile menu. */}
        <nav aria-label="Main" className="nav" onClick={() => setOpen(false)}>
          <NavLink to="/" end>
            <IconOverview />
            {t('navOverview')}
          </NavLink>
          <NavLink to="/analytics">
            <IconAnalytics />
            {t('navAnalytics')}
          </NavLink>
          <NavLink to="/transactions">
            <IconTransactions />
            {t('navTransactions')}
          </NavLink>
          <NavLink to="/budgets">
            <IconBudget />
            {t('navBudgets')}
          </NavLink>
          <NavLink to="/recurring">
            <IconRepeat />
            {t('navRecurring')}
          </NavLink>
          <NavLink to="/summaries">
            <IconSpark />
            {t('navSummaries')}
          </NavLink>
          <NavLink to="/admin">
            <IconFamily />
            {t('navAdmin')}
          </NavLink>
        </nav>

        <div className="sidebar-footer">
          <div className="prefs">
            <button
              type="button"
              className="chip"
              lang={lang === 'bn' ? 'en' : 'bn'}
              onClick={() => setLang(lang === 'bn' ? 'en' : 'bn')}
            >
              {t('language')}
            </button>
            <label className="theme-select">
              <span className="visually-hidden">{t('theme')}</span>
              <select value={theme} onChange={(e) => setThemePref(e.target.value as ThemePref)}>
                <option value="system">{t('themeSystem')}</option>
                <option value="light">{t('themeLight')}</option>
                <option value="dark">{t('themeDark')}</option>
              </select>
            </label>
          </div>
          <div className="user-card">
            <span className="avatar" aria-hidden="true">
              {initials}
            </span>
            <span className="user-meta">
              <span className="user-name">{me?.user.display_name}</span>
              {role ? <span className="user-role">{t(`role_${role}`)}</span> : null}
            </span>
            <button type="button" className="icon-button" onClick={() => void logout()} title={t('logout')}>
              <IconLogout />
              <span className="visually-hidden">{t('logout')}</span>
            </button>
          </div>
        </div>
      </aside>
      {open ? <div className="scrim" onClick={() => setOpen(false)} aria-hidden="true" /> : null}

      <main className="content">
        <Outlet />
      </main>
    </div>
  )
}
