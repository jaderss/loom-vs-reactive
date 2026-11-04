package main

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

const (
	accountSQL      = "select id, owner, currency, balance_minor from account where id = $1"
	transactionsSQL = "select id, booked_at, amount_minor, description from txn where account_id = $1 order by booked_at desc limit $2"
)

var errNotFound = errors.New("account not found")

type databaseError struct{ err error }

func (e databaseError) Error() string { return "database unavailable: " + e.err.Error() }
func (e databaseError) Unwrap() error { return e.err }

type store struct {
	pool           *pgxpool.Pool
	acquireTimeout time.Duration
	queryTimeout   time.Duration
}

func newStore(ctx context.Context, cfg config) (*store, error) {
	poolConfig, err := pgxpool.ParseConfig(cfg.dsn)
	if err != nil {
		return nil, err
	}
	poolConfig.MaxConns = int32(cfg.poolSize)
	poolConfig.MinConns = int32(cfg.poolSize)
	poolConfig.ConnConfig.ConnectTimeout = cfg.socketTimeout
	pool, err := pgxpool.NewWithConfig(ctx, poolConfig)
	if err != nil {
		return nil, err
	}
	return &store{pool: pool, acquireTimeout: cfg.acquireTimeout, queryTimeout: cfg.queryTimeout}, nil
}

func (s *store) close() {
	s.pool.Close()
}

func (s *store) account(ctx context.Context, id int64) (Account, error) {
	var a Account
	err := s.withConn(ctx, func(ctx context.Context, conn *pgxpool.Conn) error {
		return conn.QueryRow(ctx, accountSQL, id).Scan(&a.ID, &a.Owner, &a.Currency, &a.BalanceMinor)
	})
	if errors.Is(err, pgx.ErrNoRows) {
		return a, errNotFound
	}
	return a, err
}

func (s *store) transactions(ctx context.Context, id int64, limit int) ([]Txn, error) {
	txns := make([]Txn, 0, limit)
	err := s.withConn(ctx, func(ctx context.Context, conn *pgxpool.Conn) error {
		rows, err := conn.Query(ctx, transactionsSQL, id, int32(limit))
		if err != nil {
			return err
		}
		var t Txn
		_, err = pgx.ForEachRow(rows, []any{&t.ID, &t.BookedAt, &t.AmountMinor, &t.Description}, func() error {
			t.BookedAt = t.BookedAt.UTC()
			txns = append(txns, t)
			return nil
		})
		return err
	})
	return txns, err
}

func (s *store) withConn(ctx context.Context, query func(context.Context, *pgxpool.Conn) error) error {
	acquireCtx, cancelAcquire := context.WithTimeout(ctx, s.acquireTimeout)
	conn, err := s.pool.Acquire(acquireCtx)
	cancelAcquire()
	if err != nil {
		return databaseError{err}
	}
	defer conn.Release()
	queryCtx, cancelQuery := context.WithTimeout(ctx, s.queryTimeout)
	defer cancelQuery()
	err = query(queryCtx, conn)
	if err == nil || errors.Is(err, pgx.ErrNoRows) {
		return err
	}
	return databaseError{err}
}
