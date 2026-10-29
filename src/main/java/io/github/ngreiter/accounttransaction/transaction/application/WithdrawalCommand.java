package io.github.ngreiter.accounttransaction.transaction.application;

import java.math.BigDecimal;

public record WithdrawalCommand(String accountNumber, BigDecimal amount, String description) {
}
