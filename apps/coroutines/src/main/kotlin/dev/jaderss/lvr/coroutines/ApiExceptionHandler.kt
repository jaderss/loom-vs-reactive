package dev.jaderss.lvr.coroutines

import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ServerWebInputException

@RestControllerAdvice
class ApiExceptionHandler {
    private val log = LoggerFactory.getLogger(ApiExceptionHandler::class.java)

    @ExceptionHandler(AccountNotFoundException::class)
    fun notFound() = text(HttpStatus.NOT_FOUND, "account not found")

    @ExceptionHandler(InvalidLimitException::class, ServerWebInputException::class)
    fun badRequest() = text(HttpStatus.BAD_REQUEST, "bad request")

    @ExceptionHandler(DownstreamUnavailableException::class)
    fun downstreamUnavailable(e: DownstreamUnavailableException): ResponseEntity<String> {
        log.warn("downstream unavailable: {}", e.message)
        return text(HttpStatus.BAD_GATEWAY, "downstream unavailable")
    }

    @ExceptionHandler(DataAccessException::class)
    fun databaseUnavailable(e: DataAccessException): ResponseEntity<String> {
        log.warn("database unavailable: {}", e.message)
        return text(HttpStatus.SERVICE_UNAVAILABLE, "database unavailable")
    }

    private fun text(
        status: HttpStatus,
        body: String,
    ) = ResponseEntity.status(status).contentType(MediaType.TEXT_PLAIN).body(body)
}
