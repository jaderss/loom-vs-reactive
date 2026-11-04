package main

import (
	"encoding/csv"
	"io"
	"math"
	"slices"
	"strconv"
	"sync"
	"time"
)

var header = []string{
	"kind", "lang", "lang_version", "mode", "run_id", "elapsed_s", "cpus", "concurrency",
	"completed", "errors", "throughput_ops_s", "latency_p50_ms", "latency_p95_ms", "latency_p99_ms",
	"latency_max_ms", "rss_mb", "cpu_pct",
	"scenario", "app", "load_model", "arrival_rate", "pool_size", "latency_p999_ms", "timeouts", "bad_bodies",
}

type outcome int

const (
	ok outcome = iota
	failed
	timedOut
)

type tally struct {
	latencies []float64
	errors    int
	timeouts  int
	badBodies int
}

type recorder struct {
	mu sync.Mutex
	tally
}

func (r *recorder) add(result outcome, latency time.Duration, badBody bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	switch result {
	case ok:
		r.latencies = append(r.latencies, float64(latency.Nanoseconds())/1e6)
	case failed:
		r.errors++
	case timedOut:
		r.timeouts++
	}
	if badBody {
		r.badBodies++
	}
}

func (r *recorder) drain() tally {
	r.mu.Lock()
	defer r.mu.Unlock()
	drained := r.tally
	r.tally = tally{}
	return drained
}

func (r *recorder) snapshot() tally {
	r.mu.Lock()
	defer r.mu.Unlock()
	copied := r.tally
	copied.latencies = slices.Clone(r.latencies)
	return copied
}

type runInfo struct {
	target      targetStats
	app         string
	runID       string
	scenario    string
	loadModel   string
	concurrency int
}

type row struct {
	kind        string
	elapsed     float64
	wall        float64
	arrivalRate int
	rssMB       float64
	cpuPercent  float64
	tally
}

type csvWriter struct {
	mu     sync.Mutex
	info   runInfo
	writer *csv.Writer
}

func newCSVWriter(out io.Writer, info runInfo) *csvWriter {
	w := &csvWriter{info: info, writer: csv.NewWriter(out)}
	w.writer.Write(header)
	return w
}

func (w *csvWriter) write(r row) error {
	sorted := slices.Sorted(slices.Values(r.latencies))
	info := w.info
	w.mu.Lock()
	defer w.mu.Unlock()
	w.writer.Write([]string{
		r.kind, info.target.Lang, info.target.Version, info.target.Mode, info.runID, format(r.elapsed),
		strconv.Itoa(info.target.CPUs), strconv.Itoa(info.concurrency),
		strconv.Itoa(len(sorted)), strconv.Itoa(r.errors), format(float64(len(sorted)) / r.wall),
		format(percentile(sorted, 50)), format(percentile(sorted, 95)), format(percentile(sorted, 99)),
		format(percentile(sorted, 100)), format(r.rssMB), format(r.cpuPercent),
		info.scenario, info.app, info.loadModel, strconv.Itoa(r.arrivalRate), strconv.Itoa(info.target.PoolSize),
		format(percentile(sorted, 99.9)), strconv.Itoa(r.timeouts), strconv.Itoa(r.badBodies),
	})
	w.writer.Flush()
	return w.writer.Error()
}

func percentile(sorted []float64, p float64) float64 {
	if len(sorted) == 0 {
		return 0
	}
	rank := int(math.Ceil(p / 100 * float64(len(sorted))))
	return sorted[max(rank, 1)-1]
}

func format(v float64) string {
	return strconv.FormatFloat(v, 'f', 3, 64)
}
