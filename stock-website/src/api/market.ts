import {
  TickerSnapshot,
  OrderBook,
  Trade,
  Order,
  Portfolio,
  PlaceOrderRequest,
  OrderResult,
} from '../types'
import { get, post, del } from './client'

export async function fetchTickers() {
  const res = await get<{ tickers: TickerSnapshot[] }>('/api/v1/tickers')
  return res.tickers
}

export function fetchTicker(ticker: string) {
  return get<TickerSnapshot>(`/api/v1/ticker/${ticker}`)
}

export function fetchOrderBook(ticker: string, depth = 20) {
  return get<OrderBook>(`/api/v1/orderbook/${ticker}/${depth}`)
}

export async function fetchTrades(ticker: string, limit = 50) {
  const res = await get<{ trades: Trade[] }>(`/api/v1/trades/${ticker}/${limit}`)
  return res.trades
}

export async function fetchOrders() {
  const res = await get<{ orders: Order[] }>('/api/v1/orders')
  return res.orders
}

export function placeOrder(req: PlaceOrderRequest) {
  return post<OrderResult>('/api/v1/orders', req)
}

export function cancelOrder(orderId: string) {
  return del<{ success: boolean }>(`/api/v1/order/${orderId}`)
}

export function fetchPortfolio() {
  return get<Portfolio>('/api/v1/portfolio')
}

export interface CandleData {
  candles: Array<{
    openTime: number
    open: number
    high: number
    low: number
    close: number
    volume: number
    tradeCount: number
  }>
}

export function fetchCandles(ticker: string, interval: string, limit = 200) {
  return get<CandleData>(`/api/v1/candles/${ticker}/${interval}/${limit}`)
}
