package dev.jaderss.lvr.webflux;

class DatabaseUnavailableException extends RuntimeException {

    DatabaseUnavailableException(Throwable cause) {
        super(cause.toString(), cause);
    }
}
