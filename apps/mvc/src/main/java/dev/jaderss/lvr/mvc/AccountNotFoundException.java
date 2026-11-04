package dev.jaderss.lvr.mvc;

class AccountNotFoundException extends RuntimeException {

    AccountNotFoundException() {
        super("account not found", null, false, false);
    }
}
