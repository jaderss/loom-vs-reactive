package seed

import (
	"math/big"
	"testing"
)

func TestRoundHalfEven(t *testing.T) {
	cases := map[string]int64{
		"163/2": 82, "165/2": 82, "167/2": 84, "-163/2": -82, "-165/2": -82,
		"10/3": 3, "11/3": 4, "-11/3": -4, "7": 7, "0": 0,
	}
	for input, want := range cases {
		r, _ := new(big.Rat).SetString(input)
		if got := RoundHalfEven(r); got != want {
			t.Errorf("RoundHalfEven(%s) = %d, want %d", input, got, want)
		}
	}
}

func TestAccountFollowsTheSeedFormula(t *testing.T) {
	got := AccountFor(42)
	want := Account{ID: 42, Owner: "owner-42", Currency: "CHF", BalanceMinor: 332598}
	if got != want {
		t.Fatalf("got %+v, want %+v", got, want)
	}
}

func TestLatestTransactionsAreNewestFirstAndCapped(t *testing.T) {
	txns := LatestTransactions(2, 50, 3)
	if len(txns) != 3 {
		t.Fatalf("got %d transactions, want 3", len(txns))
	}
	first := Txn{ID: 100, BookedAt: "2026-01-03T02:00:02Z", AmountMinor: (2*31+50*7919)%20000 - 15000, Description: "fee"}
	if txns[0] != first {
		t.Fatalf("got %+v, want %+v", txns[0], first)
	}
	if txns[1].ID != 99 || txns[2].ID != 98 {
		t.Fatalf("ids %d %d, want 99 98", txns[1].ID, txns[2].ID)
	}
}

func TestLatestTransactionsStopAtTheSeededCount(t *testing.T) {
	if txns := LatestTransactions(1, 5, 20); len(txns) != 5 {
		t.Fatalf("got %d transactions, want 5", len(txns))
	}
}

func TestStatementConvertsTheBalanceHalfEven(t *testing.T) {
	rate, _ := new(big.Rat).SetString("1.0900")
	for id := int64(1); id < 2000; id++ {
		if AccountFor(id).BalanceMinor%100 != 50 {
			continue
		}
		statement := StatementFor(id, 50, 20, rate)
		exact := new(big.Rat).Mul(new(big.Rat).SetInt64(statement.BalanceMinor), rate)
		if !exact.IsInt() && statement.BalanceUsdMinor%2 != 0 {
			t.Fatalf("account %d: %s rounded to odd %d", id, exact.FloatString(2), statement.BalanceUsdMinor)
		}
		return
	}
	t.Fatal("no account lands on .5")
}
