package io.github.ngreiter.accounttransaction.transaction.application;

public class TransactionApplicationException extends RuntimeException {

    private final TransactionError error;
    private final String responseBody;

    public TransactionApplicationException(TransactionError error, String responseBody) {
        super(error.message());
        this.error = error;
        this.responseBody = responseBody;
    }

    public TransactionError error() {
        return error;
    }

    public String responseBody() {
        return responseBody;
    }
}
