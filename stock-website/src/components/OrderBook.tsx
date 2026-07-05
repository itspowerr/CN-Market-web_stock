import { useEffect, useState } from 'react'
import { OrderBookLevel } from '../types'
import { fetchOrderBook } from '../api/market'
import { wsClient } from '../api/websocket'

interface Props {
  ticker: string
}

export default function OrderBook({ ticker }: Props) {
  const [bids, setBids] = useState<OrderBookLevel[]>([])
  const [asks, setAsks] = useState<OrderBookLevel[]>([])

  useEffect(() => {
    fetchOrderBook(ticker).then((ob) => {
      setBids(ob.bids)
      setAsks(ob.asks)
    }).catch(() => {})
  }, [ticker])

  useEffect(() => {
    const channel = `orderbook.${ticker}`
    const unsub = wsClient.on('orderbook', (msg) => {
      if (msg.ticker !== ticker) return
      const data = msg.data as { bids: OrderBookLevel[]; asks: OrderBookLevel[] }
      if (data) {
        setBids(data.bids)
        setAsks(data.asks)
      }
    })
    wsClient.subscribe(channel)
    return () => {
      unsub()
      wsClient.unsubscribe(channel)
    }
  }, [ticker])

  const maxBidQty = Math.max(...bids.map((l) => l.quantity), 1)
  const maxAskQty = Math.max(...asks.map((l) => l.quantity), 1)

  return (
    <div className="bg-surface-200 rounded-xl overflow-hidden h-full flex flex-col">
      <div className="px-4 py-2 border-b border-white/5 text-sm font-medium text-gray-400">
        Order Book
      </div>
      <div className="flex-1 overflow-y-auto text-xs font-mono">
        {/* Asks (reversed so highest is at bottom) */}
        <div className="px-2">
          {[...asks].reverse().map((level, i) => (
            <div key={i} className="flex items-center justify-between relative py-0.5">
              <div
                className="absolute right-0 top-0 bottom-0 bg-accent-red/10 rounded"
                style={{ width: `${(level.quantity / maxAskQty) * 100}%` }}
              />
              <span className="relative z-10 text-accent-red pl-2">
                {level.price.toFixed(2)}
              </span>
              <span className="relative z-10 text-gray-300">
                {level.quantity}
              </span>
            </div>
          ))}
        </div>

        {/* Spread */}
        {bids.length > 0 && asks.length > 0 && (
          <div className="px-4 py-1 text-center text-gray-500 border-y border-white/5 my-1">
            Spread: ${(asks[0].price - bids[0].price).toFixed(2)}
          </div>
        )}

        {/* Bids */}
        <div className="px-2">
          {bids.map((level, i) => (
            <div key={i} className="flex items-center justify-between relative py-0.5">
              <div
                className="absolute left-0 top-0 bottom-0 bg-accent-green/10 rounded"
                style={{ width: `${(level.quantity / maxBidQty) * 100}%` }}
              />
              <span className="relative z-10 text-accent-green pl-2">
                {level.price.toFixed(2)}
              </span>
              <span className="relative z-10 text-gray-300">
                {level.quantity}
              </span>
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}
