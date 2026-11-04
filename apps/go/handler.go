package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"math/big"
	"net/http"
	"runtime"
	"strconv"
	"sync"
	"time"
)

const (
	defaultLimit = 20
	maxLimit     = 100
)

type Account struct {
	ID           int64  `json:"id"`
	Owner        string `json:"owner"`
	Currency     string `json:"currency"`
	BalanceMinor int64  `json:"balanceMinor"`
}

type Txn struct {
	ID          int64     `json:"id"`
	BookedAt    time.Time `json:"bookedAt"`
	AmountMinor int64     `json:"amountMinor"`
	Description string    `json:"description"`
}

type Statement struct {
	Account
	BalanceUsdMinor int64 `json:"balanceUsdMinor"`
	Transactions    []Txn `json:"transactions"`
}

type accountStore interface {
	account(ctx context.Context, id int64) (Account, error)
	transactions(ctx context.Context, id int64, limit int) ([]Txn, error)
}

type ratesSource interface {
	usdRates(ctx context.Context) (map[string]string, error)
}

type handler struct {
	store    accountStore
	rates    ratesSource
	poolSize int
}

var errBadRequest = errors.New("bad request")

func newHandler(store accountStore, rates ratesSource, poolSize int) http.Handler {
	h := &handler{store: store, rates: rates, poolSize: poolSize}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /accounts/{id}", h.account)
	mux.HandleFunc("GET /accounts/{id}/statement", h.statement)
	mux.HandleFunc("GET /health", func(w http.ResponseWriter, _ *http.Request) { w.Write([]byte("ok")) })
	mux.HandleFunc("GET /stats", h.stats)
	return mux
}

func (h *handler) account(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		writeError(w, errBadRequest)
		return
	}
	account, err := h.store.account(r.Context(), id)
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, account)
}

func (h *handler) statement(w http.ResponseWriter, r *http.Request) {
	id, idErr := strconv.ParseInt(r.PathValue("id"), 10, 64)
	limit, limitErr := parseLimit(r.URL.Query().Get("limit"))
	if idErr != nil || limitErr != nil {
		writeError(w, errBadRequest)
		return
	}
	statement, err := h.fanOut(r.Context(), id, limit)
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, statement)
}

func (h *handler) fanOut(parent context.Context, id int64, limit int) (Statement, error) {
	ctx, cancel := context.WithCancelCause(parent)
	defer cancel(nil)
	var (
		calls   sync.WaitGroup
		account Account
		txns    []Txn
		rates   map[string]string
	)
	calls.Go(func() {
		var err error
		if account, err = h.store.account(ctx, id); err != nil {
			cancel(err)
		}
	})
	calls.Go(func() {
		var err error
		if txns, err = h.store.transactions(ctx, id, limit); err != nil {
			cancel(err)
		}
	})
	calls.Go(func() {
		var err error
		if rates, err = h.rates.usdRates(ctx); err != nil {
			cancel(err)
		}
	})
	calls.Wait()
	if err := context.Cause(ctx); err != nil {
		return Statement{}, err
	}
	usdMinor, err := toUSDMinor(account, rates)
	return Statement{Account: account, BalanceUsdMinor: usdMinor, Transactions: txns}, err
}

func toUSDMinor(account Account, rates map[string]string) (int64, error) {
	rate, ok := new(big.Rat).SetString(rates[account.Currency])
	if !ok {
		return 0, downstreamError{fmt.Errorf("no usable rate for %s", account.Currency)}
	}
	return roundHalfEven(rate.Mul(rate, new(big.Rat).SetInt64(account.BalanceMinor))), nil
}

func roundHalfEven(r *big.Rat) int64 {
	quotient, remainder := new(big.Int).QuoRem(r.Num(), r.Denom(), new(big.Int))
	twice := new(big.Int).Lsh(new(big.Int).Abs(remainder), 1)
	cmp := twice.Cmp(r.Denom())
	if cmp > 0 || cmp == 0 && quotient.Bit(0) == 1 {
		quotient.Add(quotient, big.NewInt(int64(r.Sign())))
	}
	return quotient.Int64()
}

func parseLimit(text string) (int, error) {
	if text == "" {
		return defaultLimit, nil
	}
	limit, err := strconv.Atoi(text)
	if err != nil || limit < 1 || limit > maxLimit {
		return 0, errBadRequest
	}
	return limit, nil
}

func writeJSON(w http.ResponseWriter, value any) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(value)
}

func writeError(w http.ResponseWriter, err error) {
	var downstream downstreamError
	switch {
	case errors.Is(err, errBadRequest):
		http.Error(w, "bad request", http.StatusBadRequest)
	case errors.Is(err, errNotFound):
		http.Error(w, "account not found", http.StatusNotFound)
	case errors.As(err, &downstream):
		log.Print(err)
		http.Error(w, "downstream unavailable", http.StatusBadGateway)
	default:
		log.Print(err)
		http.Error(w, "database unavailable", http.StatusServiceUnavailable)
	}
}

func (h *handler) stats(w http.ResponseWriter, _ *http.Request) {
	stats := readProcessStats()
	writeJSON(w, map[string]any{
		"app": "go", "lang": "go", "version": runtime.Version(), "mode": "goroutines",
		"cpus": runtime.GOMAXPROCS(0), "pool_size": h.poolSize, "rss_mb": stats.rssMB, "cpu_seconds": stats.cpuSeconds,
	})
}
