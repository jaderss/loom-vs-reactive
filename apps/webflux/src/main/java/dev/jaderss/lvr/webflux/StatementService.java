package dev.jaderss.lvr.webflux;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
class StatementService {

    private final AccountRepository repository;
    private final RatesClient ratesClient;

    StatementService(AccountRepository repository, RatesClient ratesClient) {
        this.repository = repository;
        this.ratesClient = ratesClient;
    }

    Mono<Statement> statement(long accountId, int limit) {
        return Mono.zip(
                        repository.findAccount(accountId).switchIfEmpty(Mono.error(AccountNotFoundException::new)),
                        repository.findTransactions(accountId, limit),
                        ratesClient.usdRates())
                .map(joined -> {
                    var account = joined.getT1();
                    var usdPerUnit = joined.getT3()
                            .usdPer(account.currency())
                            .orElseThrow(() -> new DownstreamUnavailableException("no rate for " + account.currency()));
                    return Statement.of(account, joined.getT2(), usdPerUnit);
                });
    }
}
