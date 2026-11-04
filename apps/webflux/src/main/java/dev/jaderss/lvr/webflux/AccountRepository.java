package dev.jaderss.lvr.webflux;

import dev.jaderss.lvr.webflux.model.Account;
import dev.jaderss.lvr.webflux.model.Txn;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
class AccountRepository {

    private static final String ACCOUNT = "select id, owner, currency, balance_minor from account where id = $1";
    private static final String TRANSACTIONS =
            "select id, booked_at, amount_minor, description from txn where account_id = $1 order by booked_at desc limit $2";

    private final DatabaseClient db;
    private final Duration timeout;

    AccountRepository(DatabaseClient db, BenchProperties properties) {
        this.db = db;
        this.timeout = properties.dbAcquireTimeout().plus(properties.dbQueryTimeout());
    }

    Mono<Account> findAccount(long id) {
        return db.sql(ACCOUNT)
                .bind(0, id)
                .map(row -> new Account(
                        row.get(0, Long.class),
                        row.get(1, String.class),
                        row.get(2, String.class),
                        row.get(3, Long.class)))
                .one()
                .timeout(timeout)
                .onErrorMap(DatabaseUnavailableException::new);
    }

    Mono<List<Txn>> findTransactions(long accountId, int limit) {
        return db.sql(TRANSACTIONS)
                .bind(0, accountId)
                .bind(1, limit)
                .map(row -> new Txn(
                        row.get(0, Long.class),
                        row.get(1, OffsetDateTime.class).toInstant(),
                        row.get(2, Long.class),
                        row.get(3, String.class)))
                .all()
                .collectList()
                .timeout(timeout)
                .onErrorMap(DatabaseUnavailableException::new);
    }
}
