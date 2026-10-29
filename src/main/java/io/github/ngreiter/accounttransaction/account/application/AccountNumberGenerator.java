package io.github.ngreiter.accounttransaction.account.application;

import org.springframework.stereotype.Component;

import io.github.ngreiter.accounttransaction.domain.AccountNumber;

@Component
final class AccountNumberGenerator {

    String next() {
        return AccountNumber.generate();
    }
}
