package main

import (
	"cmp"
	"context"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"time"
)

type config struct {
	dsn               string
	poolSize          int
	acquireTimeout    time.Duration
	queryTimeout      time.Duration
	socketTimeout     time.Duration
	ratesURL          string
	downstreamTimeout time.Duration
}

func loadConfig() config {
	dsn := url.URL{
		Scheme: "postgres",
		User:   url.UserPassword(cmp.Or(os.Getenv("DB_USER"), "bench"), cmp.Or(os.Getenv("DB_PASSWORD"), "bench")),
		Host:   cmp.Or(os.Getenv("DB_HOST"), "localhost") + ":" + cmp.Or(os.Getenv("DB_PORT"), "5432"),
		Path:   cmp.Or(os.Getenv("DB_NAME"), "bench"),
	}
	return config{
		dsn:               dsn.String(),
		poolSize:          envInt("POOL_SIZE", 20),
		acquireTimeout:    time.Duration(envInt("DB_ACQUIRE_TIMEOUT_MS", 2000)) * time.Millisecond,
		queryTimeout:      time.Duration(envInt("DB_QUERY_TIMEOUT_MS", 2000)) * time.Millisecond,
		socketTimeout:     time.Duration(envInt("DB_SOCKET_TIMEOUT_S", 5)) * time.Second,
		ratesURL:          cmp.Or(os.Getenv("RATES_URL"), "http://localhost:8090"),
		downstreamTimeout: time.Duration(envInt("DOWNSTREAM_TIMEOUT_MS", 1000)) * time.Millisecond,
	}
}

func envInt(key string, fallback int) int {
	if value, err := strconv.Atoi(os.Getenv(key)); err == nil && value > 0 {
		return value
	}
	return fallback
}

func main() {
	cfg := loadConfig()
	store, err := newStore(context.Background(), cfg)
	if err != nil {
		log.Fatal(err)
	}
	defer store.close()
	port := cmp.Or(os.Getenv("PORT"), "8080")
	log.Printf("go app on :%s pool=%d", port, cfg.poolSize)
	server := &http.Server{
		Addr:              fmt.Sprintf(":%s", port),
		Handler:           newHandler(store, newRatesClient(cfg.ratesURL, cfg.downstreamTimeout), cfg.poolSize),
		ReadHeaderTimeout: 10 * time.Second,
	}
	log.Fatal(server.ListenAndServe())
}
