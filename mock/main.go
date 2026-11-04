package main

import (
	"cmp"
	"encoding/json"
	"log"
	"math/rand/v2"
	"net/http"
	"os"
	"strconv"
	"sync/atomic"
	"time"
)

var currencies = []string{"EUR", "GBP", "CHF", "CAD", "AUD"}

type settings struct {
	Latency   duration `json:"latency"`
	Jitter    duration `json:"jitter"`
	ErrorRate float64  `json:"errorRate"`
}

type duration time.Duration

func (d duration) MarshalJSON() ([]byte, error) {
	return json.Marshal(time.Duration(d).String())
}

func (d *duration) UnmarshalJSON(data []byte) error {
	var text string
	if err := json.Unmarshal(data, &text); err != nil {
		return err
	}
	parsed, err := time.ParseDuration(text)
	*d = duration(parsed)
	return err
}

type mock struct {
	current atomic.Pointer[settings]
	rates   []byte
}

func main() {
	initial := settings{
		Latency:   duration(envDuration("MOCK_LATENCY", 30*time.Millisecond)),
		Jitter:    duration(envDuration("MOCK_JITTER", 10*time.Millisecond)),
		ErrorRate: envFloat("MOCK_ERROR_RATE", 0),
	}
	rate := cmp.Or(os.Getenv("MOCK_RATE"), "1.0900")
	log.Printf("rates mock on :8080 latency=%s jitter=%s error_rate=%.2f rate=%s",
		time.Duration(initial.Latency), time.Duration(initial.Jitter), initial.ErrorRate, rate)
	server := &http.Server{Addr: ":8080", Handler: newMock(initial, rate).handler(), ReadHeaderTimeout: 10 * time.Second}
	log.Fatal(server.ListenAndServe())
}

func newMock(initial settings, rate string) *mock {
	rates := make(map[string]string, len(currencies))
	for _, currency := range currencies {
		rates[currency] = rate
	}
	body, _ := json.Marshal(map[string]any{"to": "USD", "rates": rates})
	m := &mock{rates: body}
	m.current.Store(&initial)
	return m
}

func (m *mock) handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /rates", m.serveRates)
	mux.HandleFunc("GET /config", m.serveConfig)
	mux.HandleFunc("PUT /config", m.updateConfig)
	mux.HandleFunc("GET /health", func(w http.ResponseWriter, _ *http.Request) { w.Write([]byte("ok")) })
	return mux
}

func (m *mock) serveRates(w http.ResponseWriter, r *http.Request) {
	current := m.current.Load()
	select {
	case <-time.After(delay(time.Duration(current.Latency), time.Duration(current.Jitter))):
	case <-r.Context().Done():
		return
	}
	if rand.Float64() < current.ErrorRate {
		http.Error(w, "rates unavailable", http.StatusServiceUnavailable)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Write(m.rates)
}

func (m *mock) serveConfig(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(m.current.Load())
}

func (m *mock) updateConfig(w http.ResponseWriter, r *http.Request) {
	var next settings
	if err := json.NewDecoder(r.Body).Decode(&next); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	m.current.Store(&next)
	log.Printf("config latency=%s jitter=%s error_rate=%.2f", time.Duration(next.Latency), time.Duration(next.Jitter), next.ErrorRate)
	m.serveConfig(w, r)
}

func delay(latency, jitter time.Duration) time.Duration {
	if jitter <= 0 {
		return latency
	}
	return max(0, latency-jitter+rand.N(2*jitter+1))
}

func envDuration(key string, fallback time.Duration) time.Duration {
	if value, err := time.ParseDuration(os.Getenv(key)); err == nil {
		return value
	}
	return fallback
}

func envFloat(key string, fallback float64) float64 {
	if value, err := strconv.ParseFloat(os.Getenv(key), 64); err == nil {
		return value
	}
	return fallback
}
