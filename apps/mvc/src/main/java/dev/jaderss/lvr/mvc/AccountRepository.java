package dev.jaderss.lvr.mvc;

import dev.jaderss.lvr.mvc.model.Account;
import dev.jaderss.lvr.mvc.model.Txn;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class AccountRepository {

    private static final String ACCOUNT = "select id, owner, currency, balance_minor from account where id = ?";
    private static final String TRANSACTIONS =
            "select id, booked_at, amount_minor, description from txn where account_id = ? order by booked_at desc limit ?";

    private final JdbcClient jdbc;

    AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Account> findAccount(long id) {
        return jdbc.sql(ACCOUNT)
                .param(id)
                .query((rs, row) -> new Account(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4)))
                .optional();
    }

    List<Txn> findTransactions(long accountId, int limit) {
        return jdbc.sql(TRANSACTIONS)
                .param(accountId)
                .param(limit)
                .query((rs, row) -> new Txn(
                        rs.getLong(1),
                        rs.getObject(2, OffsetDateTime.class).toInstant(),
                        rs.getLong(3),
                        rs.getString(4)))
                .list();
    }
}
