export const APPS = ['mvc-vt', 'mvc-platform', 'webflux', 'coroutines'] as const
export const JDKS = ['mvc-vt-java21', 'mvc-vt-java25'] as const
export const SERIES = [...APPS, ...JDKS] as const
export type Series = (typeof SERIES)[number]

export const SCENARIOS = ['read', 'statement', 'slow-db', 'overload', 'legacy-lock'] as const
export type Scenario = (typeof SCENARIOS)[number]

const NUMERIC = [
  'elapsed_s', 'cpus', 'concurrency', 'completed', 'errors', 'throughput_ops_s',
  'latency_p50_ms', 'latency_p95_ms', 'latency_p99_ms', 'latency_max_ms', 'rss_mb', 'cpu_pct',
  'arrival_rate', 'pool_size', 'latency_p999_ms', 'timeouts', 'bad_bodies',
] as const

type Numeric = (typeof NUMERIC)[number]

export type Row = Record<Numeric, number> & {
  kind: 'interval' | 'summary'
  lang: string
  lang_version: string
  mode: string
  run_id: string
  scenario: Scenario
  app: Series
  load_model: 'closed' | 'open'
}

export type Metric = Numeric | 'error_pct' | 'timeout_pct'

type RoundResult = {
  file: string
  runId: string
  round: number
  modified: number
  scenario: Scenario
  app: Series
  intervals: Row[]
  summaries: Row[]
}

export type Stat = { median: number; min: number; max: number }

export type Summary = {
  app: Series
  mode: string
  version: string
  rounds: number
  arrivalRate: number
  stats: Record<Metric, Stat>
}

export type Run = {
  id: string
  modified: number
  results: RoundResult[]
}

export function parseCsv(text: string): Row[] {
  const [header, ...lines] = text.trim().split(/\r?\n/)
  const columns = header.split(',')
  return lines.map((line) => {
    const cells = line.split(',')
    const row: Record<string, string | number> = {}
    columns.forEach((column, i) => {
      row[column] = (NUMERIC as readonly string[]).includes(column) ? Number(cells[i]) : cells[i]
    })
    return row as Row
  })
}

const isSeries = (app: string): app is Series => (SERIES as readonly string[]).includes(app)

function toResult(file: string, modified: number, rows: Row[]): RoundResult | undefined {
  const summaries = rows.filter((row) => row.kind === 'summary')
  const first = summaries[0]
  if (!first || !isSeries(first.app) || !(SCENARIOS as readonly string[]).includes(first.scenario)) return undefined
  const match = /^(.*)-r(\d+)$/.exec(first.run_id)
  return {
    file,
    runId: match ? match[1] : first.run_id,
    round: match ? Number(match[2]) : 1,
    modified,
    scenario: first.scenario,
    app: first.app,
    intervals: rows.filter((row) => row.kind === 'interval'),
    summaries,
  }
}

type Listing = { name: string; type: string; mtime?: string }

export async function loadRuns(): Promise<Run[]> {
  const listing: Listing[] = await fetch('results/').then((r) => r.json())
  const files = listing.filter((entry) => entry.type === 'file' && entry.name.endsWith('.csv'))
  const results = await Promise.all(
    files.map(async ({ name, mtime }) => {
      const text = await fetch(`results/${encodeURIComponent(name)}`).then((r) => r.text())
      return toResult(name, mtime ? Date.parse(mtime) : 0, parseCsv(text))
    }),
  )
  const byRun = Map.groupBy(results.filter((r): r is RoundResult => r !== undefined), (r) => r.runId)
  return [...byRun]
    .map(([id, runResults]) => ({ id, modified: Math.max(...runResults.map((r) => r.modified)), results: runResults }))
    .sort((a, b) => b.modified - a.modified || b.id.localeCompare(a.id))
}

export function median(values: number[]): number {
  const sorted = values.toSorted((a, b) => a - b)
  const mid = Math.floor(sorted.length / 2)
  return sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2
}

const stat = (values: number[]): Stat => ({ median: median(values), min: Math.min(...values), max: Math.max(...values) })

const sentPct = (row: Row, count: number) => {
  const sent = row.completed + row.errors + row.timeouts
  return sent === 0 ? 0 : (100 * count) / sent
}

const metricOf = (row: Row, metric: Metric) => {
  if (metric === 'error_pct') return sentPct(row, row.errors)
  if (metric === 'timeout_pct') return sentPct(row, row.timeouts)
  return row[metric]
}

const METRICS: Metric[] = [...NUMERIC, 'error_pct', 'timeout_pct']

export function summarize(results: RoundResult[], scenario: Scenario): Summary[] {
  const rows = results.filter((r) => r.scenario === scenario).flatMap((r) => r.summaries)
  const groups = Map.groupBy(rows, (row) => `${row.app}|${row.arrival_rate}`)
  return [...groups.values()]
    .map((group) => ({
      app: group[0].app,
      mode: group[0].mode,
      version: group[0].lang_version,
      rounds: group.length,
      arrivalRate: group[0].arrival_rate,
      stats: Object.fromEntries(METRICS.map((m) => [m, stat(group.map((row) => metricOf(row, m)))])) as Record<Metric, Stat>,
    }))
    .sort((a, b) => SERIES.indexOf(a.app) - SERIES.indexOf(b.app) || a.arrivalRate - b.arrivalRate)
}

export type SeriesPoint = { x: number } & Partial<Record<Series, number>>

export function timeSeries(results: RoundResult[], scenario: Scenario, metric: Numeric): SeriesPoint[] {
  const points = new Map<number, Map<Series, number[]>>()
  for (const result of results.filter((r) => r.scenario === scenario)) {
    for (const row of result.intervals) {
      const t = Math.round(row.elapsed_s)
      const bySeries = points.get(t) ?? new Map<Series, number[]>()
      bySeries.set(result.app, [...(bySeries.get(result.app) ?? []), row[metric]])
      points.set(t, bySeries)
    }
  }
  return [...points]
    .map(([x, bySeries]) => ({ x, ...Object.fromEntries([...bySeries].map(([app, values]) => [app, median(values)])) }))
    .sort((a, b) => a.x - b.x)
}

export function byRate(summaries: Summary[], metric: Metric, positiveOnly = false): SeriesPoint[] {
  const groups = Map.groupBy(summaries, (s) => s.arrivalRate)
  return [...groups]
    .map(([x, group]) => {
      const point: SeriesPoint = { x }
      for (const s of group) {
        const value = s.stats[metric].median
        if (!positiveOnly || value > 0) point[s.app] = value
      }
      return point
    })
    .sort((a, b) => a.x - b.x)
}
