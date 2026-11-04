package dev.jaderss.lvr.mvc;

import dev.jaderss.lvr.mvc.model.Account;
import dev.jaderss.lvr.mvc.model.Statement;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
    Account account(@PathVariable long id) {
        return repository.findAccount(id).orElseThrow(AccountNotFoundException::new);
    }

    @GetMapping("/accounts/{id}/statement")
    Statement statement(@PathVariable long id, @RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidLimitException();
        }
        return statementService.statement(id, limit);
    }
}
