package dev.jaderss.lvr.coroutines

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.MountableFile
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors

private const val RATES =
    """{"to":"USD","rates":{"EUR":"1.0900","GBP":"1.0900","CHF":"1.0900","CAD":"1.0900","AUD":"1.0900"}}"""

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountControllerIT {
    @LocalServerPort
    private var port = 0

    private fun get(path: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).build()
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString())
    }

    @AfterEach
    fun resetStub() = RatesStub.answer(200, RATES, Duration.ZERO)

    @Test
    @DisplayName("an account answers with the seeded row")
    fun account() {
        val response = get("/accounts/42")

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).isEqualTo("""{"id":42,"owner":"owner-42","currency":"CHF","balanceMinor":332598}""")
    }

    @Test
    @DisplayName("a statement joins the account, its latest transactions and the converted balance")
    fun statement() {
        val response = get("/accounts/2/statement?limit=2")

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).isEqualTo(
            """{"id":2,"owner":"owner-2","currency":"CHF","balanceMinor":15838,"balanceUsdMinor":17263,""" +
                """"transactions":[""" +
                """{"id":100,"bookedAt":"2026-01-03T02:00:02Z","amountMinor":1012,"description":"fee"},""" +
                """{"id":99,"bookedAt":"2026-01-03T01:00:02Z","amountMinor":-6907,"description":"transfer"}]}""",
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["/accounts/201", "/accounts/201/statement"])
    @DisplayName("an unknown account is a 404")
    fun unknownAccount(path: String) {
        val response = get(path)

        assertThat(response.statusCode()).isEqualTo(404)
        assertThat(response.body()).isEqualTo("account not found")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "/accounts/abc",
            "/accounts/1/statement?limit=0",
            "/accounts/1/statement?limit=101",
            "/accounts/1/statement?limit=x",
        ],
    )
    @DisplayName("a malformed id or a limit outside 1 to 100 is a 400")
    fun badRequest(path: String) {
        val response = get(path)

        assertThat(response.statusCode()).isEqualTo(400)
        assertThat(response.body()).isEqualTo("bad request")
    }

    @ParameterizedTest
    @ValueSource(strings = ["""{"to":"USD","rates":{}}""", """{"to":"USD"}"""])
    @DisplayName("a rates response without the account currency or the rates field is a 502")
    fun incompleteRates(body: String) {
        RatesStub.answer(200, body, Duration.ZERO)

        assertDownstreamUnavailable(get("/accounts/1/statement"))
    }

    @Test
    @DisplayName("a failing rates call is a 502")
    fun ratesFailure() {
        RatesStub.answer(503, "down", Duration.ZERO)

        assertDownstreamUnavailable(get("/accounts/1/statement"))
    }

    @Test
    @DisplayName("a rates call slower than the downstream timeout is a 502 at the timeout")
    fun ratesTimeout() {
        RatesStub.answer(200, RATES, Duration.ofSeconds(3))
        val start = System.nanoTime()

        assertDownstreamUnavailable(get("/accounts/1/statement"))
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1500))
    }

    @Test
    @DisplayName("health answers without touching the database and stats describe the app")
    fun healthAndStats() {
        assertThat(get("/health").body()).isEqualTo("ok")
        assertThat(get("/stats").body())
            .contains(""""app":"coroutines"""", """"mode":"coroutines"""", """"pool_size":20""", """"lang":"kotlin"""")
    }

    private fun assertDownstreamUnavailable(response: HttpResponse<String>) {
        assertThat(response.statusCode()).isEqualTo(502)
        assertThat(response.body()).isEqualTo("downstream unavailable")
    }

    companion object {
        private val HTTP = HttpClient.newHttpClient()

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer =
            PostgreSQLContainer("postgres:18.6")
                .withEnv("SEED_ACCOUNTS", "200")
                .withEnv("SEED_TXN_PER_ACCOUNT", "50")
                .withCopyFileToContainer(MountableFile.forHostPath("../../db/init/"), "/docker-entrypoint-initdb.d/")

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("bench.rates-url") { RatesStub.url }
            registry.add("bench.downstream-timeout") { "500ms" }
        }

        @AfterAll
        @JvmStatic
        fun stopStub() = RatesStub.server.stop(0)
    }

    private object RatesStub {
        val server: HttpServer = HttpServer.create(InetSocketAddress("localhost", 0), 0)

        @Volatile private var status = 200

        @Volatile private var body = RATES

        @Volatile private var delay = Duration.ZERO

        val url get() = "http://localhost:${server.address.port}"

        init {
            server.createContext("/rates") { exchange ->
                exchange.use {
                    Thread.sleep(delay)
                    val bytes = body.toByteArray()
                    it.responseHeaders.add("Content-Type", "application/json")
                    it.sendResponseHeaders(status, bytes.size.toLong())
                    it.responseBody.write(bytes)
                }
            }
            server.executor = Executors.newVirtualThreadPerTaskExecutor()
            server.start()
        }

        fun answer(
            status: Int,
            body: String,
            delay: Duration,
        ) {
            this.status = status
            this.body = body
            this.delay = delay
        }
    }
}
