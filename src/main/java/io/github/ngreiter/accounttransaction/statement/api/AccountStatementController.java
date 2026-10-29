package io.github.ngreiter.accounttransaction.statement.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.ngreiter.accounttransaction.domain.AccountNumber;
import io.github.ngreiter.accounttransaction.statement.application.AccountStatementService;

@RestController
@RequestMapping("/api/v1/accounts/{accountNumber}/statement")
public class AccountStatementController {

    private final AccountStatementService accountStatementService;

    public AccountStatementController(AccountStatementService accountStatementService) {
        this.accountStatementService = accountStatementService;
    }

    @GetMapping(produces = "application/json")
    public StatementResponse getStatement(@PathVariable String accountNumber,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "page", required = false) String page,
            @RequestParam(name = "size", required = false) String size) {
        if (!AccountNumber.isValid(accountNumber)) {
            throw new IllegalArgumentException("Número de conta inválido.");
        }
        return accountStatementService.getStatement(accountNumber, from, to, page, size);
    }
}
