package io.github.ngreiter.accounttransaction.statement.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonFormat;

import io.github.ngreiter.accounttransaction.domain.MovementType;
import io.github.ngreiter.accounttransaction.domain.TransactionType;

public record StatementEntryResponse(UUID financialTransactionId, TransactionType transactionType,
        MovementType movementType, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String description, Instant postedAt) {
}
