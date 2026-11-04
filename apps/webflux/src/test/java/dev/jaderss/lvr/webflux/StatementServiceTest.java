package dev.jaderss.lvr.webflux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.jaderss.lvr.webflux.model.Account;
import dev.jaderss.lvr.webflux.model.Rates;
import dev.jaderss.lvr.webflux.model.Txn;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class StatementServiceTest {

    private static final Rates EUR_RATES = new Rates(Map.of("EUR", new BigDecimal("1.0900")));
    private static final Txn TXN = new Txn(1, Instant.parse("2026-01-01T01:00:00Z"), -4599, "card");

    private final AccountRepository repository = mock(AccountRepository.class);
    private final RatesClient ratesClient = mock(RatesClient.class);
    private final StatementService service = new StatementService(repository, ratesClient);

    private void accountWithBalance(long balanceMinor) {
        when(repository.findAccount(1)).thenReturn(Mono.just(new Account(1, "owner-1", "EUR", balanceMinor)));
    }

    @ParameterizedTest(name = "{0} minor units at 1.09 is {1}")
    @CsvSource({"150, 164", "50, 54", "100, 109", "0, 0"})
    @DisplayName("converts the balance to USD rounding half to even")
    void convertsHalfEven(long balanceMinor, long usdMinor) {
        accountWithBalance(balanceMinor);
        when(repository.findTransactions(1, 20)).thenReturn(Mono.just(List.of(TXN)));
        when(ratesClient.usdRates()).thenReturn(Mono.just(EUR_RATES));

        StepVerifier.create(service.statement(1, 20))
                .assertNext(statement -> {
                    assertThat(statement.balanceUsdMinor()).isEqualTo(usdMinor);
                    assertThat(statement.transactions()).containsExactly(TXN);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("the first failure ends the request and cancels the calls still running")
    void firstFailureCancelsTheOthers() {
        var cancelled = new AtomicBoolean();
        accountWithBalance(150);
        when(repository.findTransactions(1, 20))
                .thenReturn(Mono.<List<Txn>>never().doOnCancel(() -> cancelled.set(true)));
        when(ratesClient.usdRates()).thenReturn(Mono.error(new DownstreamUnavailableException("503")));

        StepVerifier.create(service.statement(1, 20))
                .expectError(DownstreamUnavailableException.class)
                .verify(Duration.ofSeconds(5));
        assertThat(cancelled).isTrue();
    }

    @Test
    @DisplayName("an unknown account is a not found, whatever the other calls return")
    void unknownAccount() {
        when(repository.findAccount(1)).thenReturn(Mono.empty());
        when(repository.findTransactions(1, 20)).thenReturn(Mono.just(List.of()));
        when(ratesClient.usdRates()).thenReturn(Mono.just(EUR_RATES));

        StepVerifier.create(service.statement(1, 20)).verifyError(AccountNotFoundException.class);
    }

    @Test
    @DisplayName("a database failure surfaces as database unavailable")
    void databaseFailure() {
        accountWithBalance(150);
        when(repository.findTransactions(1, 20))
                .thenReturn(Mono.error(new DatabaseUnavailableException(new IllegalStateException("timeout"))));
        when(ratesClient.usdRates()).thenReturn(Mono.just(EUR_RATES));

        StepVerifier.create(service.statement(1, 20)).verifyError(DatabaseUnavailableException.class);
    }

    @Test
    @DisplayName("a rate missing for the account currency is a downstream failure")
    void missingRate() {
        accountWithBalance(150);
        when(repository.findTransactions(1, 20)).thenReturn(Mono.just(List.of()));
        when(ratesClient.usdRates()).thenReturn(Mono.just(new Rates(Map.of("GBP", BigDecimal.ONE))));

        StepVerifier.create(service.statement(1, 20))
                .expectErrorSatisfies(e -> assertThat(e)
                        .isInstanceOf(DownstreamUnavailableException.class)
                        .hasMessage("no rate for EUR"))
                .verify();
    }
}
