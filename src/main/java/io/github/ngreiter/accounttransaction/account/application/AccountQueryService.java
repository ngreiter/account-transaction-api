package io.github.ngreiter.accounttransaction.account.application;

import org.springframework.stereotype.Service;

import io.github.ngreiter.accounttransaction.account.api.AccountDetailsResponse;
import io.github.ngreiter.accounttransaction.domain.Account;
import io.github.ngreiter.accounttransaction.persistence.AccountRepository;

@Service
public class AccountQueryService {

    private final AccountRepository accountRepository;

    public AccountQueryService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    public AccountDetailsResponse getByAccountNumber(String accountNumber) {
        validateAccountNumber(accountNumber);
        Account account = accountRepository.findById(accountNumber)
                .orElseThrow(AccountNotFoundException::new);
        return new AccountDetailsResponse(account.getAccountNumber(), maskDocument(account.getDocument()),
                account.getBalance());
    }

    private void validateAccountNumber(String accountNumber) {
        if (accountNumber == null || !accountNumber.matches("[0-9]{8}-[0-9]")) {
            throw new IllegalArgumentException("Número de conta inválido.");
        }

        String base = accountNumber.substring(0, 8);
        int sum = 0;
        int weight = 2;
        for (int i = base.length() - 1; i >= 0; i--) {
            sum += (base.charAt(i) - '0') * weight;
            weight = weight == 9 ? 2 : weight + 1;
        }
        int expectedDigit = 11 - sum % 11;
        if (expectedDigit >= 10) {
            expectedDigit = 0;
        }
        if (accountNumber.charAt(9) - '0' != expectedDigit) {
            throw new IllegalArgumentException("Número de conta inválido.");
        }
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
