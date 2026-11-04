package dev.jaderss.lvr.mvc;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExecutorConfigTest {

    private static final BenchProperties PROPERTIES =
            new BenchProperties("http://localhost", Duration.ofSeconds(1), 20, 3, false, 0);

    @Test
    @DisplayName("mvc-platform fans out on named platform threads")
    void platformThreads() throws Exception {
        try (var executor = new ExecutorConfig().fanOutExecutor(false, PROPERTIES)) {
            assertThat(executor.submit(() -> Thread.currentThread().isVirtual()).get())
                    .isFalse();
            assertThat(executor.submit(() -> Thread.currentThread().getName()).get())
                    .startsWith("fanout-");
        }
    }

    @Test
    @DisplayName("mvc-vt fans out on virtual threads")
    void virtualThreads() throws Exception {
        try (var executor = new ExecutorConfig().fanOutExecutor(true, PROPERTIES)) {
            assertThat(executor.submit(() -> Thread.currentThread().isVirtual()).get())
                    .isTrue();
        }
    }
}
