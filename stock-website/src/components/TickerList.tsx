import { useEffect, useState } from 'react'
import { TickerSnapshot } from '../types'
import { fetchTickers } from '../api/market'
import { wsClient } from '../api/websocket'

interface Props {
  selected: string
  onSelect: (ticker: string) => void
}

export default function TickerList({ selected, onSelect }: Props) {
  const [tickers, setTickers] = useState<TickerSnapshot[]>([])
  const [search, setSearch] = useState('')

  useEffect(() => {
    fetchTickers().then(setTickers).catch(() => {})
  }, [])

  useEffect(() => {
    const unsub = wsClient.on('tickers', (msg) => {
      const data = msg.data as TickerSnapshot[]
      if (data) setTickers(data)
    })
    wsClient.subscribe('tickers')
    return () => {
      unsub()
      wsClient.unsubscribe('tickers')
    }
  }, [])

  const filtered = tickers.filter((t) =>
    t.ticker.toLowerCase().includes(search.toLowerCase()),
  )

  return (
    <div className="bg-surface-200 rounded-xl overflow-hidden flex flex-col h-full">
      <div className="p-3 border-b border-white/5">
        <input
          type="text"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Search tickers..."
          className="w-full px-3 py-2 bg-surface-100 border border-surface-100 rounded-lg text-sm text-white placeholder-gray-500 focus:outline-none focus:border-accent-blue transition-colors"
        />
      </div>
      <div className="flex-1 overflow-y-auto divide-y divide-white/5">
        {filtered.map((t) => (
          <button
            key={t.ticker}
            onClick={() => onSelect(t.ticker)}
            className={`w-full px-4 py-3 flex items-center justify-between hover:bg-white/5 transition-colors text-left ${
              selected === t.ticker ? 'bg-accent-blue/10 border-l-2 border-accent-blue' : ''
            }`}
          >
            <div>
              <div className="text-sm font-medium text-white">
                {t.ticker}
              </div>
              <div className="text-xs text-gray-500">
                Vol: {t.volume24h.toLocaleString()}
              </div>
            </div>
            <div className="text-right">
              <div className="text-sm font-mono text-white">
                ${t.lastPrice.toFixed(2)}
              </div>
              <div
                className={`text-xs font-mono ${
                  t.change24hPct >= 0 ? 'text-accent-green' : 'text-accent-red'
                }`}
              >
                {t.change24hPct >= 0 ? '+' : ''}
                {t.change24hPct.toFixed(2)}%
              </div>
            </div>
          </button>
        ))}
        {filtered.length === 0 && (
          <div className="px-4 py-8 text-center text-gray-500 text-sm">
            No tickers found
          </div>
        )}
      </div>
    </div>
  )
}
