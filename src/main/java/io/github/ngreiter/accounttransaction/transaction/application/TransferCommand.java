package io.github.ngreiter.accounttransaction.transaction.application;

import java.math.BigDecimal;

public record TransferCommand(String sourceAccountNumber, String destinationAccountNumber,
        BigDecimal amount, String description) {
}
