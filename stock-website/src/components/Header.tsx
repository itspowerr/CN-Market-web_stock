import { NavLink } from 'react-router-dom'
import { useAuth } from '../store/auth'

export default function Header() {
  const { username, logout } = useAuth()

  return (
    <header className="bg-surface-200 border-b border-white/5">
      <div className="max-w-7xl mx-auto px-4 h-14 flex items-center justify-between">
        <div className="flex items-center gap-6">
          <h1 className="text-lg font-bold text-white tracking-tight">
            CN Market
          </h1>
          <nav className="flex gap-4 text-sm">
            <NavLink
              to="/dashboard"
              className={({ isActive }) =>
                isActive ? 'text-accent-blue' : 'text-gray-400 hover:text-white transition-colors'
              }
            >
              Trade
            </NavLink>
            <NavLink
              to="/portfolio"
              className={({ isActive }) =>
                isActive ? 'text-accent-blue' : 'text-gray-400 hover:text-white transition-colors'
              }
            >
              Portfolio
            </NavLink>
          </nav>
        </div>
        <div className="flex items-center gap-3">
          <span className="text-sm text-gray-400">
            {username || 'Player'}
          </span>
          <button
            onClick={logout}
            className="text-xs text-gray-500 hover:text-accent-red transition-colors"
          >
            Logout
          </button>
        </div>
      </div>
    </header>
  )
}
