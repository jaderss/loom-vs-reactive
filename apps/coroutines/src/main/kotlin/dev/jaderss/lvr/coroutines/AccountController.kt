package dev.jaderss.lvr.coroutines

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

private const val MAX_LIMIT = 100

@RestController
class AccountController(
    private val repository: AccountRepository,
    private val statementService: StatementService,
) {
    @GetMapping("/accounts/{id}")
    suspend fun account(
        @PathVariable id: Long,
    ): Account = repository.findAccount(id) ?: throw AccountNotFoundException()

    @GetMapping("/accounts/{id}/statement")
    suspend fun statement(
        @PathVariable id: Long,
        @RequestParam(defaultValue = "20") limit: Int,
    ): Statement {
        if (limit !in 1..MAX_LIMIT) throw InvalidLimitException()
        return statementService.statement(id, limit)
    }
}
