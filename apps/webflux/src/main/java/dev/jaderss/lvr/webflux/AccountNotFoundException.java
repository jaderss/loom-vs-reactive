package dev.jaderss.lvr.webflux;

class AccountNotFoundException extends RuntimeException {

    AccountNotFoundException() {
        super("account not found", null, false, false);
    }
}
