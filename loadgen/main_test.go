package main

import (
	"encoding/csv"
	"encoding/json"
	"fmt"
	"math/big"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"testing"
	"time"

	"github.com/jaderss/loom-vs-reactive/loadgen/seed"
)

func fakeApp(t *testing.T, corrupt bool) *httptest.Server {
	t.Helper()
	rate, _ := new(big.Rat).SetString("1.0900")
	mux := http.NewServeMux()
	mux.HandleFunc("GET /stats", func(w http.ResponseWriter, _ *http.Request) {
		fmt.Fprint(w, `{"app":"fake","lang":"go","version":"1","mode":"test","cpus":2,"pool_size":20,"rss_mb":10,"cpu_seconds":1}`)
	})
	mux.HandleFunc("GET /accounts/{id}", func(w http.ResponseWriter, r *http.Request) {
		id, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
		account := seed.AccountFor(id)
		if corrupt {
			account.BalanceMinor++
		}
		json.NewEncoder(w).Encode(account)
	})
	mux.HandleFunc("GET /accounts/{id}/statement", func(w http.ResponseWriter, r *http.Request) {
		id, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
		limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
		json.NewEncoder(w).Encode(seed.StatementFor(id, 5, limit, rate))
	})
	server := httptest.NewServer(mux)
	t.Cleanup(server.Close)
	return server
}

func testConfig(t *testing.T, target, scenario string) config {
	rate, _ := new(big.Rat).SetString("1.0900")
	return config{
		target: target, scenario: scenario, users: 5, thinkTime: 10 * time.Millisecond,
		duration: 1200 * time.Millisecond, rampUp: 100 * time.Millisecond, arrivalRates: []int{50, 100},
		timeout: time.Second, sampleEvery: 1, limit: 3, accounts: 100, perAccount: 5, rate: rate,
		runID: "test", resultsDir: t.TempDir(),
	}
}

func readCSV(t *testing.T, cfg config, app string) [][]string {
	t.Helper()
	file, err := os.Open(filepath.Join(cfg.resultsDir, fmt.Sprintf("test_%s_%s.csv", cfg.scenario, app)))
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	rows, err := csv.NewReader(file).ReadAll()
	if err != nil {
		t.Fatal(err)
	}
	return rows
}

func column(row []string, name string) string {
	for i, h := range header {
		if h == name {
			return row[i]
		}
	}
	panic("no column " + name)
}

func summaries(rows [][]string) [][]string {
	var found [][]string
	for _, row := range rows[1:] {
		if row[0] == "summary" {
			found = append(found, row)
		}
	}
	return found
}

func TestClosedModelWritesOneSummaryWithCorrectBodies(t *testing.T) {
	cfg := testConfig(t, fakeApp(t, false).URL, "statement")
	if err := run(cfg); err != nil {
		t.Fatal(err)
	}
	rows := readCSV(t, cfg, "fake")
	if len(rows[0]) != len(header) || rows[0][0] != "kind" {
		t.Fatalf("header %v", rows[0])
	}
	found := summaries(rows)
	if len(found) != 1 {
		t.Fatalf("got %d summaries, want 1", len(found))
	}
	s := found[0]
	if column(s, "load_model") != "closed" || column(s, "bad_bodies") != "0" || column(s, "errors") != "0" || column(s, "completed") == "0" {
		t.Fatalf("unexpected summary %v", s)
	}
	if column(s, "pool_size") != "20" || column(s, "app") != "fake" || column(s, "scenario") != "statement" {
		t.Fatalf("run info missing from %v", s)
	}
}

func TestBadBodiesAreCounted(t *testing.T) {
	cfg := testConfig(t, fakeApp(t, true).URL, "read")
	if err := run(cfg); err != nil {
		t.Fatal(err)
	}
	s := summaries(readCSV(t, cfg, "fake"))[0]
	if column(s, "bad_bodies") == "0" || column(s, "bad_bodies") != column(s, "completed") {
		t.Fatalf("every sampled body is wrong, got bad_bodies=%s completed=%s", column(s, "bad_bodies"), column(s, "completed"))
	}
}

func TestOpenModelWritesASummaryPerArrivalRate(t *testing.T) {
	cfg := testConfig(t, fakeApp(t, false).URL, "overload")
	cfg.appLabel = "labelled"
	if err := run(cfg); err != nil {
		t.Fatal(err)
	}
	found := summaries(readCSV(t, cfg, "labelled"))
	if len(found) != 2 {
		t.Fatalf("got %d summaries, want 2", len(found))
	}
	for i, want := range []string{"50", "100"} {
		if column(found[i], "arrival_rate") != want || column(found[i], "load_model") != "open" {
			t.Fatalf("summary %d: %v", i, found[i])
		}
	}
	completed, _ := strconv.Atoi(column(found[1], "completed"))
	if completed < 100 || completed > 130 {
		t.Fatalf("100/s for 1.2s completed %d requests", completed)
	}
}

func TestErrorsAndTimeoutsAreSeparate(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /stats", func(w http.ResponseWriter, _ *http.Request) { fmt.Fprint(w, `{"app":"slow"}`) })
	mux.HandleFunc("GET /accounts/{id}", func(w http.ResponseWriter, r *http.Request) {
		if r.PathValue("id") == "1" {
			http.Error(w, "boom", http.StatusBadGateway)
			return
		}
		time.Sleep(300 * time.Millisecond)
	})
	server := httptest.NewServer(mux)
	t.Cleanup(server.Close)
	r := &runner{cfg: config{target: server.URL, scenario: "read", timeout: 100 * time.Millisecond}, client: server.Client()}
	if result, _ := r.call(t.Context(), 1); result != failed {
		t.Fatalf("502 classified as %d", result)
	}
	if result, _ := r.call(t.Context(), 2); result != timedOut {
		t.Fatalf("slow answer classified as %d", result)
	}
}

func TestUnknownScenarioFails(t *testing.T) {
	if _, err := loadModelFor("primes"); err == nil {
		t.Fatal("want an error")
	}
}

func TestPercentileIsNearestRank(t *testing.T) {
	sorted := []float64{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}
	for p, want := range map[float64]float64{50: 5, 95: 10, 99.9: 10, 10: 1, 11: 2} {
		if got := percentile(sorted, p); got != want {
			t.Errorf("p%.1f = %.0f, want %.0f", p, got, want)
		}
	}
	if percentile(nil, 99) != 0 {
		t.Error("empty input must give 0")
	}
}
