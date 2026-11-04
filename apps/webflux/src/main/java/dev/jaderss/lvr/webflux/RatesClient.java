package dev.jaderss.lvr.webflux;

import dev.jaderss.lvr.webflux.model.Rates;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

@Component
class RatesClient {

    private static final int MAX_CONNECTIONS = 10_000;

    private final WebClient client;
    private final Duration timeout;

    RatesClient(WebClient.Builder builder, BenchProperties properties) {
        this.timeout = properties.downstreamTimeout();
        var connections = ConnectionProvider.builder("rates")
                .maxConnections(MAX_CONNECTIONS)
                .pendingAcquireMaxCount(-1)
                .build();
        var http =
                HttpClient.create(connections).option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) timeout.toMillis());
        this.client = builder.baseUrl(properties.ratesUrl())
                .clientConnector(new ReactorClientHttpConnector(http))
                .build();
    }

    Mono<Rates> usdRates() {
        return client.get()
                .uri("/rates")
                .retrieve()
                .bodyToMono(Rates.class)
                .switchIfEmpty(Mono.error(() -> new IllegalStateException("empty rates body")))
                .timeout(timeout)
                .onErrorMap(DownstreamUnavailableException::new);
    }
}
