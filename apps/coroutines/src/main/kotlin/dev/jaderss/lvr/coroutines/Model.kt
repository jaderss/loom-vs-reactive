package dev.jaderss.lvr.coroutines

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

data class Account(
    val id: Long,
    val owner: String,
    val currency: String,
    val balanceMinor: Long,
)

data class Txn(
    val id: Long,
    val bookedAt: Instant,
    val amountMinor: Long,
    val description: String,
)

data class Rates(
    val rates: Map<String, BigDecimal>,
)

data class Statement(
    val id: Long,
    val owner: String,
    val currency: String,
    val balanceMinor: Long,
    val balanceUsdMinor: Long,
    val transactions: List<Txn>,
) {
    companion object {
        fun of(
            account: Account,
            transactions: List<Txn>,
            usdPerUnit: BigDecimal,
        ) = Statement(
            id = account.id,
            owner = account.owner,
            currency = account.currency,
            balanceMinor = account.balanceMinor,
            balanceUsdMinor =
                BigDecimal
                    .valueOf(account.balanceMinor)
                    .multiply(usdPerUnit)
                    .setScale(0, RoundingMode.HALF_EVEN)
                    .longValueExact(),
            transactions = transactions,
        )
    }
}
