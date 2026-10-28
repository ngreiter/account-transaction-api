package io.github.ngreiter.accounttransaction.account.api;

import java.math.BigDecimal;
import java.time.Instant;

public record CreateAccountResponse(String accountNumber, BigDecimal balance, Instant createdAt) {
}
