package dev.jaderss.lvr.webflux;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
class AccountController {

    private static final int MAX_LIMIT = 100;

    private final AccountRepository repository;
    private final StatementService statementService;

    AccountController(AccountRepository repository, StatementService statementService) {
        this.repository = repository;
        this.statementService = statementService;
    }

    @GetMapping("/accounts/{id}")
    Mono<Account> account(@PathVariable long id) {
        return repository.findAccount(id).switchIfEmpty(Mono.error(AccountNotFoundException::new));
    }

    @GetMapping("/accounts/{id}/statement")
    Mono<Statement> statement(@PathVariable long id, @RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            return Mono.error(InvalidLimitException::new);
        }
        return statementService.statement(id, limit);
    }
}
