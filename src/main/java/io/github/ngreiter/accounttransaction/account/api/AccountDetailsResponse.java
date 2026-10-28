package io.github.ngreiter.accounttransaction.account.api;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonFormat;

public record AccountDetailsResponse(String accountNumber, String documentMasked,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal balance) {
}
