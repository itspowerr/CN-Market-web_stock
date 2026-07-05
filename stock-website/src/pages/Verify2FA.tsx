import { useState } from 'react'
import { useNavigate, useLocation } from 'react-router-dom'
import { authStep2 } from '../api/auth'
import { useAuth } from '../store/auth'

interface LocationState {
  uuid: string
  backupCodes?: string[]
}

export default function Verify2FA() {
  const [code, setCode] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const navigate = useNavigate()
  const location = useLocation()
  const { setSession } = useAuth()
  const state = location.state as LocationState | null

  if (!state?.uuid) {
    navigate('/login', { replace: true })
    return null
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    if (!code.trim()) {
      setError('Enter your 2FA code')
      return
    }
    setLoading(true)
    try {
      const res = await authStep2(state.uuid, code.trim())
      setSession(res.sessionToken, res.playerName, res.uuid)
      navigate('/dashboard', { replace: true })
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Verification failed')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-surface-300 px-4">
      <div className="w-full max-w-md bg-surface-200 rounded-xl shadow-2xl p-8">
        <div className="text-center mb-8">
          <h1 className="text-2xl font-bold text-white mb-2">
            Two-Factor Authentication
          </h1>
          <p className="text-gray-400">
            Enter the code from your authenticator app
          </p>
        </div>

        {error && (
          <div className="bg-red-500/10 border border-red-500/30 text-red-400 px-4 py-3 rounded-lg mb-6 text-sm">
            {error}
          </div>
        )}

        {state.backupCodes && (
          <div className="bg-yellow-500/10 border border-yellow-500/30 text-yellow-400 px-4 py-3 rounded-lg mb-6 text-sm">
            <strong>Save these backup codes!</strong>
            <pre className="mt-1 text-xs">
              {state.backupCodes.join('\n')}
            </pre>
          </div>
        )}

        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label
              htmlFor="code"
              className="block text-sm font-medium text-gray-300 mb-1"
            >
              Authenticator Code
            </label>
            <input
              id="code"
              type="text"
              value={code}
              onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
              placeholder="000000"
              maxLength={6}
              className="w-full px-4 py-3 bg-surface-100 border border-surface-100 rounded-lg text-white placeholder-gray-500 focus:outline-none focus:border-accent-blue transition-colors text-center text-2xl tracking-[0.5em]"
              autoFocus
            />
          </div>

          <button
            type="submit"
            disabled={loading || code.length !== 6}
            className="w-full py-3 bg-accent-blue text-surface-300 font-semibold rounded-lg hover:brightness-110 disabled:opacity-50 transition-all"
          >
            {loading ? 'Verifying...' : 'Verify'}
          </button>
        </form>
      </div>
    </div>
  )
}
