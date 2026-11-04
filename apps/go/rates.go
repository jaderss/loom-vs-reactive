package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"time"
)

type downstreamError struct{ err error }

func (e downstreamError) Error() string { return "downstream unavailable: " + e.err.Error() }
func (e downstreamError) Unwrap() error { return e.err }

type ratesClient struct {
	client  *http.Client
	url     string
	timeout time.Duration
}

func newRatesClient(baseURL string, timeout time.Duration) *ratesClient {
	transport := &http.Transport{
		DialContext:         (&net.Dialer{Timeout: timeout}).DialContext,
		MaxIdleConns:        0,
		MaxIdleConnsPerHost: 10000,
		IdleConnTimeout:     90 * time.Second,
	}
	return &ratesClient{client: &http.Client{Transport: transport}, url: baseURL + "/rates", timeout: timeout}
}

func (c *ratesClient) usdRates(ctx context.Context) (map[string]string, error) {
	ctx, cancel := context.WithTimeout(ctx, c.timeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.url, nil)
	if err != nil {
		return nil, downstreamError{err}
	}
	resp, err := c.client.Do(req)
	if err != nil {
		return nil, downstreamError{err}
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, downstreamError{fmt.Errorf("rates answered %d", resp.StatusCode)}
	}
	var body struct {
		Rates map[string]string `json:"rates"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		return nil, downstreamError{err}
	}
	if body.Rates == nil {
		return nil, downstreamError{errors.New("rates missing from the response")}
	}
	return body.Rates, nil
}
