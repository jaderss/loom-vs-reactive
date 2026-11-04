package dev.jaderss.lvr.mvc;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountControllerIT {

    private static final String RATES = "{\"to\":\"USD\",\"rates\":{\"EUR\":\"1.0900\",\"GBP\":\"1.0900\","
            + "\"CHF\":\"1.0900\",\"CAD\":\"1.0900\",\"AUD\":\"1.0900\"}}";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6")
            .withEnv("SEED_ACCOUNTS", "200")
            .withEnv("SEED_TXN_PER_ACCOUNT", "50")
            .withCopyFileToContainer(MountableFile.forHostPath("../../db/init/"), "/docker-entrypoint-initdb.d/");

    private static final RatesStub RATES_STUB = new RatesStub();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("bench.rates-url", RATES_STUB::url);
        registry.add("bench.downstream-timeout", () -> "500ms");
    }

    @AfterEach
    void resetStub() {
        RATES_STUB.answer(200, RATES, Duration.ZERO);
    }

    @AfterAll
    static void stopStub() {
        RATES_STUB.server.stop(0);
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("an account answers with the seeded row")
    void account() throws Exception {
        var response = get("/accounts/42");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .isEqualTo("{\"id\":42,\"owner\":\"owner-42\",\"currency\":\"CHF\",\"balanceMinor\":332598}");
    }

    @Test
    @DisplayName("a statement joins the account, its latest transactions and the converted balance")
    void statement() throws Exception {
        var response = get("/accounts/2/statement?limit=2");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .isEqualTo(
                        "{\"id\":2,\"owner\":\"owner-2\",\"currency\":\"CHF\",\"balanceMinor\":15838,"
                                + "\"balanceUsdMinor\":17263,\"transactions\":["
                                + "{\"id\":100,\"bookedAt\":\"2026-01-03T02:00:02Z\",\"amountMinor\":1012,\"description\":\"fee\"},"
                                + "{\"id\":99,\"bookedAt\":\"2026-01-03T01:00:02Z\",\"amountMinor\":-6907,\"description\":\"transfer\"}]}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/accounts/201", "/accounts/201/statement"})
    @DisplayName("an unknown account is a 404")
    void unknownAccount(String path) throws Exception {
        var response = get(path);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).isEqualTo("account not found");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/accounts/abc",
                "/accounts/1/statement?limit=0",
                "/accounts/1/statement?limit=101",
                "/accounts/1/statement?limit=x"
            })
    @DisplayName("a malformed id or a limit outside 1 to 100 is a 400")
    void badRequest(String path) throws Exception {
        var response = get(path);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).isEqualTo("bad request");
    }

    @Test
    @DisplayName("a failing rates call is a 502")
    void ratesFailure() throws Exception {
        RATES_STUB.answer(503, "down", Duration.ZERO);

        assertDownstreamUnavailable(get("/accounts/1/statement"));
    }

    @Test
    @DisplayName("a rates response without the account currency is a 502")
    void ratesWithoutCurrency() throws Exception {
        RATES_STUB.answer(200, "{\"to\":\"USD\",\"rates\":{}}", Duration.ZERO);

        assertDownstreamUnavailable(get("/accounts/1/statement"));
    }

    @Test
    @DisplayName("a rates response without the rates field is a 502")
    void ratesWithoutField() throws Exception {
        RATES_STUB.answer(200, "{\"to\":\"USD\"}", Duration.ZERO);

        assertDownstreamUnavailable(get("/accounts/1/statement"));
    }

    @Test
    @DisplayName("a rates call slower than the downstream timeout is a 502 at the timeout")
    void ratesTimeout() throws Exception {
        RATES_STUB.answer(200, RATES, Duration.ofSeconds(3));
        long start = System.nanoTime();

        var response = get("/accounts/1/statement");

        assertDownstreamUnavailable(response);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1500));
    }

    @Test
    @DisplayName("health answers without touching the database and stats describe the app")
    void healthAndStats() throws Exception {
        assertThat(get("/health").body()).isEqualTo("ok");
        assertThat(get("/stats").body())
                .contains("\"app\":\"mvc-vt\"", "\"mode\":\"virtual\"", "\"pool_size\":20", "\"lang\":\"java\"");
    }

    private static void assertDownstreamUnavailable(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.body()).isEqualTo("downstream unavailable");
    }

    private static final class RatesStub {

        private final HttpServer server;
        private volatile int status = 200;
        private volatile String body = RATES;
        private volatile Duration delay = Duration.ZERO;

        private RatesStub() {
            try {
                server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            server.createContext("/rates", exchange -> {
                try {
                    Thread.sleep(delay);
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
        }

        String url() {
            return "http://localhost:" + server.getAddress().getPort();
        }

        void answer(int status, String body, Duration delay) {
            this.status = status;
            this.body = body;
            this.delay = delay;
        }
    }
}
