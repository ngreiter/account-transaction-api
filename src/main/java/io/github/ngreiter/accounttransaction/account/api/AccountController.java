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
import org.springframework.web.bind.annotation.RestController;

import io.github.ngreiter.accounttransaction.account.application.AccountCreationResult;
import io.github.ngreiter.accounttransaction.account.application.AccountCreationService;
import io.github.ngreiter.accounttransaction.account.application.AccountQueryService;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/accounts")
@Validated
public class AccountController {

    private final AccountCreationService accountCreationService;
    private final AccountQueryService accountQueryService;

    public AccountController(AccountCreationService accountCreationService, AccountQueryService accountQueryService) {
        this.accountCreationService = accountCreationService;
        this.accountQueryService = accountQueryService;
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
}
