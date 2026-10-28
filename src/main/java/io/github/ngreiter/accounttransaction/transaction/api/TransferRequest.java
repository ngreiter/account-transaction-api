package io.github.ngreiter.accounttransaction.transaction.api;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record TransferRequest(
        @NotBlank @Size(max = 10) @Pattern(regexp = "[0-9]{8}-[0-9]") String sourceAccountNumber,
        @NotBlank @Size(max = 10) @Pattern(regexp = "[0-9]{8}-[0-9]") String destinationAccountNumber,
        @NotNull @DecimalMin(value = "0.00", inclusive = false) @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @Size(max = 500) String description) {
}
