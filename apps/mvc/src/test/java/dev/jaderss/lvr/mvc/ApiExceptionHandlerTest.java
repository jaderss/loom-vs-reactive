package dev.jaderss.lvr.mvc;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ApiExceptionHandlerTest {

    private final AccountRepository repository = mock(AccountRepository.class);
    private final StatementService statementService = mock(StatementService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AccountController(repository, statementService))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    @Test
    @DisplayName("a data access failure is a 503 with a plain text body")
    void databaseUnavailable() throws Exception {
        when(repository.findAccount(1)).thenThrow(new CannotGetJdbcConnectionException("pool exhausted"));

        mvc.perform(get("/accounts/1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("database unavailable"));
    }

    @Test
    @DisplayName("a data access failure inside the fan-out is a 503 too")
    void databaseUnavailableInStatement() throws Exception {
        when(statementService.statement(1, 20)).thenThrow(new CannotGetJdbcConnectionException("pool exhausted"));

        mvc.perform(get("/accounts/1/statement"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string("database unavailable"));
    }
}
