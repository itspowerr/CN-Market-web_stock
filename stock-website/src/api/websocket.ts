import { transformKeys } from './transform'

const WS_URL = import.meta.env.DEV
  ? 'ws://localhost:8081'
  : `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/ws`

type WsMessageHandler = (msg: Record<string, unknown>) => void

export class WsClient {
  private ws: WebSocket | null = null
  private handlers = new Map<string, Set<WsMessageHandler>>()
  private connected = false
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null

  connect() {
    if (this.ws?.readyState === WebSocket.OPEN) return
    this.ws = new WebSocket(WS_URL)

    this.ws.onopen = () => {
      this.connected = true
      this.handlers.get('__open__')?.forEach((h) => h({ type: 'open' }))
    }

    this.ws.onmessage = (event) => {
      try {
        const raw = JSON.parse(event.data)
        const data = transformKeys(raw) as Record<string, unknown>
        const type = data.type as string
        this.handlers.get(type)?.forEach((h) => h(data))
      } catch {
        // ignore parse errors
      }
    }

    this.ws.onclose = () => {
      this.connected = false
      this.handlers.get('__close__')?.forEach((h) => h({ type: 'close' }))
      this.scheduleReconnect()
    }

    this.ws.onerror = () => {
      this.ws?.close()
    }
  }

  private scheduleReconnect() {
    if (this.reconnectTimer) return
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null
      this.connect()
    }, 5000)
  }

  disconnect() {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    this.ws?.close()
    this.ws = null
    this.connected = false
  }

  subscribe(channel: string) {
    this.send({ type: 'subscribe', channel })
  }

  unsubscribe(channel: string) {
    this.send({ type: 'unsubscribe', channel })
  }

  on(type: string, handler: WsMessageHandler) {
    if (!this.handlers.has(type)) this.handlers.set(type, new Set())
    this.handlers.get(type)!.add(handler)
    return () => this.handlers.get(type)?.delete(handler)
  }

  onOpen(handler: () => void) {
    return this.on('__open__', () => handler())
  }

  onClose(handler: () => void) {
    return this.on('__close__', () => handler())
  }

  isConnected() {
    return this.connected
  }

  private send(data: unknown) {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(data))
    }
  }
}

export const wsClient = new WsClient()
