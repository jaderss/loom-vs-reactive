package dev.jaderss.lvr.webflux;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebInputException;

@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(AccountNotFoundException.class)
    ResponseEntity<String> notFound() {
        return text(HttpStatus.NOT_FOUND, "account not found");
    }

    @ExceptionHandler({InvalidLimitException.class, ServerWebInputException.class})
    ResponseEntity<String> badRequest() {
        return text(HttpStatus.BAD_REQUEST, "bad request");
    }

    @ExceptionHandler(DownstreamUnavailableException.class)
    ResponseEntity<String> downstreamUnavailable(DownstreamUnavailableException e) {
        log.warn("downstream unavailable: {}", e.getMessage());
        return text(HttpStatus.BAD_GATEWAY, "downstream unavailable");
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<String> databaseUnavailable(DataAccessException e) {
        log.warn("database unavailable: {}", e.getMessage());
        return text(HttpStatus.SERVICE_UNAVAILABLE, "database unavailable");
    }

    private static ResponseEntity<String> text(HttpStatus status, String body) {
        return ResponseEntity.status(status).contentType(MediaType.TEXT_PLAIN).body(body);
    }
}
