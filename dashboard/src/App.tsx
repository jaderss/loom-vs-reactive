import { useEffect, useState } from 'react'
import { Legend, SeriesLines, SummaryBars } from './charts'
import { byRate, loadRuns, summarize, timeSeries, type Metric, type Run, type Scenario, type Series, type Summary } from './data'
import { formatNumber, formatRunId } from './format'

type Kind = 'closed' | 'open' | 'jdk'

const closedLoad = (s: Summary) =>
  `${formatNumber(s.stats.concurrency.median)} virtual users, closed model, ${s.stats.cpus.median} CPUs and a pool of ${s.stats.pool_size.median} per app.`

const TABS: { id: Scenario; title: string; kind: Kind; about: string; load: (s: Summary) => string }[] = [
  {
    id: 'read',
    title: 'Read',
    kind: 'closed',
    about: 'GET /accounts/{id}: one query by primary key, no downstream call.',
    load: closedLoad,
  },
  {
    id: 'statement',
    title: 'Statement',
    kind: 'closed',
    about: 'GET /accounts/{id}/statement: account, last transactions and a rates call to the mock, in parallel. The mock adds latency and jitter.',
    load: closedLoad,
  },
  {
    id: 'slow-db',
    title: 'Slow database',
    kind: 'closed',
    about: 'The statement endpoint with Toxiproxy adding latency to every Postgres reply.',
    load: closedLoad,
  },
  {
    id: 'overload',
    title: 'Overload',
    kind: 'open',
    about: 'The statement endpoint under an open model. The arrival rate steps from below capacity to above it, and latency counts from the time each request was due.',
    load: (s) => `Fixed arrival rate per step, ${s.stats.cpus.median} CPUs and a pool of ${s.stats.pool_size.median} per app.`,
  },
  {
    id: 'legacy-lock',
    title: 'Legacy lock',
    kind: 'jdk',
    about: 'mvc on virtual threads with a striped synchronized block around the rate call. On Java 21 a blocked virtual thread pins its carrier, on Java 25 it does not (JEP 491).',
    load: closedLoad,
  },
]

const COLUMNS: { label: string; metric: Metric }[] = [
  { label: 'Throughput /s', metric: 'throughput_ops_s' },
  { label: 'p50 ms', metric: 'latency_p50_ms' },
  { label: 'p95 ms', metric: 'latency_p95_ms' },
  { label: 'p99 ms', metric: 'latency_p99_ms' },
  { label: 'p99.9 ms', metric: 'latency_p999_ms' },
  { label: 'Max ms', metric: 'latency_max_ms' },
  { label: 'Peak RSS MB', metric: 'rss_mb' },
  { label: 'CPU %', metric: 'cpu_pct' },
  { label: 'Completed', metric: 'completed' },
  { label: 'Errors', metric: 'errors' },
  { label: 'Timeouts', metric: 'timeouts' },
  { label: 'Bad bodies', metric: 'bad_bodies' },
]

function ResultsTable({ summaries, open }: { summaries: Summary[]; open: boolean }) {
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>App</th>
            {open && <th className="num">Sent /s</th>}
            <th>Mode</th>
            <th>Version</th>
            {COLUMNS.map((c) => <th key={c.label} className="num">{c.label}</th>)}
            <th className="num">Rounds</th>
          </tr>
        </thead>
        <tbody>
          {summaries.map((s) => (
            <tr key={`${s.app}-${s.arrivalRate}`}>
              <td>{s.app}</td>
              {open && <td className="num">{formatNumber(s.arrivalRate)}</td>}
              <td>{s.mode}</td>
              <td>{s.version}</td>
              {COLUMNS.map((c) => <td key={c.label} className="num">{formatNumber(s.stats[c.metric].median)}</td>)}
              <td className="num">{s.rounds}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function ClosedCharts({ run, scenario, summaries, series }: { run: Run; scenario: Scenario; summaries: Summary[]; series: Series[] }) {
  const lines = (metric: 'throughput_ops_s' | 'latency_p99_ms' | 'rss_mb') => timeSeries(run.results, scenario, metric)
  return (
    <>
      <div className="grid">
        <SummaryBars summaries={summaries} title="Throughput" unit="req/s" metric="throughput_ops_s" />
        <SummaryBars summaries={summaries} title="p99 latency" unit="ms" metric="latency_p99_ms" />
        <SummaryBars summaries={summaries} title="p99.9 latency" unit="ms" metric="latency_p999_ms" />
        <SummaryBars summaries={summaries} title="Peak memory" unit="MB RSS" metric="rss_mb" />
      </div>
      <div className="grid">
        <SeriesLines data={lines('throughput_ops_s')} series={series} title="Throughput per second" unit="req/s" x="seconds" />
        <SeriesLines data={lines('latency_p99_ms')} series={series} title="p99 latency per second" unit="ms" x="seconds" />
        <SeriesLines data={lines('rss_mb')} series={series} title="Memory" unit="MB RSS" x="seconds" />
      </div>
    </>
  )
}

function OpenCharts({ summaries, series }: { summaries: Summary[]; series: Series[] }) {
  return (
    <div className="grid">
      <SeriesLines data={byRate(summaries, 'latency_p99_ms', true)} series={series} title="p99 latency by arrival rate" unit="ms" x="rate" log />
      <SeriesLines data={byRate(summaries, 'latency_p999_ms', true)} series={series} title="p99.9 latency by arrival rate" unit="ms" x="rate" log />
      <SeriesLines data={byRate(summaries, 'throughput_ops_s')} series={series} title="Completed by arrival rate" unit="req/s" x="rate" />
      <SeriesLines data={byRate(summaries, 'error_pct')} series={series} title="Errors by arrival rate" unit="% of sent" x="rate" />
      <SeriesLines data={byRate(summaries, 'timeout_pct')} series={series} title="Timeouts by arrival rate" unit="% of sent" x="rate" />
    </div>
  )
}

function ScenarioSection({ run, tab }: { run: Run; tab: (typeof TABS)[number] }) {
  const summaries = summarize(run.results, tab.id)
  if (summaries.length === 0) {
    return <p className="notice">No {tab.title.toLowerCase()} results in this run. Run <code>make bench SCENARIOS={tab.id}</code> and reload.</p>
  }
  const series = [...new Set(summaries.map((s) => s.app))]
  const rounds = Math.max(...summaries.map((s) => s.rounds))
  const failed = summaries.filter((s) => s.stats.bad_bodies.max > 0)
  return (
    <section>
      <header className="section-header">
        <div>
          <p>{tab.about}</p>
          <p className="secondary">
            {tab.load(summaries[0])}{' '}
            {rounds === 1 ? 'One round.' : `Median of ${rounds} rounds${tab.kind === 'open' ? '.' : '; whiskers on the bars show the lowest and highest round.'}`}
          </p>
        </div>
        <Legend series={series} />
      </header>
      {failed.length > 0 && (
        <p className="notice failed" role="alert">
          <span aria-hidden="true">✕</span> Failed run: {failed.map((s) => s.app).join(', ')} answered with wrong bodies. These numbers do not count.
        </p>
      )}
      {tab.kind === 'open'
        ? <OpenCharts summaries={summaries} series={series} />
        : <ClosedCharts run={run} scenario={tab.id} summaries={summaries} series={series} />}
      <ResultsTable summaries={summaries} open={tab.kind === 'open'} />
    </section>
  )
}

const tabFromHash = () => TABS.find((t) => `#${t.id}` === window.location.hash) ?? TABS[0]

export default function App() {
  const [runs, setRuns] = useState<Run[]>()
  const [tab, setTab] = useState(tabFromHash)
  const [selectedRunId, setSelectedRunId] = useState<string>()
  const [error, setError] = useState<string>()

  useEffect(() => {
    loadRuns()
      .then(setRuns)
      .catch((e: unknown) => setError(String(e)))
    const onHashChange = () => setTab(tabFromHash())
    window.addEventListener('hashchange', onHashChange)
    return () => window.removeEventListener('hashchange', onHashChange)
  }, [])

  const run = runs?.find((r) => r.id === selectedRunId) ?? runs?.[0]

  return (
    <main>
      <header className="page-header">
        <div>
          <h1>loom-vs-reactive</h1>
          <p className="secondary">Spring MVC on virtual and platform threads, WebFlux and Kotlin coroutines, with the same HTTP contract against the same Postgres.</p>
        </div>
        {runs && runs.length > 0 && (
          <label className="run-picker">
            <span className="secondary">Run</span>
            <select value={run?.id} onChange={(e) => setSelectedRunId(e.target.value)}>
              {runs.map((r) => <option key={r.id} value={r.id}>{formatRunId(r.id)}</option>)}
            </select>
          </label>
        )}
      </header>

      <nav className="tabs" role="tablist" aria-label="Scenario">
        {TABS.map((t) => (
          <button key={t.id} role="tab" aria-selected={t.id === tab.id} className="tab" onClick={() => { window.location.hash = t.id }}>
            {t.title}
          </button>
        ))}
      </nav>

      {error && <p className="notice">Could not load results: {error}</p>}
      {runs && runs.length === 0 && <p className="notice">No results yet. Run <code>make bench</code> and reload.</p>}
      {run && <ScenarioSection key={`${run.id}-${tab.id}`} run={run} tab={tab} />}
    </main>
  )
}
