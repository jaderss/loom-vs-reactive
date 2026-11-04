package dev.jaderss.lvr.coroutines

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.springframework.stereotype.Service

@Service
class StatementService(
    private val repository: AccountRepository,
    private val ratesClient: RatesClient,
) {
    suspend fun statement(
        accountId: Long,
        limit: Int,
    ): Statement =
        coroutineScope {
            val account = async { repository.findAccount(accountId) ?: throw AccountNotFoundException() }
            val transactions = async { repository.findTransactions(accountId, limit) }
            val rates = async { ratesClient.usdRates() }
            val currency = account.await().currency
            val usdPerUnit =
                rates.await().rates[currency] ?: throw DownstreamUnavailableException("no rate for $currency")
            Statement.of(account.await(), transactions.await(), usdPerUnit)
        }
}
