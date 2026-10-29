package io.github.ngreiter.accounttransaction.domain;

public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException() {
        super("Saldo insuficiente.");
    }
}
