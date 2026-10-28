package io.github.ngreiter.accounttransaction.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import io.github.ngreiter.accounttransaction.domain.MovementType;
import io.github.ngreiter.accounttransaction.domain.TransactionType;

public interface AccountTransactionStatementRow {

    UUID getFinancialTransactionId();

    TransactionType getTransactionType();

    MovementType getMovementType();

    BigDecimal getAmount();

    String getDescription();

    Instant getPostedAt();
}
