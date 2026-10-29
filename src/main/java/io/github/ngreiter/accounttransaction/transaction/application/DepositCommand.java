package io.github.ngreiter.accounttransaction.transaction.application;

import java.math.BigDecimal;

public record DepositCommand(String accountNumber, BigDecimal amount, String description) {
}
