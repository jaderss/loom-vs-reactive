import { Bar, BarChart, CartesianGrid, Cell, ErrorBar, LabelList, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { SERIES, SERIES_INFO, type Metric, type Series, type SeriesPoint, type Summary } from './data'
import { formatNumber } from './format'
import { useTheme } from './theme'

type TooltipEntry = { dataKey?: string | number; value?: number | string; color?: string; payload?: Record<string, unknown> }

function ChartTooltip({ active, payload, label, unit, title }: {
  active?: boolean
  payload?: TooltipEntry[]
  label?: string | number
  unit: string
  title: (label: string | number | undefined) => string
}) {
  if (!active || !payload?.length) return null
  return (
    <div className="tooltip">
      <div className="tooltip-title">{title(label)}</div>
      {payload.map((entry) => (
        <div className="tooltip-row" key={String(entry.payload?.app ?? entry.dataKey)}>
          <span className="line-key" style={{ background: entry.color }} />
          <strong>{formatNumber(Number(entry.value))} {unit}</strong>
          <span className="secondary">{String(entry.payload?.app ?? entry.dataKey)}</span>
          {typeof entry.payload?.range === 'string' && <span className="secondary">{entry.payload.range}</span>}
        </div>
      ))}
    </div>
  )
}

const axisTick = (fill: string) => ({ fill, fontSize: 12 })

export function SummaryBars({ summaries, title, unit, metric }: {
  summaries: Summary[]
  title: string
  unit: string
  metric: Metric
}) {
  const theme = useTheme()
  const spread = summaries.some((s) => s.rounds > 1)
  const data = summaries.map((s) => {
    const { median, min, max } = s.stats[metric]
    return {
      app: s.app,
      value: median,
      max,
      spread: [median - min, max - median],
      range: s.rounds > 1 ? `${formatNumber(min)} to ${formatNumber(max)} over ${s.rounds} rounds` : undefined,
    }
  })
  return (
    <figure className="chart">
      <figcaption>{title} <span className="unit">({unit})</span></figcaption>
      <ResponsiveContainer width="100%" height={16 + data.length * 36}>
        <BarChart data={data} layout="vertical" margin={{ top: 4, right: 72, bottom: 4, left: 0 }} barCategoryGap={8}>
          <XAxis type="number" hide domain={[0, 'dataMax']} />
          <YAxis type="category" dataKey="app" width={112} tickLine={false} axisLine={{ stroke: theme.axis }} tick={axisTick(theme.muted)} />
          <Tooltip cursor={false} content={<ChartTooltip unit={unit} title={() => title} />} />
          <Bar dataKey="value" maxBarSize={24} radius={[0, 4, 4, 0]} isAnimationActive={false} stroke={theme.surface} strokeWidth={2}>
            {data.map((entry) => <Cell key={entry.app} fill={theme.series[entry.app]} />)}
            {spread && <ErrorBar dataKey="spread" direction="x" width={6} stroke={theme.muted} strokeWidth={1} />}
            <LabelList
              dataKey="value"
              content={({ x, y, width, height, index }) => {
                const entry = data[Number(index)]
                const end = Number(x) + Number(width) * (entry.value > 0 ? entry.max / entry.value : 1)
                return (
                  <text x={end + 6} y={Number(y) + Number(height) / 2} dominantBaseline="central" className="bar-label">
                    {formatNumber(entry.value)}
                  </text>
                )
              }}
            />
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </figure>
  )
}

function logTicks(data: SeriesPoint[], series: Series[]) {
  const values = data.flatMap((point) => series.map((s) => point[s])).filter((v): v is number => v !== undefined && v > 0)
  if (values.length === 0) return undefined
  const min = Math.min(...values)
  const max = Math.max(...values)
  if (max / min < 10) return undefined
  const multiples = max / min > 200 ? [1] : [1, 2, 5]
  const steps = Array.from({ length: 12 }, (_, i) => multiples.map((m) => m * 10 ** (i - 2))).flat()
  const low = steps.findLast((t) => t <= min) ?? steps[0]
  const high = steps.find((t) => t >= max) ?? steps[steps.length - 1]
  return steps.filter((t) => t >= low && t <= high)
}

export function SeriesLines({ data, series, title, unit, x, log = false }: {
  data: SeriesPoint[]
  series: Series[]
  title: string
  unit: string
  x: 'seconds' | 'rate'
  log?: boolean
}) {
  const theme = useTheme()
  const ticks = log ? logTicks(data, series) : undefined
  const markers = x === 'rate'
  return (
    <figure className="chart">
      <figcaption>{title} <span className="unit">({unit}{ticks ? ', log scale' : ''})</span></figcaption>
      <ResponsiveContainer width="100%" height={220}>
        <LineChart data={data} margin={{ top: 8, right: 16, bottom: 0, left: 0 }}>
          <CartesianGrid vertical={false} stroke={theme.grid} />
          {x === 'rate'
            ? <XAxis dataKey="x" type="category" tickLine={false} axisLine={{ stroke: theme.axis }} tick={axisTick(theme.muted)} tickFormatter={(v) => formatNumber(Number(v))} />
            : <XAxis dataKey="x" type="number" domain={['dataMin', 'dataMax']} tickLine={false} axisLine={{ stroke: theme.axis }} tick={axisTick(theme.muted)} unit="s" />}
          <YAxis
            width={52}
            tickLine={false}
            axisLine={false}
            tick={axisTick(theme.muted)}
            tickFormatter={(v) => formatNumber(Number(v))}
            {...(ticks ? { scale: 'log' as const, domain: [ticks[0], ticks[ticks.length - 1]], ticks, allowDataOverflow: true } : {})}
          />
          <Tooltip
            cursor={{ stroke: theme.axis }}
            content={<ChartTooltip unit={unit} title={(t) => (x === 'rate' ? `${formatNumber(Number(t))} req/s sent` : `${t} s`)} />}
          />
          {SERIES.filter((s) => series.includes(s)).map((s) => (
            <Line
              key={s}
              dataKey={s}
              name={s}
              stroke={theme.series[s]}
              strokeWidth={2}
              dot={markers ? { r: 4, fill: theme.series[s], stroke: theme.surface, strokeWidth: 2 } : false}
              activeDot={{ r: 5, stroke: theme.surface, strokeWidth: 2 }}
              isAnimationActive={false}
              connectNulls
            />
          ))}
        </LineChart>
      </ResponsiveContainer>
    </figure>
  )
}

export function Legend({ series }: { series: Series[] }) {
  const theme = useTheme()
  return (
    <ul className="legend" aria-label="What each color is">
      {series.map((s) => (
        <li key={s}>
          <span className="legend-name">
            <span className="line-key" style={{ background: theme.series[s] }} />
            <strong>{s}</strong>
            <span>{SERIES_INFO[s].name}</span>
          </span>
          <span className="legend-detail">{SERIES_INFO[s].detail}</span>
        </li>
      ))}
    </ul>
  )
}
