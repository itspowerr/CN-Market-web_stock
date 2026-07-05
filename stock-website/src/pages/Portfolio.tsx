import { useEffect, useState } from 'react'
import { Portfolio as PortfolioType } from '../types'
import { fetchPortfolio } from '../api/market'

export default function PortfolioPage() {
  const [portfolio, setPortfolio] = useState<PortfolioType | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    fetchPortfolio()
      .then(setPortfolio)
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [])

  if (loading) {
    return (
      <div className="max-w-4xl mx-auto p-8 text-center text-gray-500">
        Loading...
      </div>
    )
  }

  return (
    <div className="max-w-4xl mx-auto p-8">
      <h1 className="text-2xl font-bold text-white mb-6">Portfolio</h1>

      {/* Summary cards */}
      <div className="grid grid-cols-4 gap-4 mb-8">
        <div className="bg-surface-200 rounded-xl p-4">
          <div className="text-xs text-gray-500 mb-1">Balance</div>
          <div className="text-lg font-mono text-white">
            ${portfolio?.balance.toFixed(2) ?? '0.00'}
          </div>
        </div>
        <div className="bg-surface-200 rounded-xl p-4">
          <div className="text-xs text-gray-500 mb-1">Portfolio Value</div>
          <div className="text-lg font-mono text-white">
            ${portfolio?.totalValue.toFixed(2) ?? '0.00'}
          </div>
        </div>
        <div className="bg-surface-200 rounded-xl p-4">
          <div className="text-xs text-gray-500 mb-1">Total P&L</div>
          <div
            className={`text-lg font-mono ${
              (portfolio?.totalPnl ?? 0) >= 0
                ? 'text-accent-green'
                : 'text-accent-red'
            }`}
          >
            {(portfolio?.totalPnl ?? 0) >= 0 ? '+' : ''}$
            {(portfolio?.totalPnl ?? 0).toFixed(2)}
          </div>
        </div>
        <div className="bg-surface-200 rounded-xl p-4">
          <div className="text-xs text-gray-500 mb-1">P&L %</div>
          <div
            className={`text-lg font-mono ${
              (portfolio?.totalPnlPercent ?? 0) >= 0
                ? 'text-accent-green'
                : 'text-accent-red'
            }`}
          >
            {(portfolio?.totalPnlPercent ?? 0) >= 0 ? '+' : ''}
            {(portfolio?.totalPnlPercent ?? 0).toFixed(2)}%
          </div>
        </div>
      </div>

      {/* Holdings table */}
      <div className="bg-surface-200 rounded-xl overflow-hidden">
        <div className="px-4 py-3 border-b border-white/5 text-sm font-medium text-gray-400">
          Holdings
        </div>
        <table className="w-full text-sm">
          <thead>
            <tr className="text-gray-500 border-b border-white/5 text-xs">
              <th className="px-4 py-2 text-left">Ticker</th>
              <th className="px-4 py-2 text-right">Shares</th>
              <th className="px-4 py-2 text-right">Avg Entry</th>
              <th className="px-4 py-2 text-right">Current</th>
              <th className="px-4 py-2 text-right">Value</th>
              <th className="px-4 py-2 text-right">P&L</th>
              <th className="px-4 py-2 text-right">P&L %</th>
            </tr>
          </thead>
          <tbody>
            {portfolio?.holdings.map((h) => (
              <tr
                key={h.ticker}
                className="border-b border-white/5 hover:bg-white/[0.02]"
              >
                <td className="px-4 py-3 text-white font-medium">{h.ticker}</td>
                <td className="px-4 py-3 text-right text-gray-300">
                  {h.shares}
                </td>
                <td className="px-4 py-3 text-right font-mono text-gray-300">
                  ${h.avgEntryPrice.toFixed(2)}
                </td>
                <td className="px-4 py-3 text-right font-mono text-white">
                  ${h.currentPrice.toFixed(2)}
                </td>
                <td className="px-4 py-3 text-right font-mono text-white">
                  ${h.value.toFixed(2)}
                </td>
                <td
                  className={`px-4 py-3 text-right font-mono ${
                    h.pnl >= 0 ? 'text-accent-green' : 'text-accent-red'
                  }`}
                >
                  {h.pnl >= 0 ? '+' : ''}${h.pnl.toFixed(2)}
                </td>
                <td
                  className={`px-4 py-3 text-right font-mono ${
                    h.pnlPercent >= 0 ? 'text-accent-green' : 'text-accent-red'
                  }`}
                >
                  {h.pnlPercent >= 0 ? '+' : ''}
                  {h.pnlPercent.toFixed(2)}%
                </td>
              </tr>
            ))}
            {(!portfolio?.holdings || portfolio.holdings.length === 0) && (
              <tr>
                <td colSpan={7} className="px-4 py-12 text-center text-gray-500">
                  No holdings. Start trading!
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
