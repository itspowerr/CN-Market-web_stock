import { useState } from 'react'
import { OrderType, OrderSide } from '../types'
import { placeOrder } from '../api/market'

interface Props {
  ticker: string
  lastPrice: number
  onPlaced: () => void
}

export default function OrderForm({ ticker, lastPrice, onPlaced }: Props) {
  const [side, setSide] = useState<OrderSide>('BUY')
  const [type, setType] = useState<OrderType>('MARKET')
  const [price, setPrice] = useState('')
  const [stopPrice, setStopPrice] = useState('')
  const [quantity, setQuantity] = useState('1')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    const qty = parseInt(quantity)
    if (isNaN(qty) || qty <= 0) {
      setError('Invalid quantity')
      return
    }

    setLoading(true)
    try {
      const result = await placeOrder({
        ticker,
        side,
        type,
        price: type === 'LIMIT' ? parseFloat(price) || 0 : undefined,
        quantity: qty,
      })
      if (!result.success) {
        setError(result.error || 'Order failed')
      } else {
        setQuantity('1')
        setPrice('')
        setStopPrice('')
        onPlaced()
      }
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Order failed')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="bg-surface-200 rounded-xl p-4">
      <h3 className="text-sm font-medium text-gray-400 mb-3">Place Order</h3>

      {error && (
        <div className="bg-red-500/10 border border-red-500/30 text-red-400 px-3 py-2 rounded-lg mb-3 text-xs">
          {error}
        </div>
      )}

      <form onSubmit={handleSubmit} className="space-y-3">
        {/* Side toggle */}
        <div className="flex rounded-lg overflow-hidden border border-white/10">
          <button
            type="button"
            onClick={() => setSide('BUY')}
            className={`flex-1 py-2 text-sm font-medium transition-colors ${
              side === 'BUY'
                ? 'bg-accent-green text-surface-300'
                : 'bg-transparent text-gray-400 hover:text-white'
            }`}
          >
            Buy
          </button>
          <button
            type="button"
            onClick={() => setSide('SELL')}
            className={`flex-1 py-2 text-sm font-medium transition-colors ${
              side === 'SELL'
                ? 'bg-accent-red text-surface-300'
                : 'bg-transparent text-gray-400 hover:text-white'
            }`}
          >
            Sell
          </button>
        </div>

        {/* Order type */}
        <select
          value={type}
          onChange={(e) => setType(e.target.value as OrderType)}
          className="w-full px-3 py-2 bg-surface-100 border border-surface-100 rounded-lg text-sm text-white focus:outline-none focus:border-accent-blue"
        >
          <option value="MARKET">Market</option>
          <option value="LIMIT">Limit</option>
          <option value="STOP_LOSS">Stop Loss</option>
          <option value="TAKE_PROFIT">Take Profit</option>
        </select>

        {/* Price (for limit orders) */}
        {(type === 'LIMIT' || type === 'STOP_LOSS' || type === 'TAKE_PROFIT') && (
          <div>
            <label className="text-xs text-gray-500 mb-1 block">
              {type === 'LIMIT' ? 'Limit Price' : type === 'STOP_LOSS' ? 'Stop Price' : 'Target Price'}
            </label>
            <input
              type="number"
              value={type === 'LIMIT' ? price : stopPrice}
              onChange={(e) => {
                if (type === 'LIMIT') setPrice(e.target.value)
                else setStopPrice(e.target.value)
              }}
              placeholder={type === 'LIMIT' ? '0.00' : '0.00'}
              step="0.01"
              min="0"
              className="w-full px-3 py-2 bg-surface-100 border border-surface-100 rounded-lg text-sm text-white placeholder-gray-500 focus:outline-none focus:border-accent-blue"
            />
          </div>
        )}

        {/* Quantity */}
        <div>
          <label className="text-xs text-gray-500 mb-1 block">Quantity</label>
          <input
            type="number"
            value={quantity}
            onChange={(e) => setQuantity(e.target.value)}
            placeholder="1"
            min="1"
            className="w-full px-3 py-2 bg-surface-100 border border-surface-100 rounded-lg text-sm text-white placeholder-gray-500 focus:outline-none focus:border-accent-blue"
          />
        </div>

        {lastPrice > 0 && (
          <div className="text-xs text-gray-500">
            Reference: ${lastPrice.toFixed(2)}
          </div>
        )}

        <button
          type="submit"
          disabled={loading}
          className={`w-full py-2.5 rounded-lg font-semibold text-sm transition-all ${
            side === 'BUY'
              ? 'bg-accent-green text-surface-300 hover:brightness-110'
              : 'bg-accent-red text-white hover:brightness-110'
          } disabled:opacity-50`}
        >
          {loading
            ? 'Placing...'
            : `${side === 'BUY' ? 'Buy' : 'Sell'} ${quantity || '0'} ${ticker}`}
        </button>
      </form>
    </div>
  )
}
