package io.github.ngreiter.accounttransaction.transaction.application;

public record TransferResult(TransactionError error, String responseBody, Integer replayedStatus) {

    public static TransferResult succeeded(String responseBody) {
        return new TransferResult(null, responseBody, null);
    }

    public static TransferResult replayed(int status, String responseBody) {
        return new TransferResult(null, responseBody, status);
    }

    public static TransferResult rejected(TransactionError error, String responseBody) {
        return new TransferResult(error, responseBody, null);
    }
}
