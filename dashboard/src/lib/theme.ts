/**
 * Light / dark / automatic theme. The choice is stored per browser and applied
 * as <html data-theme="…">; "automatic" follows the operating system.
 */

export type ThemePref = 'light' | 'dark' | 'system'
export type Scheme = 'light' | 'dark'

const KEY = 'fet.theme'
const listeners = new Set<() => void>()
let pref: ThemePref = read()

function read(): ThemePref {
  try {
    const saved = localStorage.getItem(KEY)
    if (saved === 'light' || saved === 'dark' || saved === 'system') return saved
  } catch {
    // Storage unavailable: use the default.
  }
  return 'system'
}

function systemDark(): boolean {
  return typeof window !== 'undefined' && window.matchMedia?.('(prefers-color-scheme: dark)').matches === true
}

export function applyTheme(): void {
  const root = document.documentElement
  if (pref === 'system') delete root.dataset.theme
  else root.dataset.theme = pref
}

export function getThemePref(): ThemePref {
  return pref
}

export function setThemePref(next: ThemePref): void {
  pref = next
  try {
    localStorage.setItem(KEY, next)
  } catch {
    // Not persisted; still applies for this visit.
  }
  applyTheme()
  listeners.forEach((l) => l())
}

export function resolvedScheme(): Scheme {
  if (pref === 'system') return systemDark() ? 'dark' : 'light'
  return pref
}

export function subscribeTheme(callback: () => void): () => void {
  listeners.add(callback)
  const media = typeof window !== 'undefined' ? window.matchMedia?.('(prefers-color-scheme: dark)') : undefined
  media?.addEventListener('change', callback)
  return () => {
    listeners.delete(callback)
    media?.removeEventListener('change', callback)
  }
}
