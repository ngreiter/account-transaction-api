package io.github.ngreiter.accounttransaction.transaction.api;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.ngreiter.accounttransaction.transaction.application.WithdrawalResult;
import io.github.ngreiter.accounttransaction.transaction.application.WithdrawalService;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/transactions/withdrawals")
@Validated
public class WithdrawalController {

    private final WithdrawalService withdrawalService;

    public WithdrawalController(WithdrawalService withdrawalService) {
        this.withdrawalService = withdrawalService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> withdraw(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody WithdrawalRequest request) {
        WithdrawalResult result = withdrawalService.withdraw(idempotencyKey, request);
        return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).body(result.responseBody());
    }
}
