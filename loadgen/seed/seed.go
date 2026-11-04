package seed

import (
	"fmt"
	"math/big"
	"time"
)

var (
	currencies   = []string{"EUR", "GBP", "CHF", "CAD", "AUD"}
	descriptions = []string{"card", "transfer", "fee", "salary"}
	epoch        = time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
)

type Account struct {
	ID           int64  `json:"id"`
	Owner        string `json:"owner"`
	Currency     string `json:"currency"`
	BalanceMinor int64  `json:"balanceMinor"`
}

type Txn struct {
	ID          int64  `json:"id"`
	BookedAt    string `json:"bookedAt"`
	AmountMinor int64  `json:"amountMinor"`
	Description string `json:"description"`
}

type Statement struct {
	ID              int64  `json:"id"`
	Owner           string `json:"owner"`
	Currency        string `json:"currency"`
	BalanceMinor    int64  `json:"balanceMinor"`
	BalanceUsdMinor int64  `json:"balanceUsdMinor"`
	Transactions    []Txn  `json:"transactions"`
}

func AccountFor(id int64) Account {
	return Account{
		ID:           id,
		Owner:        fmt.Sprintf("owner-%d", id),
		Currency:     currencies[id%int64(len(currencies))],
		BalanceMinor: id * 7919 % 10000000,
	}
}

func LatestTransactions(accountID int64, perAccount, limit int) []Txn {
	txns := make([]Txn, 0, min(perAccount, limit))
	for n := int64(perAccount); n >= 1 && len(txns) < limit; n-- {
		txns = append(txns, Txn{
			ID:          (accountID-1)*int64(perAccount) + n,
			BookedAt:    epoch.Add(time.Duration(n)*time.Hour + time.Duration(accountID%3600)*time.Second).Format(time.RFC3339),
			AmountMinor: (accountID*31+n*7919)%20000 - 15000,
			Description: descriptions[n%int64(len(descriptions))],
		})
	}
	return txns
}

func StatementFor(id int64, perAccount, limit int, rate *big.Rat) Statement {
	account := AccountFor(id)
	return Statement{
		ID:              account.ID,
		Owner:           account.Owner,
		Currency:        account.Currency,
		BalanceMinor:    account.BalanceMinor,
		BalanceUsdMinor: RoundHalfEven(new(big.Rat).Mul(new(big.Rat).SetInt64(account.BalanceMinor), rate)),
		Transactions:    LatestTransactions(id, perAccount, limit),
	}
}

func RoundHalfEven(r *big.Rat) int64 {
	quotient, remainder := new(big.Int).QuoRem(r.Num(), r.Denom(), new(big.Int))
	twice := new(big.Int).Lsh(new(big.Int).Abs(remainder), 1)
	cmp := twice.Cmp(r.Denom())
	if cmp > 0 || cmp == 0 && quotient.Bit(0) == 1 {
		quotient.Add(quotient, big.NewInt(int64(r.Sign())))
	}
	return quotient.Int64()
}
