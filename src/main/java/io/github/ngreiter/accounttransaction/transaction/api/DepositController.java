package io.github.ngreiter.accounttransaction.transaction.api;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.ngreiter.accounttransaction.transaction.application.DepositResult;
import io.github.ngreiter.accounttransaction.transaction.application.DepositService;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/transactions/deposits")
@Validated
public class DepositController {

    private final DepositService depositService;

    public DepositController(DepositService depositService) {
        this.depositService = depositService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> deposit(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody DepositRequest request) {
        DepositResult result = depositService.deposit(idempotencyKey, request);
        return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).body(result.responseBody());
    }
}
