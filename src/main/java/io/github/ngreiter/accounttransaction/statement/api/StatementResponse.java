package io.github.ngreiter.accounttransaction.statement.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

public record StatementResponse(String accountNumber, LocalDate from, LocalDate to,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal openingBalance,
        List<StatementEntryResponse> entries,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal closingBalance,
        int page, int size, int numberOfElements, long totalEntries) {
}
