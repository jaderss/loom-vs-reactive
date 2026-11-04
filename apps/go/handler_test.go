package main

import (
	"context"
	"errors"
	"io"
	"math/big"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

type fakeStore struct {
	accountErr error
	txnDelay   time.Duration
	txnErr     error
	cancelled  chan struct{}
}

func (f *fakeStore) account(_ context.Context, id int64) (Account, error) {
	if f.accountErr != nil {
		return Account{}, f.accountErr
	}
	return Account{ID: id, Owner: "owner-1", Currency: "EUR", BalanceMinor: 150}, nil
}

func (f *fakeStore) transactions(ctx context.Context, _ int64, limit int) ([]Txn, error) {
	select {
	case <-time.After(f.txnDelay):
	case <-ctx.Done():
		if f.cancelled != nil {
			close(f.cancelled)
		}
		return nil, databaseError{ctx.Err()}
	}
	if f.txnErr != nil {
		return nil, f.txnErr
	}
	txns := make([]Txn, 0, limit)
	for i := range min(limit, 2) {
		txns = append(txns, Txn{ID: int64(i + 1), BookedAt: time.Date(2026, 1, 1, 1, 0, 0, 0, time.UTC), AmountMinor: -4599, Description: "card"})
	}
	return txns, nil
}

type fakeRates struct {
	rates map[string]string
	err   error
}

func (f fakeRates) usdRates(context.Context) (map[string]string, error) {
	return f.rates, f.err
}

var eurRates = fakeRates{rates: map[string]string{"EUR": "1.0900"}}

func serve(t *testing.T, store accountStore, rates ratesSource, path string) (int, string, time.Duration) {
	t.Helper()
	server := httptest.NewServer(newHandler(store, rates, 20))
	t.Cleanup(server.Close)
	start := time.Now()
	resp, err := http.Get(server.URL + path)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, strings.TrimSpace(string(body)), time.Since(start)
}

func TestStatementJoinsTheThreeCalls(t *testing.T) {
	status, body, _ := serve(t, &fakeStore{}, eurRates, "/accounts/1/statement?limit=1")
	want := `{"id":1,"owner":"owner-1","currency":"EUR","balanceMinor":150,"balanceUsdMinor":164,` +
		`"transactions":[{"id":1,"bookedAt":"2026-01-01T01:00:00Z","amountMinor":-4599,"description":"card"}]}`
	if status != http.StatusOK || body != want {
		t.Fatalf("got %d %s", status, body)
	}
}

func TestStatementErrors(t *testing.T) {
	cases := []struct {
		name   string
		store  *fakeStore
		rates  fakeRates
		path   string
		status int
		body   string
	}{
		{"unknown account", &fakeStore{accountErr: errNotFound}, eurRates, "/accounts/9/statement", 404, "account not found"},
		{"rates down", &fakeStore{}, fakeRates{err: downstreamError{errors.New("503")}}, "/accounts/1/statement", 502, "downstream unavailable"},
		{"currency missing", &fakeStore{}, fakeRates{rates: map[string]string{"GBP": "1.2"}}, "/accounts/1/statement", 502, "downstream unavailable"},
		{"database down", &fakeStore{txnErr: databaseError{errors.New("refused")}}, eurRates, "/accounts/1/statement", 503, "database unavailable"},
		{"limit zero", &fakeStore{}, eurRates, "/accounts/1/statement?limit=0", 400, "bad request"},
		{"limit too high", &fakeStore{}, eurRates, "/accounts/1/statement?limit=101", 400, "bad request"},
		{"limit not a number", &fakeStore{}, eurRates, "/accounts/1/statement?limit=x", 400, "bad request"},
		{"id not a number", &fakeStore{}, eurRates, "/accounts/x/statement", 400, "bad request"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if status, body, _ := serve(t, c.store, c.rates, c.path); status != c.status || body != c.body {
				t.Fatalf("got %d %q, want %d %q", status, body, c.status, c.body)
			}
		})
	}
}

func TestFirstFailureCancelsTheOtherCalls(t *testing.T) {
	store := &fakeStore{txnDelay: 10 * time.Second, cancelled: make(chan struct{})}
	status, _, elapsed := serve(t, store, fakeRates{err: downstreamError{errors.New("503")}}, "/accounts/1/statement")
	if status != http.StatusBadGateway || elapsed > 5*time.Second {
		t.Fatalf("got %d after %s, want 502 at once", status, elapsed)
	}
	select {
	case <-store.cancelled:
	case <-time.After(5 * time.Second):
		t.Fatal("transactions call was not cancelled")
	}
}

func TestAccountEndpoint(t *testing.T) {
	if status, body, _ := serve(t, &fakeStore{}, eurRates, "/accounts/1"); status != 200 || body != `{"id":1,"owner":"owner-1","currency":"EUR","balanceMinor":150}` {
		t.Fatalf("got %d %s", status, body)
	}
	if status, body, _ := serve(t, &fakeStore{accountErr: errNotFound}, eurRates, "/accounts/2"); status != 404 || body != "account not found" {
		t.Fatalf("got %d %s", status, body)
	}
	if status, _, _ := serve(t, &fakeStore{}, eurRates, "/accounts/abc"); status != 400 {
		t.Fatalf("got %d, want 400", status)
	}
}

func TestHealthAndStats(t *testing.T) {
	if status, body, _ := serve(t, &fakeStore{}, eurRates, "/health"); status != 200 || body != "ok" {
		t.Fatalf("health %d %s", status, body)
	}
	if status, body, _ := serve(t, &fakeStore{}, eurRates, "/stats"); status != 200 || !strings.Contains(body, `"mode":"goroutines"`) || !strings.Contains(body, `"pool_size":20`) {
		t.Fatalf("stats %d %s", status, body)
	}
}

func TestRoundHalfEven(t *testing.T) {
	for input, want := range map[string]int64{"163/2": 82, "165/2": 82, "-165/2": -82, "-167/2": -84, "11/3": 4, "10/3": 3} {
		r, _ := new(big.Rat).SetString(input)
		if got := roundHalfEven(r); got != want {
			t.Errorf("roundHalfEven(%s) = %d, want %d", input, got, want)
		}
	}
}

func TestProcessStatsParsers(t *testing.T) {
	if got := parseRSSMB("Name:\tgo\nVmRSS:\t   2048 kB\n"); got != 2 {
		t.Errorf("rss %f, want 2", got)
	}
	stat := "1 (my app) S 0 1 1 0 -1 4194560 100 0 0 0 250 150 0 0 20 0 8 0"
	if got := parseCPUSeconds(stat); got != 4 {
		t.Errorf("cpu %f, want 4", got)
	}
}
