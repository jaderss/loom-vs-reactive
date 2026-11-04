package dev.jaderss.lvr.mvc;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class QueryTimeoutCheckTest {

    @ParameterizedTest(name = "{0} ms")
    @ValueSource(longs = {0, 500, 2500})
    @DisplayName("a query timeout JdbcTemplate would truncate stops the app at startup")
    void rejectsPartialSeconds(long millis) {
        assertThatThrownBy(() -> QueryTimeoutCheck.requireWholeSeconds(Duration.ofMillis(millis)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("whole number of seconds");
    }

    @Test
    @DisplayName("a missing query timeout stops the app at startup")
    void rejectsMissing() {
        assertThatThrownBy(() -> QueryTimeoutCheck.requireWholeSeconds(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("whole seconds pass")
    void acceptsWholeSeconds() {
        assertThatCode(() -> QueryTimeoutCheck.requireWholeSeconds(Duration.ofMillis(2000)))
                .doesNotThrowAnyException();
    }
}
