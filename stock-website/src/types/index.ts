export interface TickerSnapshot {
  ticker: string
  lastPrice: number
  bid: number
  ask: number
  spread: number
  change24hPct: number
  volume24h: number
  high24h: number
  low24h: number
}

export interface OrderBookLevel {
  price: number
  quantity: number
  orders: number
}

export interface OrderBook {
  ticker: string
  bids: OrderBookLevel[]
  asks: OrderBookLevel[]
  timestamp: number
}

export interface Trade {
  id: string
  ticker: string
  price: number
  quantity: number
  buyerUuid: string
  sellerUuid: string
  timestamp: number
}

export type OrderType = 'MARKET' | 'LIMIT' | 'STOP_LOSS' | 'TAKE_PROFIT'
export type OrderSide = 'BUY' | 'SELL'
export type OrderStatus = 'OPEN' | 'PARTIALLY_FILLED' | 'FILLED' | 'CANCELLED'

export interface Order {
  id: string
  ticker: string
  playerUuid: string
  type: OrderType
  side: OrderSide
  price: number
  quantity: number
  filled: number
  status: OrderStatus
  createdAt: number
}

export interface PortfolioHolding {
  ticker: string
  shares: number
  avgEntryPrice: number
  currentPrice: number
  value: number
  pnl: number
  pnlPercent: number
}

export interface Portfolio {
  holdings: PortfolioHolding[]
  totalValue: number
  totalCost: number
  totalPnl: number
  totalPnlPercent: number
  balance: number
}

export interface AuthStep1Response {
  step: number
  uuid: string
  totpSetupNeeded: boolean
  totpSecret?: string
  qrCodeUrl?: string
  backupCodes?: string
  requiresTotp: boolean
}

export interface AuthStep2Response {
  success: boolean
  sessionToken: string
  playerName: string
  uuid: string
  expiresAt: number
}

export interface AuthStatusResponse {
  authenticated: boolean
  playerName?: string
  uuid?: string
  balanceRaw?: number
  sessionExpiresAt?: number
  keyExpiresAt?: number
}

export interface OrderResult {
  success: boolean
  error?: string
  order?: Order
  trades?: Trade[]
}

export interface PlaceOrderRequest {
  ticker: string
  side: OrderSide
  type: OrderType
  price?: number
  quantity: number
}

export interface ApiError {
  error: string
}
