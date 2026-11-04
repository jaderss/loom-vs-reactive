package main

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func ratesServer(t *testing.T, handler http.HandlerFunc) *ratesClient {
	t.Helper()
	server := httptest.NewServer(handler)
	t.Cleanup(server.Close)
	return newRatesClient(server.URL, 200*time.Millisecond)
}

func TestRatesDecodesTheMap(t *testing.T) {
	client := ratesServer(t, func(w http.ResponseWriter, _ *http.Request) {
		w.Write([]byte(`{"to":"USD","rates":{"EUR":"1.0900"}}`))
	})
	rates, err := client.usdRates(t.Context())
	if err != nil || rates["EUR"] != "1.0900" {
		t.Fatalf("got %v %v", rates, err)
	}
}

func TestRatesFailuresAreDownstreamErrors(t *testing.T) {
	cases := map[string]http.HandlerFunc{
		"status 503":   func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusServiceUnavailable) },
		"no rates":     func(w http.ResponseWriter, _ *http.Request) { w.Write([]byte(`{"to":"USD"}`)) },
		"invalid json": func(w http.ResponseWriter, _ *http.Request) { w.Write([]byte(`{`)) },
		"too slow":     func(w http.ResponseWriter, r *http.Request) { <-r.Context().Done() },
	}
	for name, handler := range cases {
		t.Run(name, func(t *testing.T) {
			_, err := ratesServer(t, handler).usdRates(t.Context())
			var downstream downstreamError
			if !errors.As(err, &downstream) {
				t.Fatalf("got %v, want a downstream error", err)
			}
		})
	}
}
