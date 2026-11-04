package dev.jaderss.lvr.coroutines

import io.netty.channel.ChannelOption
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider

private const val MAX_CONNECTIONS = 10_000

@Component
class RatesClient(
    builder: WebClient.Builder,
    properties: BenchProperties,
) {
    private val timeout = properties.downstreamTimeout
    private val client =
        builder
            .baseUrl(properties.ratesUrl)
            .clientConnector(
                ReactorClientHttpConnector(
                    HttpClient
                        .create(
                            ConnectionProvider
                                .builder("rates")
                                .maxConnections(MAX_CONNECTIONS)
                                .pendingAcquireMaxCount(-1)
                                .build(),
                        ).option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeout.toMillis().toInt()),
                ),
            ).build()

    suspend fun usdRates(): Rates =
        withDeadline(timeout, { DownstreamUnavailableException(it.toString(), it) }) {
            client
                .get()
                .uri("/rates")
                .retrieve()
                .awaitBody<Rates>()
        }
}
