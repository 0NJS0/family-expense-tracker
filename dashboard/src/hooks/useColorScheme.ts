import { useSyncExternalStore } from 'react'
import { getThemePref, resolvedScheme, subscribeTheme } from '../lib/theme'
import type { Scheme, ThemePref } from '../lib/theme'

/** The colour scheme actually shown (theme choice, or the OS setting when automatic). */
export function useColorScheme(): Scheme {
  return useSyncExternalStore(subscribeTheme, resolvedScheme, () => 'light')
}

export function useThemePref(): ThemePref {
  return useSyncExternalStore(subscribeTheme, getThemePref, () => 'system')
}
