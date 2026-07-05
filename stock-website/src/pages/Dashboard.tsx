import { useState, useCallback } from 'react'
import TickerList from '../components/TickerList'
import Chart from '../components/Chart'
import OrderBook from '../components/OrderBook'
import TradeHistory from '../components/TradeHistory'
import OrderForm from '../components/OrderForm'
import OrderList from '../components/OrderList'
import { TickerSnapshot } from '../types'
import { fetchTicker } from '../api/market'
import { wsClient } from '../api/websocket'
import { useEffect } from 'react'

export default function Dashboard() {
  const [selectedTicker, setSelectedTicker] = useState('DIAMOND')
  const [tickerSnapshot, setTickerSnapshot] = useState<TickerSnapshot | null>(null)

  useEffect(() => {
    fetchTicker(selectedTicker).then(setTickerSnapshot).catch(() => {})
  }, [selectedTicker])

  useEffect(() => {
    const unsub = wsClient.on('tickers', (msg) => {
      const data = msg.data as TickerSnapshot[]
      if (data) {
        const found = data.find((t) => t.ticker === selectedTicker)
        if (found) setTickerSnapshot(found)
      }
    })
    return () => { unsub() }
  }, [selectedTicker])

  const handleTickerSelect = useCallback((ticker: string) => {
    setSelectedTicker(ticker)
  }, [])

  const handleOrderPlaced = useCallback(() => {
    // refresh order book and ticker
    fetchTicker(selectedTicker).then(setTickerSnapshot).catch(() => {})
  }, [selectedTicker])

  return (
    <div className="max-w-7xl mx-auto p-4 h-[calc(100vh-56px)] flex flex-col gap-4">
      {/* Top section — 3-column layout */}
      <div className="flex gap-4 flex-1 min-h-0">
        {/* Ticker List */}
        <div className="w-64 shrink-0">
          <TickerList selected={selectedTicker} onSelect={handleTickerSelect} />
        </div>

        {/* Chart */}
        <div className="flex-1 min-w-0">
          <div className="bg-surface-200 rounded-xl h-full flex flex-col">
            <div className="px-4 py-2 border-b border-white/5 flex items-center justify-between">
              <div>
                <span className="text-sm font-medium text-white">
                  {selectedTicker}
                </span>
                {tickerSnapshot && (
                  <span className="ml-3 text-sm font-mono text-white">
                    ${tickerSnapshot.lastPrice.toFixed(2)}
                  </span>
                )}
              </div>
              {tickerSnapshot && (
                <div className="flex items-center gap-4 text-xs font-mono">
                  <span className="text-gray-400">
                    H: ${tickerSnapshot.high24h.toFixed(2)}
                  </span>
                  <span className="text-gray-400">
                    L: ${tickerSnapshot.low24h.toFixed(2)}
                  </span>
                  <span className="text-gray-400">
                    Vol: {tickerSnapshot.volume24h.toLocaleString()}
                  </span>
                  <span
                    className={
                      tickerSnapshot.change24hPct >= 0
                        ? 'text-accent-green'
                        : 'text-accent-red'
                    }
                  >
                    {tickerSnapshot.change24hPct >= 0 ? '+' : ''}
                    {tickerSnapshot.change24hPct.toFixed(2)}%
                  </span>
                </div>
              )}
            </div>
            <div className="flex-1">
              <Chart ticker={selectedTicker} />
            </div>
          </div>
        </div>

        {/* Right panel */}
        <div className="w-64 shrink-0 flex flex-col gap-4 min-h-0">
          <div className="flex-1 min-h-0">
            <OrderBook ticker={selectedTicker} />
          </div>
          <div className="flex-1 min-h-0">
            <TradeHistory ticker={selectedTicker} />
          </div>
        </div>
      </div>

      {/* Bottom section — Order form + Open Orders */}
      <div className="flex gap-4 shrink-0">
        <div className="w-64 shrink-0">
          <OrderForm
            ticker={selectedTicker}
            lastPrice={tickerSnapshot?.lastPrice || 0}
            onPlaced={handleOrderPlaced}
          />
        </div>
        <div className="flex-1 min-w-0">
          <OrderList />
        </div>
      </div>
    </div>
  )
}
