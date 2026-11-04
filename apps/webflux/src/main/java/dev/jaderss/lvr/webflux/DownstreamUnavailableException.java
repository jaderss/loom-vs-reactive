package dev.jaderss.lvr.webflux;

class DownstreamUnavailableException extends RuntimeException {

    DownstreamUnavailableException(String message) {
        super(message);
    }

    DownstreamUnavailableException(Throwable cause) {
        super(cause.toString(), cause);
    }
}
