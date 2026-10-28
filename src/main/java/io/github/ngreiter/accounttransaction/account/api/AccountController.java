package io.github.ngreiter.accounttransaction.account.api;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.ngreiter.accounttransaction.account.application.AccountCreationResult;
import io.github.ngreiter.accounttransaction.account.application.AccountCreationService;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/accounts")
@Validated
public class AccountController {

    private final AccountCreationService accountCreationService;

    public AccountController(AccountCreationService accountCreationService) {
        this.accountCreationService = accountCreationService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> create(@RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody CreateAccountRequest request) {
        AccountCreationResult result = accountCreationService.create(key, request.document());
        return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).body(result.responseBody());
    }
}
