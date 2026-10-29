package io.github.ngreiter.accounttransaction.transaction.application;

public enum TransactionError {
    RESOURCE_NOT_FOUND("RESOURCE_NOT_FOUND", "Recurso n\u00e3o encontrado."),
    IDEMPOTENCY_CONFLICT("IDEMPOTENCY_CONFLICT",
            "A chave de idempot\u00eancia n\u00e3o pode ser reutilizada com dados diferentes."),
    INSUFFICIENT_FUNDS("INSUFFICIENT_FUNDS", "Saldo insuficiente para concluir a opera\u00e7\u00e3o.");

    private final String code;
    private final String message;

    TransactionError(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }

    public static TransactionError fromStoredStatus(int status) {
        return switch (status) {
            case 404 -> RESOURCE_NOT_FOUND;
            case 422 -> INSUFFICIENT_FUNDS;
            default -> null;
        };
    }
}
