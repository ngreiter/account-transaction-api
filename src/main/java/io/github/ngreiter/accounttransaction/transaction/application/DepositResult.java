package io.github.ngreiter.accounttransaction.transaction.application;

public record DepositResult(TransactionError error, String responseBody, Integer replayedStatus) {

    public static DepositResult succeeded(String responseBody) {
        return new DepositResult(null, responseBody, null);
    }

    public static DepositResult replayed(int status, String responseBody) {
        return new DepositResult(null, responseBody, status);
    }

    public static DepositResult rejected(TransactionError error, String responseBody) {
        return new DepositResult(error, responseBody, null);
    }
}
