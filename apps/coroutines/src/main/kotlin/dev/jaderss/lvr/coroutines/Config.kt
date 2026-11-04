package dev.jaderss.lvr.coroutines

import io.r2dbc.postgresql.PostgresqlConnectionFactoryProvider
import io.r2dbc.spi.ConnectionFactoryOptions
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.r2dbc.autoconfigure.ConnectionFactoryOptionsBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@ConfigurationProperties("bench")
data class BenchProperties(
    val ratesUrl: String,
    val downstreamTimeout: Duration,
    val dbQueryTimeout: Duration,
    val dbSocketTimeout: Duration,
)

@Configuration(proxyBeanMethods = false)
class DatabaseConfig {
    @Bean
    fun timeouts(properties: BenchProperties) =
        ConnectionFactoryOptionsBuilderCustomizer { options ->
            options
                .option(PostgresqlConnectionFactoryProvider.STATEMENT_TIMEOUT, properties.dbQueryTimeout)
                .option(ConnectionFactoryOptions.CONNECT_TIMEOUT, properties.dbSocketTimeout)
        }
}
