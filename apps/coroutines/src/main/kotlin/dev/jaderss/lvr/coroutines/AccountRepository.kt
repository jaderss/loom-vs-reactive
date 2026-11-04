package dev.jaderss.lvr.coroutines

import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import org.springframework.boot.r2dbc.autoconfigure.R2dbcProperties
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

private const val ACCOUNT = "select id, owner, currency, balance_minor from account where id = $1"
private const val TRANSACTIONS =
    "select id, booked_at, amount_minor, description from txn where account_id = $1 order by booked_at desc limit $2"

@Repository
class AccountRepository(
    private val db: DatabaseClient,
    r2dbc: R2dbcProperties,
    properties: BenchProperties,
) {
    private val timeout = checkNotNull(r2dbc.pool.maxAcquireTime) + properties.dbQueryTimeout

    suspend fun findAccount(id: Long): Account? =
        withDeadline(timeout, ::databaseFailure) {
            db
                .sql(ACCOUNT)
                .bind(0, id)
                .map { row -> Account(row.column(0), row.column(1), row.column(2), row.column(3)) }
                .awaitOneOrNull()
        }

    suspend fun findTransactions(
        accountId: Long,
        limit: Int,
    ): List<Txn> =
        withDeadline(timeout, ::databaseFailure) {
            db
                .sql(TRANSACTIONS)
                .bind(0, accountId)
                .bind(1, limit)
                .map { row ->
                    Txn(row.column(0), row.column<OffsetDateTime>(1).toInstant(), row.column(2), row.column(3))
                }.flow()
                .toList()
        }
}

private fun databaseFailure(e: Throwable) = DataAccessResourceFailureException(e.toString(), e)

private inline fun <reified T : Any> Readable.column(index: Int): T =
    get(index, T::class.javaObjectType) ?: error("column $index is null")
