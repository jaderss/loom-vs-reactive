package dev.jaderss.lvr.coroutines

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.Mockito.mock
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

internal val PROPERTIES =
    BenchProperties(
        ratesUrl = "http://localhost",
        downstreamTimeout = Duration.ofSeconds(1),
        poolSize = 20,
        dbAcquireTimeout = Duration.ofSeconds(1),
        dbQueryTimeout = Duration.ofSeconds(1),
        dbSocketTimeout = Duration.ofSeconds(1),
    )
private val EUR_RATES = Rates(mapOf("EUR" to BigDecimal("1.0900")))
private val TXN = Txn(1, Instant.parse("2026-01-01T01:00:00Z"), -4599, "card")

class StatementServiceTest {
    private var transactionsCancelled = false

    private fun service(
        account: suspend () -> Account? = { Account(1, "owner-1", "EUR", 150) },
        transactions: suspend () -> List<Txn> = { listOf(TXN) },
        rates: suspend () -> Rates = { EUR_RATES },
    ): StatementService {
        val repository =
            object : AccountRepository(mock(DatabaseClient::class.java), PROPERTIES) {
                override suspend fun findAccount(id: Long) = account()

                override suspend fun findTransactions(
                    accountId: Long,
                    limit: Int,
                ): List<Txn> =
                    try {
                        transactions()
                    } finally {
                        transactionsCancelled = true
                    }
            }
        val ratesClient =
            object : RatesClient(WebClient.builder(), PROPERTIES) {
                override suspend fun usdRates() = rates()
            }
        return StatementService(repository, ratesClient)
    }

    @ParameterizedTest(name = "{0} minor units at 1.09 is {1}")
    @CsvSource("150, 164", "50, 54", "100, 109", "0, 0")
    @DisplayName("converts the balance to USD rounding half to even")
    fun convertsHalfEven(
        balanceMinor: Long,
        usdMinor: Long,
    ) = runTest {
        val statement = service(account = { Account(1, "owner-1", "EUR", balanceMinor) }).statement(1, 20)

        assertThat(statement.balanceUsdMinor).isEqualTo(usdMinor)
        assertThat(statement.transactions).containsExactly(TXN)
    }

    @Test
    @DisplayName("the first failure ends the request and cancels the calls still running")
    fun firstFailureCancelsTheOthers() =
        runTest {
            val service =
                service(
                    transactions = { awaitCancellation() },
                    rates = { throw DownstreamUnavailableException("503") },
                )

            assertThrows<DownstreamUnavailableException> { service.statement(1, 20) }
            assertThat(transactionsCancelled).isTrue()
        }

    @Test
    @DisplayName("an unknown account is a not found, whatever the other calls return")
    fun unknownAccount() =
        runTest {
            assertThrows<AccountNotFoundException> { service(account = { null }).statement(1, 20) }
        }

    @Test
    @DisplayName("a database failure surfaces as database unavailable")
    fun databaseFailure() =
        runTest {
            val service =
                service(transactions = { throw DatabaseUnavailableException(IllegalStateException("timeout")) })

            assertThrows<DatabaseUnavailableException> { service.statement(1, 20) }
        }

    @Test
    @DisplayName("a rate missing for the account currency is a downstream failure")
    fun missingRate() =
        runTest {
            val service = service(rates = { Rates(mapOf("GBP" to BigDecimal.ONE)) })

            val failure = assertThrows<DownstreamUnavailableException> { service.statement(1, 20) }
            assertThat(failure).hasMessage("no rate for EUR")
        }

    @Test
    @DisplayName("a call past its deadline fails with the given exception, cancellation passes through")
    fun deadline() =
        runTest {
            assertThrows<DatabaseUnavailableException> {
                withDeadline(Duration.ofMillis(10), ::DatabaseUnavailableException) { awaitCancellation() }
            }
            assertThrows<DatabaseUnavailableException> {
                withDeadline(Duration.ofSeconds(1), ::DatabaseUnavailableException) { error("refused") }
            }
        }

    @Test
    @DisplayName("process stats parse the proc files")
    fun processStats() {
        assertThat(residentMegabytes("Name:\tjava\nVmRSS:\t   2048 kB\n")).isEqualTo(2.0)
        assertThat(cpuSeconds("1 (my app) S 0 1 1 0 -1 4194560 100 0 0 0 250 150 0 0 20 0 8 0")).isEqualTo(4.0)
    }
}
