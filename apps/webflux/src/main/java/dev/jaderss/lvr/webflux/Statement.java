package dev.jaderss.lvr.webflux;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

record Statement(
        long id, String owner, String currency, long balanceMinor, long balanceUsdMinor, List<Txn> transactions) {

    static Statement of(Account account, List<Txn> transactions, BigDecimal usdPerUnit) {
        long usdMinor = BigDecimal.valueOf(account.balanceMinor())
                .multiply(usdPerUnit)
                .setScale(0, RoundingMode.HALF_EVEN)
                .longValueExact();
        return new Statement(
                account.id(), account.owner(), account.currency(), account.balanceMinor(), usdMinor, transactions);
    }
}
