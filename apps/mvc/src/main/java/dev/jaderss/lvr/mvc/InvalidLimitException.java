package dev.jaderss.lvr.mvc;

class InvalidLimitException extends RuntimeException {

    InvalidLimitException() {
        super("limit out of range", null, false, false);
    }
}
