// Small inline icons (no icon library). Decorative: always paired with a text label.

const base = {
  width: 20,
  height: 20,
  viewBox: '0 0 24 24',
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 2,
  strokeLinecap: 'round' as const,
  strokeLinejoin: 'round' as const,
  'aria-hidden': true,
  focusable: false,
}

export const IconOverview = () => (
  <svg {...base}>
    <rect x="3" y="3" width="7" height="9" rx="1.5" />
    <rect x="14" y="3" width="7" height="5" rx="1.5" />
    <rect x="14" y="12" width="7" height="9" rx="1.5" />
    <rect x="3" y="16" width="7" height="5" rx="1.5" />
  </svg>
)

export const IconAnalytics = () => (
  <svg {...base}>
    <path d="M3 3v18h18" />
    <path d="M7 15l4-4 3 3 5-6" />
  </svg>
)

export const IconTransactions = () => (
  <svg {...base}>
    <path d="M7 4h13M7 12h13M7 20h13" />
    <circle cx="3.5" cy="4" r="1" />
    <circle cx="3.5" cy="12" r="1" />
    <circle cx="3.5" cy="20" r="1" />
  </svg>
)

export const IconFamily = () => (
  <svg {...base}>
    <circle cx="9" cy="8" r="3.5" />
    <path d="M2.5 20c.8-3.6 3.3-5.5 6.5-5.5s5.7 1.9 6.5 5.5" />
    <circle cx="17.5" cy="9" r="2.5" />
    <path d="M17 14.5c2.3.2 3.9 1.7 4.5 4.5" />
  </svg>
)

export const IconMenu = () => (
  <svg {...base}>
    <path d="M4 6h16M4 12h16M4 18h16" />
  </svg>
)

export const IconLogout = () => (
  <svg {...base}>
    <path d="M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3" />
    <path d="M10 16l-4-4 4-4M6 12h10" />
  </svg>
)

export const IconWallet = () => (
  <svg {...base}>
    <rect x="3" y="6" width="18" height="14" rx="2.5" />
    <path d="M3 10h18" />
    <circle cx="16.5" cy="15" r="1.2" />
    <path d="M6 6l9-3 1.5 3" />
  </svg>
)

export const IconArrowUp = () => (
  <svg {...base}>
    <path d="M12 19V5M5 12l7-7 7 7" />
  </svg>
)

export const IconArrowDown = () => (
  <svg {...base}>
    <path d="M12 5v14M19 12l-7 7-7-7" />
  </svg>
)

export const IconSwap = () => (
  <svg {...base}>
    <path d="M4 8h14l-3-3M20 16H6l3 3" />
  </svg>
)

export const IconBudget = () => (
  <svg {...base}>
    <circle cx="12" cy="12" r="9" />
    <path d="M12 3v9l6.5 6.2" />
  </svg>
)

export const IconRepeat = () => (
  <svg {...base}>
    <path d="M17 2l4 4-4 4" />
    <path d="M3 11v-1a4 4 0 0 1 4-4h14" />
    <path d="M7 22l-4-4 4-4" />
    <path d="M21 13v1a4 4 0 0 1-4 4H3" />
  </svg>
)

export const IconSpark = () => (
  <svg {...base}>
    <path d="M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z" />
    <path d="M19 17l.7 1.8 1.8.7-1.8.7L19 22l-.7-1.8-1.8-.7 1.8-.7z" />
  </svg>
)
