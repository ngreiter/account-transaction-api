package io.github.ngreiter.accounttransaction.transaction.api;

import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.ngreiter.accounttransaction.transaction.application.DepositResult;
import io.github.ngreiter.accounttransaction.transaction.application.DepositService;
import io.github.ngreiter.accounttransaction.transaction.application.DepositCommand;
import io.github.ngreiter.accounttransaction.transaction.application.TransferResult;
import io.github.ngreiter.accounttransaction.transaction.application.TransferService;
import io.github.ngreiter.accounttransaction.transaction.application.TransferCommand;
import io.github.ngreiter.accounttransaction.transaction.application.WithdrawalResult;
import io.github.ngreiter.accounttransaction.transaction.application.WithdrawalService;
import io.github.ngreiter.accounttransaction.transaction.application.WithdrawalCommand;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/transactions")
@Validated
public class TransactionController {

    private final DepositService depositService;
    private final WithdrawalService withdrawalService;
    private final TransferService transferService;

    public TransactionController(DepositService depositService, WithdrawalService withdrawalService,
            TransferService transferService) {
        this.depositService = depositService;
        this.withdrawalService = withdrawalService;
        this.transferService = transferService;
    }

    @PostMapping(path = "/deposits", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> deposit(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody DepositRequest request) {
        DepositCommand command = new DepositCommand(request.accountNumber(), request.amount(), request.description());
        DepositResult result = depositService.deposit(idempotencyKey, command);
        int status = result.replayedStatus() == null ? HttpStatus.CREATED.value() : result.replayedStatus();
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(result.responseBody());
    }

    @PostMapping(path = "/withdrawals", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> withdraw(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody WithdrawalRequest request) {
        WithdrawalCommand command = new WithdrawalCommand(request.accountNumber(), request.amount(), request.description());
        WithdrawalResult result = withdrawalService.withdraw(idempotencyKey, command);
        int status = result.replayedStatus() == null ? HttpStatus.CREATED.value() : result.replayedStatus();
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(result.responseBody());
    }

    @PostMapping(path = "/transfers", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> transfer(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        TransferCommand command = new TransferCommand(request.sourceAccountNumber(), request.destinationAccountNumber(),
                request.amount(), request.description());
        TransferResult result = transferService.transfer(idempotencyKey, command);
        int status = result.replayedStatus() == null ? HttpStatus.CREATED.value() : result.replayedStatus();
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(result.responseBody());
    }
}
