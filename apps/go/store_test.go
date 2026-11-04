package main

import (
	"errors"
	"os"
	"testing"
	"time"
)

func testStore(t *testing.T) *store {
	t.Helper()
	dsn := os.Getenv("TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("TEST_DATABASE_URL not set")
	}
	s, err := newStore(t.Context(), config{dsn: dsn, poolSize: 2, acquireTimeout: time.Second, queryTimeout: time.Second, socketTimeout: time.Second})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(s.close)
	return s
}

func TestStoreReadsTheSeededAccount(t *testing.T) {
	s := testStore(t)
	account, err := s.account(t.Context(), 42)
	if err != nil {
		t.Fatal(err)
	}
	if account != (Account{ID: 42, Owner: "owner-42", Currency: "CHF", BalanceMinor: 332598}) {
		t.Fatalf("got %+v", account)
	}
	if _, err := s.account(t.Context(), -1); !errors.Is(err, errNotFound) {
		t.Fatalf("got %v, want errNotFound", err)
	}
}

func TestStoreReadsTheLatestTransactionsFirst(t *testing.T) {
	s := testStore(t)
	txns, err := s.transactions(t.Context(), 2, 3)
	if err != nil {
		t.Fatal(err)
	}
	if len(txns) != 3 || !txns[0].BookedAt.After(txns[1].BookedAt) || txns[0].BookedAt.Location() != time.UTC {
		t.Fatalf("got %+v", txns)
	}
}

func TestStoreQueryTimeoutIsADatabaseError(t *testing.T) {
	s := testStore(t)
	s.queryTimeout = time.Nanosecond
	var dbErr databaseError
	if _, err := s.account(t.Context(), 1); !errors.As(err, &dbErr) {
		t.Fatalf("got %v, want a database error", err)
	}
}
