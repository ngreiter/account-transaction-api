package io.github.ngreiter.accounttransaction.account.api;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.ngreiter.accounttransaction.account.application.AccountCreationResult;
import io.github.ngreiter.accounttransaction.account.application.AccountCreationService;
import io.github.ngreiter.accounttransaction.account.application.AccountQueryService;
import io.github.ngreiter.accounttransaction.domain.AccountNumber;
import io.github.ngreiter.accounttransaction.statement.api.StatementResponse;
import io.github.ngreiter.accounttransaction.statement.application.AccountStatementService;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/accounts")
@Validated
public class AccountController {

    private final AccountCreationService accountCreationService;
    private final AccountQueryService accountQueryService;
    private final AccountStatementService accountStatementService;

    public AccountController(AccountCreationService accountCreationService, AccountQueryService accountQueryService,
            AccountStatementService accountStatementService) {
        this.accountCreationService = accountCreationService;
        this.accountQueryService = accountQueryService;
        this.accountStatementService = accountStatementService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> create(@RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody CreateAccountRequest request) {
        AccountCreationResult result = accountCreationService.create(key, request.document());
        return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).body(result.responseBody());
    }

    @GetMapping(path = "/{accountNumber}", produces = MediaType.APPLICATION_JSON_VALUE)
    public AccountDetailsResponse getByAccountNumber(@PathVariable String accountNumber) {
        return accountQueryService.getByAccountNumber(accountNumber);
    }

    @GetMapping(path = "/{accountNumber}/statement", produces = "application/json")
    public StatementResponse getStatement(@PathVariable String accountNumber,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "page", required = false) String page,
            @RequestParam(name = "size", required = false) String size) {
        if (!AccountNumber.isValid(accountNumber)) {
            throw new IllegalArgumentException("Núm\u00e9ro de conta inv\u00e1lido.");
        }
        return accountStatementService.getStatement(accountNumber, from, to, page, size);
    }
}
