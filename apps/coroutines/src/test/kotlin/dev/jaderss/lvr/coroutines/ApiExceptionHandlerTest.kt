package dev.jaderss.lvr.coroutines

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.MediaType
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.expectBody
import org.springframework.web.reactive.function.client.WebClient

class ApiExceptionHandlerTest {
    private val failing =
        object : AccountRepository(mock(DatabaseClient::class.java), R2DBC, PROPERTIES) {
            override suspend fun findAccount(id: Long): Account? =
                throw DataAccessResourceFailureException("pool exhausted")
        }
    private val client =
        WebTestClient
            .bindToController(
                AccountController(failing, StatementService(failing, RatesClient(WebClient.builder(), PROPERTIES))),
            ).controllerAdvice(ApiExceptionHandler())
            .build()

    @Test
    @DisplayName("a database failure is a 503 with a plain text body")
    fun databaseUnavailable() {
        client
            .get()
            .uri("/accounts/1")
            .exchange()
            .expectStatus()
            .isEqualTo(503)
            .expectHeader()
            .contentTypeCompatibleWith(MediaType.TEXT_PLAIN)
            .expectBody<String>()
            .isEqualTo("database unavailable")
    }
}
