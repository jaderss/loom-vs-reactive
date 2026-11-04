package dev.jaderss.lvr.mvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.jaderss.lvr.mvc.model.Account;
import dev.jaderss.lvr.mvc.model.Rates;
import dev.jaderss.lvr.mvc.model.Txn;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.QueryTimeoutException;

class StatementServiceTest {

    private static final Rates EUR_RATES = new Rates(Map.of("EUR", new BigDecimal("1.0900")));
    private static final Txn TXN = new Txn(1, Instant.parse("2026-01-01T01:00:00Z"), -4599, "card");

    private final AccountRepository repository = mock(AccountRepository.class);
    private final RatesClient ratesClient = mock(RatesClient.class);
    private ExecutorService executor;

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    static Stream<ExecutorService> executors() {
        return Stream.of(Executors.newVirtualThreadPerTaskExecutor(), Executors.newFixedThreadPool(6));
    }

    private StatementService service(ExecutorService executor) {
        this.executor = executor;
        var properties = new BenchProperties("http://localhost", Duration.ofSeconds(1), 20, 6, false, 0);
        return new StatementService(repository, ratesClient, new LegacyLock(properties), executor);
    }

    private void accountWithBalance(long balanceMinor) {
        when(repository.findAccount(1)).thenReturn(Optional.of(new Account(1, "owner-1", "EUR", balanceMinor)));
    }

    @ParameterizedTest(name = "{0} minor units at 1.09 is {1}")
    @CsvSource({"150, 164", "50, 54", "100, 109", "0, 0"})
    @DisplayName("converts the balance to USD rounding half to even")
    void convertsHalfEven(long balanceMinor, long usdMinor) {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        accountWithBalance(balanceMinor);
        when(repository.findTransactions(1, 20)).thenReturn(List.of(TXN));
        when(ratesClient.usdRates()).thenReturn(EUR_RATES);

        var statement = service(executor).statement(1, 20);

        assertThat(statement.balanceUsdMinor()).isEqualTo(usdMinor);
        assertThat(statement.transactions()).containsExactly(TXN);
    }

    @ParameterizedTest
    @MethodSource("executors")
    @DisplayName("the first failure ends the request and interrupts the calls still running")
    void firstFailureCancelsTheOthers(ExecutorService executor) throws InterruptedException {
        var interrupted = new CountDownLatch(1);
        accountWithBalance(150);
        when(repository.findTransactions(1, 20)).thenAnswer(invocation -> {
            try {
                Thread.sleep(Duration.ofSeconds(10));
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
            return List.of();
        });
        when(ratesClient.usdRates()).thenThrow(new DownstreamUnavailableException("503"));
        var service = service(executor);
        long start = System.nanoTime();

        assertThatThrownBy(() -> service.statement(1, 20)).isInstanceOf(DownstreamUnavailableException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("an unknown account is a not found, whatever the other calls return")
    void unknownAccount() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        when(repository.findAccount(1)).thenReturn(Optional.empty());
        when(ratesClient.usdRates()).thenReturn(EUR_RATES);

        assertThatThrownBy(() -> service(executor).statement(1, 20)).isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    @DisplayName("a database failure surfaces as the data access exception")
    void databaseFailure() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        accountWithBalance(150);
        when(repository.findTransactions(1, 20)).thenThrow(new QueryTimeoutException("timeout"));
        when(ratesClient.usdRates()).thenReturn(EUR_RATES);

        assertThatThrownBy(() -> service(executor).statement(1, 20)).isInstanceOf(QueryTimeoutException.class);
    }

    @Test
    @DisplayName("a rate missing for the account currency is a downstream failure")
    void missingRate() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        accountWithBalance(150);
        when(ratesClient.usdRates()).thenReturn(new Rates(Map.of("GBP", BigDecimal.ONE)));

        assertThatThrownBy(() -> service(executor).statement(1, 20))
                .isInstanceOf(DownstreamUnavailableException.class)
                .hasMessage("no rate for EUR");
    }

    @Test
    @DisplayName("the legacy lock lets one call per stripe through at a time")
    void legacyLockSerializesAStripe() throws InterruptedException {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        var lock = new LegacyLock(new BenchProperties("http://localhost", Duration.ofSeconds(1), 20, 6, true, 4));
        var inside = new AtomicInteger();
        var mostInside = new AtomicInteger();
        for (int i = 0; i < 20; i++) {
            executor.execute(() -> lock.guard(8, () -> {
                mostInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
                sleep();
                return inside.decrementAndGet();
            }));
        }
        executor.shutdown();

        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(mostInside).hasValue(1);
    }

    private static void sleep() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
