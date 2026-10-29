package io.github.ngreiter.accounttransaction.transaction.application;

public record WithdrawalResult(TransactionError error, String responseBody, Integer replayedStatus) {

    public static WithdrawalResult succeeded(String responseBody) {
        return new WithdrawalResult(null, responseBody, null);
    }

    public static WithdrawalResult replayed(int status, String responseBody) {
        return new WithdrawalResult(null, responseBody, status);
    }

    public static WithdrawalResult rejected(TransactionError error, String responseBody) {
        return new WithdrawalResult(error, responseBody, null);
    }
}
