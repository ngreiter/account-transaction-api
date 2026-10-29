package io.github.ngreiter.accounttransaction.account.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.ngreiter.accounttransaction.account.api.AccountDetailsResponse;
import io.github.ngreiter.accounttransaction.domain.Account;
import io.github.ngreiter.accounttransaction.persistence.AccountRepository;

class AccountQueryServiceTest {

    private AccountRepository accountRepository;
    private AccountQueryService service;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        service = new AccountQueryService(accountRepository);
    }

    @Test
    void returnsExistingAccountWithMaskedCpfAndBalance() {
        Account account = new Account("12345678-9", "52998224725");
        account.credit(new BigDecimal("125.50"));
        when(accountRepository.findById("12345678-9")).thenReturn(Optional.of(account));

        AccountDetailsResponse response = service.getByAccountNumber("12345678-9");

        assertEquals("12345678-9", response.accountNumber());
        assertEquals("***.***.***-25", response.documentMasked());
        assertEquals(new BigDecimal("125.50"), response.balance());
        verify(accountRepository).findById("12345678-9");
    }

    @Test
    void returnsExistingAccountWithMaskedCnpj() {
        Account account = new Account("12345678-9", "11222333000181");
        when(accountRepository.findById("12345678-9")).thenReturn(Optional.of(account));

        AccountDetailsResponse response = service.getByAccountNumber("12345678-9");

        assertEquals("**.***.***/****-81", response.documentMasked());
        assertEquals(new BigDecimal("0.00"), response.balance());
    }

    @Test
    void throwsAccountNotFoundWhenAccountNumberDoesNotExist() {
        when(accountRepository.findById("12345678-9")).thenReturn(Optional.empty());

        assertThrows(AccountNotFoundException.class, () -> service.getByAccountNumber("12345678-9"));

        verify(accountRepository).findById("12345678-9");
    }

    @Test
    void rejectsInvalidAccountNumberBeforeRepositoryLookup() {
        assertThrows(IllegalArgumentException.class, () -> service.getByAccountNumber("invalid"));

        verifyNoInteractions(accountRepository);
    }
}
