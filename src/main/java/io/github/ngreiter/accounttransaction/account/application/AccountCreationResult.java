package io.github.ngreiter.accounttransaction.account.application;

public record AccountCreationResult(AccountCreationError error, String responseBody, Integer replayedStatus) {

    public static AccountCreationResult succeeded(String responseBody) {
        return new AccountCreationResult(null, responseBody, null);
    }

    public static AccountCreationResult replayed(int status, String responseBody) {
        return new AccountCreationResult(null, responseBody, status);
    }

    public static AccountCreationResult rejected(AccountCreationError error, String responseBody) {
        return new AccountCreationResult(error, responseBody, null);
    }
}
