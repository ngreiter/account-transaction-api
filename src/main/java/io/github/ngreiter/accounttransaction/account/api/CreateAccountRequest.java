package io.github.ngreiter.accounttransaction.account.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
        @NotBlank
        @Size(max = 18)
        String document) {
}
