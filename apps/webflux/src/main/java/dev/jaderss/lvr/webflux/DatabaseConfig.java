package dev.jaderss.lvr.webflux;

import io.r2dbc.postgresql.PostgresqlConnectionFactoryProvider;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.springframework.boot.r2dbc.autoconfigure.ConnectionFactoryOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class DatabaseConfig {

    @Bean
    ConnectionFactoryOptionsBuilderCustomizer timeouts(BenchProperties properties) {
        return options -> options.option(
                        PostgresqlConnectionFactoryProvider.STATEMENT_TIMEOUT, properties.dbQueryTimeout())
                .option(ConnectionFactoryOptions.CONNECT_TIMEOUT, properties.dbSocketTimeout());
    }
}
