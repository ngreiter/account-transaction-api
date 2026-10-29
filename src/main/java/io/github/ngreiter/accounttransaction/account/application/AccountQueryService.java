package io.github.ngreiter.accounttransaction.account.application;

import org.springframework.stereotype.Service;

import io.github.ngreiter.accounttransaction.account.api.AccountDetailsResponse;
import io.github.ngreiter.accounttransaction.domain.Account;
import io.github.ngreiter.accounttransaction.domain.AccountNumber;
import io.github.ngreiter.accounttransaction.persistence.AccountRepository;

@Service
public class AccountQueryService {

    private final AccountRepository accountRepository;

    public AccountQueryService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    public AccountDetailsResponse getByAccountNumber(String accountNumber) {
        if (!AccountNumber.isValid(accountNumber)) {
            throw new IllegalArgumentException("Número de conta inválido.");
        }
        Account account = accountRepository.findById(accountNumber)
                .orElseThrow(AccountNotFoundException::new);
        return new AccountDetailsResponse(account.getAccountNumber(), maskDocument(account.getDocument()),
                account.getBalance());
    }

    private String maskDocument(String document) {
        if (document.length() == 11) {
            return "***.***.***-" + document.substring(9);
        }
        if (document.length() == 14) {
            return "**.***.***/****-" + document.substring(12);
        }
        return "***";
    }
}
