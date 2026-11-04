package dev.jaderss.lvr.webflux;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class ApiExceptionHandlerTest {

    private final AccountRepository repository = mock(AccountRepository.class);
    private final StatementService statementService = mock(StatementService.class);
    private final WebTestClient client = WebTestClient.bindToController(
                    new AccountController(repository, statementService))
            .controllerAdvice(new ApiExceptionHandler())
            .build();

    @Test
    @DisplayName("a database failure is a 503 with a plain text body")
    void databaseUnavailable() {
        when(repository.findAccount(1))
                .thenReturn(Mono.error(new DataAccessResourceFailureException("pool exhausted")));

        client.get()
                .uri("/accounts/1")
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.TEXT_PLAIN)
                .expectBody(String.class)
                .isEqualTo("database unavailable");
    }

    @Test
    @DisplayName("a database failure inside the fan-out is a 503 too")
    void databaseUnavailableInStatement() {
        when(statementService.statement(1, 20)).thenReturn(Mono.error(new QueryTimeoutException("timeout")));

        client.get()
                .uri("/accounts/1/statement")
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectBody(String.class)
                .isEqualTo("database unavailable");
    }
}
