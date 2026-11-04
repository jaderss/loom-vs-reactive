package dev.jaderss.lvr.mvc;

import java.time.Duration;
import org.springframework.boot.jdbc.autoconfigure.JdbcProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class QueryTimeoutCheck {

    QueryTimeoutCheck(JdbcProperties jdbc) {
        requireWholeSeconds(jdbc.getTemplate().getQueryTimeout());
    }

    static void requireWholeSeconds(Duration queryTimeout) {
        if (queryTimeout == null || queryTimeout.isZero() || queryTimeout.toMillis() % 1000 != 0) {
            throw new IllegalStateException("DB_QUERY_TIMEOUT_MS must be a whole number of seconds,"
                    + " JdbcTemplate keeps only the seconds and 0 means no timeout: " + queryTimeout);
        }
    }
}
