import { useEffect, useRef, useState } from 'react'
import { createChart, ColorType, IChartApi } from 'lightweight-charts'
import { fetchCandles } from '../api/market'

interface Props {
  ticker: string
}

interface Candle {
  time: string
  open: number
  high: number
  low: number
  close: number
}

const INTERVALS = ['1h', '15m', '5m', '1m', '1d'] as const
type Interval = (typeof INTERVALS)[number]

export default function Chart({ ticker }: Props) {
  const containerRef = useRef<HTMLDivElement>(null)
  const chartRef = useRef<IChartApi | null>(null)
  const [interval, setInterval] = useState<Interval>('1h')

  useEffect(() => {
    if (!containerRef.current) return

    const chart = createChart(containerRef.current, {
      layout: {
        background: { type: ColorType.Solid, color: '#181825' },
        textColor: '#6b7280',
      },
      grid: {
        vertLines: { color: '#1e1e2e' },
        horzLines: { color: '#1e1e2e' },
      },
      crosshair: {
        mode: 0,
        vertLine: { color: '#6b7280', style: 2 },
        horzLine: { color: '#6b7280', style: 2 },
      },
      rightPriceScale: { borderColor: '#1e1e2e' },
      timeScale: { borderColor: '#1e1e2e', timeVisible: false },
      width: containerRef.current.clientWidth,
      height: containerRef.current.clientHeight,
    })

    const series = chart.addCandlestickSeries({
      upColor: '#a6e3a1',
      downColor: '#f38ba8',
      borderDownColor: '#f38ba8',
      borderUpColor: '#a6e3a1',
      wickDownColor: '#f38ba8',
      wickUpColor: '#a6e3a1',
    } as const)

    chartRef.current = chart

    const load = async () => {
      try {
        const data = await fetchCandles(ticker, interval, 200)
        const candles: Candle[] = (data.candles || []).map((c) => {
          const t = new Date(c.openTime)
          const time = interval === '1d'
            ? `${t.getUTCFullYear()}-${String(t.getUTCMonth() + 1).padStart(2, '0')}-${String(t.getUTCDate()).padStart(2, '0')}`
            : Math.floor(t.getTime() / 1000).toString()
          return { time, open: c.open, high: c.high, low: c.low, close: c.close }
        })
        if (candles.length > 0) series.setData(candles)
      } catch {
        // ignore
      }
    }
    load()

    const handleResize = () => {
      if (containerRef.current) {
        chart.applyOptions({
          width: containerRef.current.clientWidth,
          height: containerRef.current.clientHeight,
        })
      }
    }
    window.addEventListener('resize', handleResize)

    return () => {
      window.removeEventListener('resize', handleResize)
      chart.remove()
    }
  }, [ticker, interval])

  return (
    <div className="flex flex-col h-full">
      <div className="flex gap-1 px-4 py-2 border-b border-white/5">
        {INTERVALS.map((iv) => (
          <button
            key={iv}
            onClick={() => setInterval(iv)}
            className={`px-2.5 py-1 text-xs rounded transition-colors ${
              interval === iv
                ? 'bg-accent-blue/20 text-accent-blue'
                : 'text-gray-500 hover:text-gray-300'
            }`}
          >
            {iv}
          </button>
        ))}
      </div>
      <div ref={containerRef} className="flex-1" />
    </div>
  )
}
