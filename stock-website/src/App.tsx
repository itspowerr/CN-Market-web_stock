import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { AuthProvider, useAuth } from './store/auth'
import Layout from './components/Layout'
import Login from './pages/Login'
import Verify2FA from './pages/Verify2FA'
import Dashboard from './pages/Dashboard'
import PortfolioPage from './pages/Portfolio'
import NotFound from './pages/NotFound'
import { useEffect } from 'react'
import { wsClient } from './api/websocket'

function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const { sessionId, loading } = useAuth()

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-surface-300">
        <div className="text-gray-500">Loading...</div>
      </div>
    )
  }

  if (!sessionId) return <Navigate to="/login" replace />
  return <>{children}</>
}

function WsManager() {
  const { sessionId } = useAuth()

  useEffect(() => {
    if (sessionId) wsClient.connect()
    else wsClient.disconnect()
  }, [sessionId])

  return null
}

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <WsManager />
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route path="/verify" element={<Verify2FA />} />
          <Route
            element={
              <ProtectedRoute>
                <Layout />
              </ProtectedRoute>
            }
          >
            <Route path="/dashboard" element={<Dashboard />} />
            <Route path="/portfolio" element={<PortfolioPage />} />
          </Route>
          <Route path="/" element={<Navigate to="/dashboard" replace />} />
          <Route path="*" element={<NotFound />} />
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  )
}
