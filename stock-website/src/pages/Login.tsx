import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { authStep1 } from '../api/auth'

export default function Login() {
  const [apiKey, setApiKey] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const navigate = useNavigate()

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    if (!apiKey.trim()) {
      setError('Please enter your API key')
      return
    }
    setLoading(true)
    try {
      const res = await authStep1(apiKey.trim())
      navigate('/verify', {
        state: { uuid: res.uuid, backupCodes: res.backupCodes ? res.backupCodes.split(', ') : undefined },
      })
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Authentication failed')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-surface-300 px-4">
      <div className="w-full max-w-md bg-surface-200 rounded-xl shadow-2xl p-8">
        <div className="text-center mb-8">
          <h1 className="text-3xl font-bold text-white mb-2">
            CraftNepal Market
          </h1>
          <p className="text-gray-400">
            Enter your API key from the game
          </p>
        </div>

        {error && (
          <div className="bg-red-500/10 border border-red-500/30 text-red-400 px-4 py-3 rounded-lg mb-6 text-sm">
            {error}
          </div>
        )}

        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label
              htmlFor="apiKey"
              className="block text-sm font-medium text-gray-300 mb-1"
            >
              API Key
            </label>
            <input
              id="apiKey"
              type="password"
              value={apiKey}
              onChange={(e) => setApiKey(e.target.value)}
              placeholder="mk_..."
              className="w-full px-4 py-3 bg-surface-100 border border-surface-100 rounded-lg text-white placeholder-gray-500 focus:outline-none focus:border-accent-blue transition-colors"
              autoFocus
            />
          </div>

          <button
            type="submit"
            disabled={loading}
            className="w-full py-3 bg-accent-blue text-surface-300 font-semibold rounded-lg hover:brightness-110 disabled:opacity-50 transition-all"
          >
            {loading ? 'Verifying...' : 'Continue'}
          </button>
        </form>

        <p className="text-gray-500 text-xs text-center mt-6">
          Use /market apikey generate in-game to get your key
        </p>
      </div>
    </div>
  )
}
