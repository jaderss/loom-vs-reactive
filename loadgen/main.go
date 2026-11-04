package main

import (
	"bytes"
	"cmp"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"math/big"
	"math/rand/v2"
	"net/http"
	"os"
	"path/filepath"
	"reflect"
	"slices"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/jaderss/loom-vs-reactive/loadgen/seed"
)

type config struct {
	target       string
	scenario     string
	appLabel     string
	users        int
	thinkTime    time.Duration
	duration     time.Duration
	rampUp       time.Duration
	arrivalRates []int
	timeout      time.Duration
	sampleEvery  int
	limit        int
	accounts     int64
	perAccount   int
	rate         *big.Rat
	runID        string
	resultsDir   string
}

type targetStats struct {
	App        string  `json:"app"`
	Lang       string  `json:"lang"`
	Version    string  `json:"version"`
	Mode       string  `json:"mode"`
	CPUs       int     `json:"cpus"`
	PoolSize   int     `json:"pool_size"`
	RssMB      float64 `json:"rss_mb"`
	CPUSeconds float64 `json:"cpu_seconds"`
}

type phase struct {
	rate     int
	rec      recorder
	start    time.Time
	end      time.Time
	cpuStart float64
	cpuEnd   float64
	peakRSS  float64
}

type runner struct {
	cfg      config
	client   *http.Client
	stats    func() targetStats
	interval recorder
	current  atomic.Pointer[phase]
	sent     atomic.Int64
}

func loadConfig() (config, error) {
	rate, ok := new(big.Rat).SetString(cmp.Or(os.Getenv("MOCK_RATE"), "1.0900"))
	if !ok {
		return config{}, fmt.Errorf("MOCK_RATE %q is not a decimal", os.Getenv("MOCK_RATE"))
	}
	var rates []int
	for _, field := range strings.Fields(cmp.Or(os.Getenv("ARRIVAL_RATES"), "1000 2000 4000 8000")) {
		value, err := strconv.Atoi(field)
		if err != nil || value <= 0 {
			return config{}, fmt.Errorf("ARRIVAL_RATES has %q, want positive integers", field)
		}
		rates = append(rates, value)
	}
	return config{
		target:       cmp.Or(os.Getenv("TARGET"), "http://localhost:8080"),
		scenario:     cmp.Or(os.Getenv("SCENARIO"), "statement"),
		appLabel:     os.Getenv("APP_LABEL"),
		users:        envInt("USERS", 2000),
		thinkTime:    envDuration("THINK_TIME", time.Second),
		duration:     envDuration("DURATION", 60*time.Second),
		rampUp:       envDuration("RAMP_UP", 15*time.Second),
		arrivalRates: rates,
		timeout:      envDuration("REQUEST_TIMEOUT", 10*time.Second),
		sampleEvery:  envInt("SAMPLE_EVERY", 100),
		limit:        envInt("STATEMENT_LIMIT", 20),
		accounts:     int64(envInt("SEED_ACCOUNTS", 100000)),
		perAccount:   envInt("SEED_TXN_PER_ACCOUNT", 50),
		rate:         rate,
		runID:        cmp.Or(os.Getenv("RUN_ID"), "local"),
		resultsDir:   cmp.Or(os.Getenv("RESULTS_DIR"), "results"),
	}, nil
}

func envInt(key string, fallback int) int {
	if value, err := strconv.Atoi(os.Getenv(key)); err == nil && value > 0 {
		return value
	}
	return fallback
}

func envDuration(key string, fallback time.Duration) time.Duration {
	if value, err := time.ParseDuration(os.Getenv(key)); err == nil {
		return value
	}
	return fallback
}

func main() {
	cfg, err := loadConfig()
	if err == nil {
		err = run(cfg)
	}
	if err != nil {
		log.Fatal(err)
	}
}

func run(cfg config) error {
	loadModel, err := loadModelFor(cfg.scenario)
	if err != nil {
		return err
	}
	client := &http.Client{Transport: &http.Transport{
		MaxIdleConns:        0,
		MaxIdleConnsPerHost: max(cfg.users, 10000),
		IdleConnTimeout:     90 * time.Second,
	}}
	target, err := waitForTarget(client, cfg.target)
	if err != nil {
		return err
	}
	app := cmp.Or(cfg.appLabel, target.App)
	if err := os.MkdirAll(cfg.resultsDir, 0o755); err != nil {
		return err
	}
	file, err := os.Create(filepath.Join(cfg.resultsDir, fmt.Sprintf("%s_%s_%s.csv", cfg.runID, cfg.scenario, app)))
	if err != nil {
		return err
	}
	defer file.Close()
	concurrency := cfg.users
	if loadModel == "open" {
		concurrency = 0
	}
	out := newCSVWriter(file, runInfo{target: target, app: app, runID: cfg.runID, scenario: cfg.scenario, loadModel: loadModel, concurrency: concurrency})
	r := &runner{cfg: cfg, client: client, stats: remoteStats(client, cfg.target)}
	log.Printf("%s %s against %s (%s %s %s) at %s", cfg.scenario, loadModel, app, target.Lang, target.Version, target.Mode, cfg.target)
	var phases []*phase
	if loadModel == "open" {
		phases = r.openModel(out)
	} else {
		phases = r.closedModel(out)
	}
	for _, p := range phases {
		summary := r.summary(p)
		if err := out.write(summary); err != nil {
			return err
		}
		sorted := slices.Sorted(slices.Values(summary.latencies))
		log.Printf("%s rate=%d completed=%d errors=%d timeouts=%d bad_bodies=%d p50=%.1fms p99=%.1fms p99.9=%.1fms cpu=%.0f%% rss=%.0fMB",
			app, p.rate, len(sorted), summary.errors, summary.timeouts, summary.badBodies,
			percentile(sorted, 50), percentile(sorted, 99), percentile(sorted, 99.9), summary.cpuPercent, summary.rssMB)
	}
	return nil
}

func loadModelFor(scenario string) (string, error) {
	switch scenario {
	case "read", "statement", "slow-db", "legacy-lock":
		return "closed", nil
	case "overload":
		return "open", nil
	default:
		return "", fmt.Errorf("unknown SCENARIO %q, expected read, statement, slow-db, overload or legacy-lock", scenario)
	}
}

func (r *runner) closedModel(out *csvWriter) []*phase {
	ctx, cancel := context.WithTimeout(context.Background(), r.cfg.rampUp+r.cfg.duration)
	defer cancel()
	var users sync.WaitGroup
	for i := range r.cfg.users {
		delay := r.cfg.rampUp * time.Duration(i) / time.Duration(r.cfg.users)
		users.Go(func() { r.user(ctx, delay) })
	}
	sleep(ctx, r.cfg.rampUp)
	p := &phase{}
	stop := r.measure(p, out)
	users.Wait()
	stop()
	return []*phase{p}
}

func (r *runner) user(ctx context.Context, delay time.Duration) {
	if !sleep(ctx, delay) {
		return
	}
	for ctx.Err() == nil {
		start := time.Now()
		p := r.current.Load()
		result, badBody := r.call(ctx, rand.N(r.cfg.accounts)+1)
		if ctx.Err() != nil {
			return
		}
		r.record(p, result, time.Since(start), badBody)
		if !sleep(ctx, jitter(r.cfg.thinkTime)) {
			return
		}
	}
}

func (r *runner) openModel(out *csvWriter) []*phase {
	var inflight sync.WaitGroup
	r.fire(r.cfg.arrivalRates[0], r.cfg.rampUp, &inflight)
	phases := make([]*phase, 0, len(r.cfg.arrivalRates))
	for _, rate := range r.cfg.arrivalRates {
		p := &phase{rate: rate}
		stop := r.measure(p, out)
		r.fire(rate, r.cfg.duration, &inflight)
		stop()
		phases = append(phases, p)
	}
	r.current.Store(nil)
	inflight.Wait()
	return phases
}

func (r *runner) fire(rate int, d time.Duration, inflight *sync.WaitGroup) {
	start := time.Now()
	total := int(int64(rate) * int64(d) / int64(time.Second))
	for i := range total {
		intended := start.Add(time.Duration(int64(i) * int64(time.Second) / int64(rate)))
		if wait := time.Until(intended); wait > 0 {
			time.Sleep(wait)
		}
		p := r.current.Load()
		inflight.Go(func() {
			result, badBody := r.call(context.Background(), rand.N(r.cfg.accounts)+1)
			r.record(p, result, time.Since(intended), badBody)
		})
	}
	if elapsed := time.Since(start); total > 0 && elapsed > d+d/100 {
		log.Printf("open model fell behind: %d requests at %d/s took %s instead of %s", total, rate, elapsed, d)
	}
}

func (r *runner) measure(p *phase, out *csvWriter) func() {
	p.start, p.cpuStart = time.Now(), r.stats().CPUSeconds
	r.interval.drain()
	r.current.Store(p)
	done := make(chan struct{})
	var sampling sync.WaitGroup
	sampling.Go(func() {
		ticker := time.NewTicker(time.Second)
		defer ticker.Stop()
		last := p.start
		lastCPU := p.cpuStart
		for {
			select {
			case <-done:
				return
			case now := <-ticker.C:
				stats := r.stats()
				wall := now.Sub(last).Seconds()
				p.peakRSS = max(p.peakRSS, stats.RssMB)
				out.write(row{kind: "interval", elapsed: now.Sub(p.start).Seconds(), wall: wall, arrivalRate: p.rate,
					rssMB: stats.RssMB, cpuPercent: (stats.CPUSeconds - lastCPU) / wall * 100, tally: r.interval.drain()})
				last, lastCPU = now, stats.CPUSeconds
			}
		}
	})
	return func() {
		close(done)
		sampling.Wait()
		stats := r.stats()
		p.end, p.cpuEnd = time.Now(), stats.CPUSeconds
		p.peakRSS = max(p.peakRSS, stats.RssMB)
	}
}

func (r *runner) record(p *phase, result outcome, latency time.Duration, badBody bool) {
	if p == nil {
		return
	}
	r.interval.add(result, latency, badBody)
	p.rec.add(result, latency, badBody)
}

func (r *runner) summary(p *phase) row {
	wall := p.end.Sub(p.start).Seconds()
	return row{kind: "summary", elapsed: wall, wall: wall, arrivalRate: p.rate, rssMB: p.peakRSS,
		cpuPercent: (p.cpuEnd - p.cpuStart) / wall * 100, tally: p.rec.snapshot()}
}

func (r *runner) call(ctx context.Context, id int64) (outcome, bool) {
	ctx, cancel := context.WithTimeout(ctx, r.cfg.timeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, r.url(id), nil)
	if err != nil {
		return failed, false
	}
	resp, err := r.client.Do(req)
	if err != nil {
		return classify(err), false
	}
	defer resp.Body.Close()
	sampled := r.cfg.sampleEvery > 0 && r.sent.Add(1)%int64(r.cfg.sampleEvery) == 0
	if !sampled {
		if _, err := io.Copy(io.Discard, resp.Body); err != nil {
			return classify(err), false
		}
		return statusOutcome(resp.StatusCode), false
	}
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return classify(err), false
	}
	result := statusOutcome(resp.StatusCode)
	return result, result == ok && !r.bodyMatches(id, body)
}

func (r *runner) url(id int64) string {
	if r.cfg.scenario == "read" {
		return fmt.Sprintf("%s/accounts/%d", r.cfg.target, id)
	}
	return fmt.Sprintf("%s/accounts/%d/statement?limit=%d", r.cfg.target, id, r.cfg.limit)
}

func (r *runner) bodyMatches(id int64, body []byte) bool {
	if r.cfg.scenario == "read" {
		return matches(body, seed.AccountFor(id))
	}
	return matches(body, seed.StatementFor(id, r.cfg.perAccount, r.cfg.limit, r.cfg.rate))
}

func matches[T any](body []byte, want T) bool {
	decoder := json.NewDecoder(bytes.NewReader(body))
	decoder.DisallowUnknownFields()
	var got T
	return decoder.Decode(&got) == nil && reflect.DeepEqual(got, want)
}

func statusOutcome(status int) outcome {
	if status == http.StatusOK {
		return ok
	}
	return failed
}

func classify(err error) outcome {
	if errors.Is(err, context.DeadlineExceeded) {
		return timedOut
	}
	return failed
}

func jitter(d time.Duration) time.Duration {
	return d/2 + rand.N(d+1)
}

func sleep(ctx context.Context, d time.Duration) bool {
	timer := time.NewTimer(d)
	defer timer.Stop()
	select {
	case <-timer.C:
		return true
	case <-ctx.Done():
		return false
	}
}

func waitForTarget(client *http.Client, target string) (targetStats, error) {
	deadline := time.Now().Add(180 * time.Second)
	for time.Now().Before(deadline) {
		if stats, err := fetchStats(client, target); err == nil {
			return stats, nil
		}
		time.Sleep(500 * time.Millisecond)
	}
	return targetStats{}, errors.New("target did not answer /stats within 180s: " + target)
}

func fetchStats(client *http.Client, target string) (targetStats, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, target+"/stats", nil)
	if err != nil {
		return targetStats{}, err
	}
	resp, err := client.Do(req)
	if err != nil {
		return targetStats{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return targetStats{}, fmt.Errorf("/stats answered %d", resp.StatusCode)
	}
	var stats targetStats
	return stats, json.NewDecoder(resp.Body).Decode(&stats)
}

func remoteStats(client *http.Client, target string) func() targetStats {
	var mu sync.Mutex
	var last targetStats
	return func() targetStats {
		mu.Lock()
		defer mu.Unlock()
		if stats, err := fetchStats(client, target); err == nil {
			last = stats
		}
		return last
	}
}
