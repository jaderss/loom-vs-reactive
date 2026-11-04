package dev.jaderss.lvr.coroutines

import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitSingleOrNull
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

private const val ACCOUNT = "select id, owner, currency, balance_minor from account where id = $1"
private const val TRANSACTIONS =
    "select id, booked_at, amount_minor, description from txn where account_id = $1 order by booked_at desc limit $2"

@Repository
class AccountRepository(
    private val db: DatabaseClient,
    properties: BenchProperties,
) {
    private val timeout = properties.dbAcquireTimeout + properties.dbQueryTimeout

    suspend fun findAccount(id: Long): Account? =
        withDeadline(timeout, ::DatabaseUnavailableException) {
            db
                .sql(ACCOUNT)
                .bind(0, id)
                .map { row ->
                    Account(
                        id = row.column(0),
                        owner = row.column(1),
                        currency = row.column(2),
                        balanceMinor = row.column(3),
                    )
                }.awaitSingleOrNull()
        }

    suspend fun findTransactions(
        accountId: Long,
        limit: Int,
    ): List<Txn> =
        withDeadline(timeout, ::DatabaseUnavailableException) {
            db
                .sql(TRANSACTIONS)
                .bind(0, accountId)
                .bind(1, limit)
                .map { row ->
                    Txn(
                        id = row.column(0),
                        bookedAt = row.column<OffsetDateTime>(1).toInstant(),
                        amountMinor = row.column(2),
                        description = row.column(3),
                    )
                }.flow()
                .toList()
        }
}

private inline fun <reified T : Any> Readable.column(index: Int): T =
    get(index, T::class.javaObjectType) ?: error("column $index is null")
