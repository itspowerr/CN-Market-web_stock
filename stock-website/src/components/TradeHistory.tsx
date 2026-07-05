import { useEffect, useState } from 'react'
import { Trade } from '../types'
import { fetchTrades } from '../api/market'
import { wsClient } from '../api/websocket'

interface Props {
  ticker: string
}

export default function TradeHistory({ ticker }: Props) {
  const [trades, setTrades] = useState<Trade[]>([])

  useEffect(() => {
    fetchTrades(ticker).then(setTrades).catch(() => {})
  }, [ticker])

  useEffect(() => {
    const channel = `trades.${ticker}`
    const unsub = wsClient.on('trade', (msg) => {
      if (msg.ticker !== ticker) return
      setTrades((prev) => [msg.data as Trade, ...prev].slice(0, 50))
    })
    wsClient.subscribe(channel)
    return () => {
      unsub()
      wsClient.unsubscribe(channel)
    }
  }, [ticker])

  return (
    <div className="bg-surface-200 rounded-xl overflow-hidden h-full flex flex-col">
      <div className="px-4 py-2 border-b border-white/5 text-sm font-medium text-gray-400">
        Recent Trades
      </div>
      <div className="flex-1 overflow-y-auto text-xs font-mono">
        {trades.slice(0, 30).map((t, i) => (
          <div
            key={t.id}
            className={`flex items-center justify-between px-4 py-1 ${
              i % 2 === 0 ? 'bg-white/[0.02]' : ''
            }`}
          >
            <span className="text-gray-400">
              {new Date(t.timestamp).toLocaleTimeString()}
            </span>
            <span className="text-white">{t.price.toFixed(2)}</span>
            <span className="text-gray-300">{t.quantity}</span>
          </div>
        ))}
        {trades.length === 0 && (
          <div className="px-4 py-8 text-center text-gray-500 text-xs">
            No trades yet
          </div>
        )}
      </div>
    </div>
  )
}
