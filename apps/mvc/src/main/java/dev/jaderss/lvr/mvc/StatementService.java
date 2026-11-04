package dev.jaderss.lvr.mvc;

import java.util.concurrent.ExecutorService;
import org.springframework.stereotype.Service;

@Service
class StatementService {

    private final AccountRepository repository;
    private final RatesClient ratesClient;
    private final LegacyLock legacyLock;
    private final ExecutorService executor;

    StatementService(
            AccountRepository repository, RatesClient ratesClient, LegacyLock legacyLock, ExecutorService executor) {
        this.repository = repository;
        this.ratesClient = ratesClient;
        this.legacyLock = legacyLock;
        this.executor = executor;
    }

    Statement statement(long accountId, int limit) {
        var fanOut = new FanOut(executor);
        var account = fanOut.fork(() -> repository.findAccount(accountId).orElseThrow(AccountNotFoundException::new));
        var transactions = fanOut.fork(() -> repository.findTransactions(accountId, limit));
        var rates = fanOut.fork(() -> legacyLock.guard(accountId, ratesClient::usdRates));
        fanOut.join();
        var currency = account.resultNow().currency();
        var usdPerUnit = rates.resultNow()
                .usdPer(currency)
                .orElseThrow(() -> new DownstreamUnavailableException("no rate for " + currency));
        return Statement.of(account.resultNow(), transactions.resultNow(), usdPerUnit);
    }
}
