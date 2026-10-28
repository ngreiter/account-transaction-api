package io.github.ngreiter.accounttransaction.transaction.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonFormat;

public record WithdrawalResponse(UUID financialTransactionId, String type,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount, String status, Instant postedAt) {
}
