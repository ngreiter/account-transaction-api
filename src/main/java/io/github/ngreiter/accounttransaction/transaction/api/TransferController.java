package io.github.ngreiter.accounttransaction.transaction.api;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.ngreiter.accounttransaction.transaction.application.TransferResult;
import io.github.ngreiter.accounttransaction.transaction.application.TransferService;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/transactions/transfers")
@Validated
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> transfer(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        TransferResult result = transferService.transfer(idempotencyKey, request);
        return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).body(result.responseBody());
    }
}
