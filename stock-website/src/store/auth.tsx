import { createContext, useContext, useState, useEffect, useCallback, ReactNode } from 'react'
import { authStatus, authLogout } from '../api/auth'

interface AuthContextValue {
  sessionId: string | null
  username: string | null
  uuid: string | null
  loading: boolean
  setSession: (sessionId: string, username: string, uuid: string) => void
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthContextValue>({
  sessionId: null,
  username: null,
  uuid: null,
  loading: true,
  setSession: () => {},
  logout: async () => {},
})

export function AuthProvider({ children }: { children: ReactNode }) {
  const [sessionId, setSessionId] = useState<string | null>(() =>
    sessionStorage.getItem('session_id'),
  )
  const [username, setUsername] = useState<string | null>(null)
  const [uuid, setUuid] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    if (!sessionId) {
      setLoading(false)
      return
    }
    authStatus()
      .then((res) => {
        if (res.authenticated && res.playerName) {
          setUsername(res.playerName)
          setUuid(res.uuid || null)
        } else {
          sessionStorage.removeItem('session_id')
          setSessionId(null)
        }
      })
      .catch(() => {
        sessionStorage.removeItem('session_id')
        setSessionId(null)
      })
      .finally(() => setLoading(false))
  }, [sessionId])

  const setSession = useCallback(
    (sid: string, user: string, uid: string) => {
      sessionStorage.setItem('session_id', sid)
      setSessionId(sid)
      setUsername(user)
      setUuid(uid)
    },
    [],
  )

  const logout = useCallback(async () => {
    try {
      await authLogout()
    } catch {
      // ignore
    }
    sessionStorage.removeItem('session_id')
    setSessionId(null)
    setUsername(null)
    setUuid(null)
  }, [])

  return (
    <AuthContext.Provider
      value={{ sessionId, username, uuid, loading, setSession, logout }}
    >
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  return useContext(AuthContext)
}
