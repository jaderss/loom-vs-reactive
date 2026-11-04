package contract

import (
	"bytes"
	"cmp"
	"encoding/json"
	"fmt"
	"io"
	"math/big"
	"net/http"
	"os"
	"reflect"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/jaderss/loom-vs-reactive/loadgen/seed"
)

var (
	baseURL           = cmp.Or(os.Getenv("BASE_URL"), "http://localhost:8080")
	mockURL           = os.Getenv("MOCK_URL")
	toxiproxyURL      = os.Getenv("TOXIPROXY_URL")
	accounts          = int64(envInt("SEED_ACCOUNTS", 100000))
	perAccount        = envInt("SEED_TXN_PER_ACCOUNT", 50)
	downstreamTimeout = time.Duration(envInt("DOWNSTREAM_TIMEOUT_MS", 1000)) * time.Millisecond
	socketTimeout     = time.Duration(envInt("DB_SOCKET_TIMEOUT_S", 5)) * time.Second
	rate              = mustRate(cmp.Or(os.Getenv("MOCK_RATE"), "1.0900"))
	client            = &http.Client{Timeout: 30 * time.Second}
)

func envInt(key string, fallback int) int {
	if value, err := strconv.Atoi(os.Getenv(key)); err == nil && value > 0 {
		return value
	}
	return fallback
}

func mustRate(text string) *big.Rat {
	r, ok := new(big.Rat).SetString(text)
	if !ok {
		panic("MOCK_RATE is not a decimal: " + text)
	}
	return r
}

func TestMain(m *testing.M) {
	deadline := time.Now().Add(180 * time.Second)
	for {
		resp, err := client.Get(baseURL + "/health")
		if err == nil {
			resp.Body.Close()
			if resp.StatusCode == http.StatusOK {
				break
			}
		}
		if time.Now().After(deadline) {
			fmt.Fprintf(os.Stderr, "%s/health did not answer 200 within 180s: %v\n", baseURL, err)
			os.Exit(1)
		}
		time.Sleep(500 * time.Millisecond)
	}
	os.Exit(m.Run())
}

type response struct {
	status  int
	body    []byte
	elapsed time.Duration
}

func get(t *testing.T, path string) response {
	t.Helper()
	start := time.Now()
	resp, err := client.Get(baseURL + path)
	if err != nil {
		t.Fatalf("GET %s: %v", path, err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatalf("GET %s body: %v", path, err)
	}
	return response{status: resp.StatusCode, body: body, elapsed: time.Since(start)}
}

func expectStatus(t *testing.T, path string, status int, body string) response {
	t.Helper()
	got := get(t, path)
	if got.status != status {
		t.Fatalf("GET %s: status %d, want %d, body %q", path, got.status, status, got.body)
	}
	if body != "" && strings.TrimSpace(string(got.body)) != body {
		t.Fatalf("GET %s: body %q, want %q", path, got.body, body)
	}
	return got
}

func expectJSON[T any](t *testing.T, path string, want T) {
	t.Helper()
	got := expectStatus(t, path, http.StatusOK, "")
	decoder := json.NewDecoder(bytes.NewReader(got.body))
	decoder.DisallowUnknownFields()
	var decoded T
	if err := decoder.Decode(&decoded); err != nil {
		t.Fatalf("GET %s: %v in %s", path, err, got.body)
	}
	if !reflect.DeepEqual(decoded, want) {
		t.Fatalf("GET %s:\n got %+v\nwant %+v", path, decoded, want)
	}
}

func TestHealthAnswers200(t *testing.T) {
	expectStatus(t, "/health", http.StatusOK, "")
}

func TestStatsDescribeTheApp(t *testing.T) {
	got := expectStatus(t, "/stats", http.StatusOK, "")
	var stats struct {
		App      string  `json:"app"`
		Lang     string  `json:"lang"`
		Version  string  `json:"version"`
		Mode     string  `json:"mode"`
		CPUs     int     `json:"cpus"`
		PoolSize int     `json:"pool_size"`
		RssMB    float64 `json:"rss_mb"`
		CPU      float64 `json:"cpu_seconds"`
	}
	if err := json.Unmarshal(got.body, &stats); err != nil {
		t.Fatal(err)
	}
	if stats.App == "" || stats.Lang == "" || stats.Version == "" || stats.Mode == "" || stats.CPUs < 1 || stats.PoolSize < 1 {
		t.Fatalf("incomplete stats %s", got.body)
	}
}

func TestAccountMatchesTheSeed(t *testing.T) {
	for _, id := range []int64{1, 42, accounts} {
		expectJSON(t, fmt.Sprintf("/accounts/%d", id), seed.AccountFor(id))
	}
}

func TestUnknownAccountIs404(t *testing.T) {
	expectStatus(t, fmt.Sprintf("/accounts/%d", accounts+1), http.StatusNotFound, "account not found")
}

func TestAccountIdMustBeANumber(t *testing.T) {
	expectStatus(t, "/accounts/abc", http.StatusBadRequest, "bad request")
}

func TestStatementDefaultsToTwentyTransactions(t *testing.T) {
	expectJSON(t, "/accounts/7/statement", seed.StatementFor(7, perAccount, 20, rate))
}

func TestStatementHonoursTheLimit(t *testing.T) {
	for _, limit := range []int{1, 5, 100} {
		expectJSON(t, fmt.Sprintf("/accounts/%d/statement?limit=%d", accounts, limit), seed.StatementFor(accounts, perAccount, limit, rate))
	}
}

func TestStatementRejectsALimitOutOfRange(t *testing.T) {
	for _, limit := range []string{"0", "101", "-1", "many"} {
		expectStatus(t, "/accounts/1/statement?limit="+limit, http.StatusBadRequest, "bad request")
	}
}

func TestStatementOfUnknownAccountIs404(t *testing.T) {
	expectStatus(t, fmt.Sprintf("/accounts/%d/statement", accounts+1), http.StatusNotFound, "account not found")
}

func TestStatementRoundsHalfToEven(t *testing.T) {
	found := map[bool]int64{}
	for id := int64(1); id <= accounts && len(found) < 2; id++ {
		exact := new(big.Rat).Mul(new(big.Rat).SetInt64(seed.AccountFor(id).BalanceMinor), rate)
		twice := new(big.Rat).Mul(exact, big.NewRat(2, 1))
		if exact.IsInt() || !twice.IsInt() {
			continue
		}
		truncated := new(big.Int).Quo(exact.Num(), exact.Denom())
		if _, seen := found[truncated.Bit(0) == 0]; !seen {
			found[truncated.Bit(0) == 0] = id
		}
	}
	if len(found) < 2 {
		t.Skipf("MOCK_RATE %s gives no balance that lands on .5 both ways", rate.FloatString(4))
	}
	for _, id := range found {
		expectJSON(t, fmt.Sprintf("/accounts/%d/statement?limit=1", id), seed.StatementFor(id, perAccount, 1, rate))
	}
}

func TestDownstreamFailureIs502(t *testing.T) {
	needMock(t)
	setMock(t, "0s", 1)
	expectStatus(t, "/accounts/1/statement", http.StatusBadGateway, "downstream unavailable")
	expectJSON(t, "/accounts/1", seed.AccountFor(1))
}

func TestDownstreamTimeoutIs502(t *testing.T) {
	needMock(t)
	setMock(t, (downstreamTimeout + 2*time.Second).String(), 0)
	got := expectStatus(t, "/accounts/1/statement", http.StatusBadGateway, "downstream unavailable")
	if limit := downstreamTimeout + time.Second; got.elapsed > limit {
		t.Fatalf("answered in %s, want under %s", got.elapsed, limit)
	}
}

func TestFirstFailureCancelsTheOtherCalls(t *testing.T) {
	needMock(t)
	needToxiproxy(t)
	setMock(t, "0s", 1)
	toxiproxy(t, http.MethodPost, "/proxies/postgres/toxics", `{"name":"lag","type":"latency","stream":"downstream","attributes":{"latency":1500}}`)
	got := expectStatus(t, "/accounts/1/statement", http.StatusBadGateway, "downstream unavailable")
	if got.elapsed > time.Second {
		t.Fatalf("answered in %s, the failed rate call should end the request before the 1.5s queries", got.elapsed)
	}
}

func TestDatabaseDownIs503(t *testing.T) {
	needToxiproxy(t)
	toxiproxy(t, http.MethodPost, "/proxies/postgres", `{"enabled":false}`)
	got := expectStatus(t, "/accounts/1", http.StatusServiceUnavailable, "database unavailable")
	if limit := socketTimeout + 2*time.Second; got.elapsed > limit {
		t.Fatalf("answered in %s, want under %s", got.elapsed, limit)
	}
	expectStatus(t, "/accounts/1/statement", http.StatusServiceUnavailable, "database unavailable")
}

func TestSlowDatabaseIs503(t *testing.T) {
	needToxiproxy(t)
	toxiproxy(t, http.MethodPost, "/proxies/postgres/toxics", `{"name":"lag","type":"latency","stream":"downstream","attributes":{"latency":10000}}`)
	got := expectStatus(t, "/accounts/1", http.StatusServiceUnavailable, "database unavailable")
	if limit := socketTimeout + 2*time.Second; got.elapsed > limit {
		t.Fatalf("answered in %s, want under %s", got.elapsed, limit)
	}
}

func needMock(t *testing.T) {
	if mockURL == "" {
		t.Skip("MOCK_URL not set")
	}
}

func needToxiproxy(t *testing.T) {
	if toxiproxyURL == "" {
		t.Skip("TOXIPROXY_URL not set")
	}
}

func setMock(t *testing.T, latency string, errorRate float64) {
	t.Helper()
	send(t, http.MethodPut, mockURL+"/config", fmt.Sprintf(`{"latency":%q,"jitter":"0s","errorRate":%g}`, latency, errorRate))
	t.Cleanup(func() {
		send(t, http.MethodPut, mockURL+"/config", fmt.Sprintf(`{"latency":%q,"jitter":%q,"errorRate":0}`,
			cmp.Or(os.Getenv("MOCK_LATENCY"), "30ms"), cmp.Or(os.Getenv("MOCK_JITTER"), "10ms")))
	})
}

func toxiproxy(t *testing.T, method, path, body string) {
	t.Helper()
	send(t, method, toxiproxyURL+path, body)
	t.Cleanup(func() {
		send(t, http.MethodPost, toxiproxyURL+"/reset", "")
		waitForRecovery(t)
	})
}

func waitForRecovery(t *testing.T) {
	t.Helper()
	deadline := time.Now().Add(60 * time.Second)
	for time.Now().Before(deadline) {
		if resp, err := client.Get(baseURL + "/accounts/1"); err == nil {
			resp.Body.Close()
			if resp.StatusCode == http.StatusOK {
				return
			}
		}
		time.Sleep(250 * time.Millisecond)
	}
	t.Fatal("app did not recover within 60s after the database came back")
}

func send(t *testing.T, method, url, body string) {
	t.Helper()
	req, err := http.NewRequest(method, url, strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := client.Do(req)
	if err != nil {
		t.Fatalf("%s %s: %v", method, url, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		reply, _ := io.ReadAll(resp.Body)
		t.Fatalf("%s %s: status %d %s", method, url, resp.StatusCode, reply)
	}
}
