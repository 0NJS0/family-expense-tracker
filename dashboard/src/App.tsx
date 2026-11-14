import { QueryClientProvider } from '@tanstack/react-query'
import { useState } from 'react'
import { BrowserRouter, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router'
import { ApiClient } from './api/client'
import { createQueryClient } from './api/queryClient'
import { useSession } from './auth/session'
import { SessionProvider } from './auth/SessionContext'
import { Layout } from './components/Layout'
import { Loading } from './components/common'
import { useI18n } from './i18n/context'
import { I18nProvider } from './i18n/I18nContext'
import { AcceptInvitePage } from './pages/AcceptInvitePage'
import { AdminPage } from './pages/AdminPage'
import { AnalyticsPage } from './pages/AnalyticsPage'
import { BudgetsPage } from './pages/BudgetsPage'
import { RecurringPage } from './pages/RecurringPage'
import { SummariesPage } from './pages/SummariesPage'
import { LoginPage } from './pages/LoginPage'
import { OverviewPage } from './pages/OverviewPage'
import { TransactionsPage } from './pages/TransactionsPage'

export function AppRoutes() {
  const { status, household } = useSession()
  const { t } = useI18n()
  const location = useLocation()
  const navigate = useNavigate()
  // Invitation links work whether or not someone is signed in on this browser.
  if (location.pathname === '/accept') {
    const token = new URLSearchParams(location.search).get('token') ?? ''
    return <AcceptInvitePage token={token} onDone={() => navigate('/', { replace: true })} />
  }
  if (status === 'checking') return <Loading />
  if (status === 'anonymous') return <LoginPage />
  if (!household) {
    return (
      <main className="login">
        <p className="state">{t('noHousehold')}</p>
      </main>
    )
  }
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<OverviewPage />} />
        <Route path="analytics" element={<AnalyticsPage />} />
        <Route path="transactions" element={<TransactionsPage />} />
        <Route path="budgets" element={<BudgetsPage />} />
        <Route path="recurring" element={<RecurringPage />} />
        <Route path="summaries" element={<SummariesPage />} />
        <Route path="admin" element={<AdminPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  )
}

export function App() {
  const [client] = useState(() => new ApiClient())
  const [queryClient] = useState(createQueryClient)
  return (
    <QueryClientProvider client={queryClient}>
      <I18nProvider>
        <SessionProvider client={client}>
          <BrowserRouter>
            <AppRoutes />
          </BrowserRouter>
        </SessionProvider>
      </I18nProvider>
    </QueryClientProvider>
  )
}
