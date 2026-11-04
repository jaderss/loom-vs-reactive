package dev.jaderss.lvr.webflux;

class InvalidLimitException extends RuntimeException {

    InvalidLimitException() {
        super("limit out of range", null, false, false);
    }
}
