package io.github.ngreiter.accounttransaction.account.application;

public enum AccountCreationError {
    ACCOUNT_CONFLICT("ACCOUNT_CONFLICT", "N\u00e3o foi poss\u00edvel abrir a conta com os dados informados."),
    IDEMPOTENCY_CONFLICT("IDEMPOTENCY_CONFLICT",
            "A chave de idempot\u00eancia n\u00e3o pode ser reutilizada com dados diferentes.");

    private final String code;
    private final String message;

    AccountCreationError(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}
