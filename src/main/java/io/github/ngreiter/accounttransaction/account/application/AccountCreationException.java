package io.github.ngreiter.accounttransaction.account.application;

public class AccountCreationException extends RuntimeException {

    private final AccountCreationError error;
    private final String responseBody;

    public AccountCreationException(AccountCreationError error, String responseBody) {
        super(error.message());
        this.error = error;
        this.responseBody = responseBody;
    }

    public AccountCreationError error() {
        return error;
    }

    public String responseBody() {
        return responseBody;
    }
}
