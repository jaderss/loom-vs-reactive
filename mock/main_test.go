package main

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func newServer(t *testing.T, initial settings) *httptest.Server {
	t.Helper()
	server := httptest.NewServer(newMock(initial, "1.0900").handler())
	t.Cleanup(server.Close)
	return server
}

func get(t *testing.T, url string) (int, string) {
	t.Helper()
	resp, err := http.Get(url)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, string(body)
}

func TestRatesListEveryCurrencyAtTheConfiguredRate(t *testing.T) {
	server := newServer(t, settings{})
	status, body := get(t, server.URL+"/rates")
	if status != http.StatusOK {
		t.Fatalf("status %d", status)
	}
	var decoded struct {
		To    string            `json:"to"`
		Rates map[string]string `json:"rates"`
	}
	if err := json.Unmarshal([]byte(body), &decoded); err != nil {
		t.Fatal(err)
	}
	if decoded.To != "USD" || len(decoded.Rates) != len(currencies) || decoded.Rates["EUR"] != "1.0900" {
		t.Fatalf("unexpected body %s", body)
	}
}

func TestErrorRateOneAlwaysFails(t *testing.T) {
	server := newServer(t, settings{ErrorRate: 1})
	if status, _ := get(t, server.URL+"/rates"); status != http.StatusServiceUnavailable {
		t.Fatalf("status %d, want 503", status)
	}
}

func TestConfigChangesLatencyAtRuntime(t *testing.T) {
	server := newServer(t, settings{})
	req, _ := http.NewRequest(http.MethodPut, server.URL+"/config", strings.NewReader(`{"latency":"150ms","jitter":"0s","errorRate":0}`))
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status %d", resp.StatusCode)
	}
	if _, body := get(t, server.URL+"/config"); !strings.Contains(body, `"latency":"150ms"`) {
		t.Fatalf("config not stored: %s", body)
	}
	start := time.Now()
	get(t, server.URL+"/rates")
	if elapsed := time.Since(start); elapsed < 150*time.Millisecond {
		t.Fatalf("answered in %s, want at least 150ms", elapsed)
	}
}

func TestConfigRejectsBadDuration(t *testing.T) {
	server := newServer(t, settings{})
	req, _ := http.NewRequest(http.MethodPut, server.URL+"/config", strings.NewReader(`{"latency":"soon"}`))
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("status %d, want 400", resp.StatusCode)
	}
}

func TestDelayStaysInsideTheJitterWindow(t *testing.T) {
	for range 1000 {
		if d := delay(30*time.Millisecond, 10*time.Millisecond); d < 20*time.Millisecond || d > 40*time.Millisecond {
			t.Fatalf("delay %s outside 20ms..40ms", d)
		}
	}
}
