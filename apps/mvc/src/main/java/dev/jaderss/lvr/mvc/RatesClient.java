package dev.jaderss.lvr.mvc;

import java.net.http.HttpClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
class RatesClient {

    private final RestClient client;

    RatesClient(RestClient.Builder builder, BenchProperties properties) {
        var http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.downstreamTimeout())
                .build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.downstreamTimeout());
        this.client =
                builder.baseUrl(properties.ratesUrl()).requestFactory(factory).build();
    }

    Rates usdRates() {
        try {
            return client.get().uri("/rates").retrieve().requiredBody(Rates.class);
        } catch (RestClientException | IllegalStateException e) {
            throw new DownstreamUnavailableException(e);
        }
    }
}
