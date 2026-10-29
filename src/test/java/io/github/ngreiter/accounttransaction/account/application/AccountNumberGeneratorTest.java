package io.github.ngreiter.accounttransaction.account.application;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.ngreiter.accounttransaction.domain.AccountNumber;

class AccountNumberGeneratorTest {

    @Test
    void generatesAccountNumberWithValidFormatAndCheckDigit() {
        AccountNumberGenerator generator = new AccountNumberGenerator();

        String accountNumber = generator.next();

        assertTrue(accountNumber.matches("[0-9]{8}-[0-9]"));
        assertTrue(AccountNumber.isValid(accountNumber));
    }
}
