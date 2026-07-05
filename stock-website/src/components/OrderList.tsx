import { useEffect, useState } from 'react'
import { Order } from '../types'
import { fetchOrders, cancelOrder } from '../api/market'

export default function OrderList() {
  const [orders, setOrders] = useState<Order[]>([])
  const [loading, setLoading] = useState(true)

  const load = () => {
    fetchOrders()
      .then(setOrders)
      .catch(() => {})
      .finally(() => setLoading(false))
  }

  useEffect(load, [])

  const handleCancel = async (id: string) => {
    try {
      await cancelOrder(id)
      load()
    } catch {
      // ignore
    }
  }

  const openOrders = orders.filter(
    (o) => o.status === 'OPEN' || o.status === 'PARTIALLY_FILLED',
  )

  return (
    <div className="bg-surface-200 rounded-xl overflow-hidden">
      <div className="px-4 py-2 border-b border-white/5 text-sm font-medium text-gray-400">
        Open Orders ({openOrders.length})
      </div>
      <div className="overflow-x-auto">
        <table className="w-full text-xs font-mono">
          <thead>
            <tr className="text-gray-500 border-b border-white/5">
              <th className="px-3 py-2 text-left">Ticker</th>
              <th className="px-3 py-2 text-left">Side</th>
              <th className="px-3 py-2 text-left">Type</th>
              <th className="px-3 py-2 text-right">Price</th>
              <th className="px-3 py-2 text-right">Qty</th>
              <th className="px-3 py-2 text-right">Filled</th>
              <th className="px-3 py-2"></th>
            </tr>
          </thead>
          <tbody>
            {openOrders.map((o) => (
              <tr key={o.id} className="border-b border-white/5 hover:bg-white/[0.02]">
                <td className="px-3 py-2 text-white font-medium">{o.ticker}</td>
                <td
                  className={`px-3 py-2 ${
                    o.side === 'BUY' ? 'text-accent-green' : 'text-accent-red'
                  }`}
                >
                  {o.side}
                </td>
                <td className="px-3 py-2 text-gray-400">{o.type}</td>
                <td className="px-3 py-2 text-right text-white">
                  {o.price > 0 ? `$${o.price.toFixed(2)}` : '-'}
                </td>
                <td className="px-3 py-2 text-right text-gray-300">{o.quantity}</td>
                <td className="px-3 py-2 text-right text-gray-400">
                  {o.filled}/{o.quantity}
                </td>
                <td className="px-3 py-2 text-right">
                  <button
                    onClick={() => handleCancel(o.id)}
                    className="text-accent-red hover:brightness-110 text-xs"
                  >
                    Cancel
                  </button>
                </td>
              </tr>
            ))}
            {openOrders.length === 0 && (
              <tr>
                <td colSpan={7} className="px-4 py-8 text-center text-gray-500 text-xs">
                  No open orders
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
