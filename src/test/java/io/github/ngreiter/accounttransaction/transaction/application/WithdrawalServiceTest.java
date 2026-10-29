package io.github.ngreiter.accounttransaction.transaction.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.ngreiter.accounttransaction.domain.Account;
import io.github.ngreiter.accounttransaction.domain.IdempotencyRecord;
import io.github.ngreiter.accounttransaction.domain.FinancialTransaction;
import io.github.ngreiter.accounttransaction.domain.TransactionType;
import io.github.ngreiter.accounttransaction.persistence.AccountRepository;
import io.github.ngreiter.accounttransaction.persistence.AccountTransactionRepository;
import io.github.ngreiter.accounttransaction.persistence.FinancialTransactionRepository;
import io.github.ngreiter.accounttransaction.shared.application.IdempotencySupport;

class WithdrawalServiceTest {

    private static final String ACCOUNT_NUMBER = "12345678-9";
    private AccountRepository accountRepository;
    private FinancialTransactionRepository financialTransactionRepository;
    private AccountTransactionRepository accountTransactionRepository;
    private IdempotencySupport idempotencySupport;
    private TransactionTemplate transactionTemplate;
    private EntityManager entityManager;
    private TransactionResponseSnapshotSerializer responseSerializer;
    private WithdrawalService service;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        financialTransactionRepository = mock(FinancialTransactionRepository.class);
        accountTransactionRepository = mock(AccountTransactionRepository.class);
        idempotencySupport = mock(IdempotencySupport.class);
        transactionTemplate = mock(TransactionTemplate.class);
        entityManager = mock(EntityManager.class);
        responseSerializer = mock(TransactionResponseSnapshotSerializer.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactionTemplate).execute(any());
        service = new WithdrawalService(accountRepository, financialTransactionRepository,
                accountTransactionRepository, idempotencySupport, transactionTemplate, entityManager,
                responseSerializer);
    }

    @Test
    void withdrawsAmountAndPersistsSuccessfulIdempotencyResponse() {
        Account account = new Account(ACCOUNT_NUMBER, "52998224725");
        account.credit(new BigDecimal("20.00"));
        when(idempotencySupport.findByKey("withdraw-1")).thenReturn(Optional.empty());
        when(accountRepository.findAllByAccountNumberInOrderByAccountNumberForUpdate(List.of(ACCOUNT_NUMBER)))
                .thenReturn(List.of(account));
        FinancialTransaction savedTransaction = mock(FinancialTransaction.class);
        when(savedTransaction.getId()).thenReturn(UUID.randomUUID());
        when(financialTransactionRepository.saveAndFlush(any())).thenReturn(savedTransaction);
        when(accountTransactionRepository.saveAndFlush(any()))
                .thenAnswer(invocation -> mock(io.github.ngreiter.accounttransaction.domain.AccountTransaction.class));
        when(responseSerializer.created(any(), eq(TransactionType.WITHDRAWAL.name()), eq(new BigDecimal("5.00")),
                eq("SUCCEEDED"), any()))
                .thenReturn(new TransactionResponseSnapshotSerializer.Snapshot(201, "withdraw-body"));

        WithdrawalResult result = service.withdraw("withdraw-1",
                new WithdrawalCommand(ACCOUNT_NUMBER, new BigDecimal("5.00"), "cash out"));

        assertEquals("withdraw-body", result.responseBody());
        assertEquals(new BigDecimal("15.00"), account.getBalance());
        verify(financialTransactionRepository).saveAndFlush(any());
        verify(accountTransactionRepository).saveAndFlush(any());
        verify(idempotencySupport).save(eq("POST:/api/v1/transactions/withdrawals"), eq("withdraw-1"), any(),
                any(), eq(201), eq("withdraw-body"));
    }

    @Test
    void rejectsWithdrawalForMissingAccount() {
        when(idempotencySupport.findByKey("withdraw-missing")).thenReturn(Optional.empty());
        when(accountRepository.findAllByAccountNumberInOrderByAccountNumberForUpdate(List.of(ACCOUNT_NUMBER)))
                .thenReturn(List.of());
        when(responseSerializer.rejected(TransactionError.RESOURCE_NOT_FOUND))
                .thenReturn(new TransactionResponseSnapshotSerializer.Snapshot(404, "not-found"));

        TransactionApplicationException exception = assertThrows(TransactionApplicationException.class,
                () -> service.withdraw("withdraw-missing", new WithdrawalCommand(ACCOUNT_NUMBER,
                        new BigDecimal("1.00"), null)));

        assertEquals(TransactionError.RESOURCE_NOT_FOUND, exception.error());
        verify(idempotencySupport).save(eq("POST:/api/v1/transactions/withdrawals"), eq("withdraw-missing"),
                any(), eq(404), eq("not-found"));
    }

    @Test
    void rejectsWithdrawalWhenBalanceIsInsufficientAndPersistsResult() {
        Account account = new Account(ACCOUNT_NUMBER, "52998224725");
        account.credit(new BigDecimal("2.00"));
        when(idempotencySupport.findByKey("withdraw-insufficient")).thenReturn(Optional.empty());
        when(accountRepository.findAllByAccountNumberInOrderByAccountNumberForUpdate(List.of(ACCOUNT_NUMBER)))
                .thenReturn(List.of(account));
        when(responseSerializer.rejected(TransactionError.INSUFFICIENT_FUNDS))
                .thenReturn(new TransactionResponseSnapshotSerializer.Snapshot(422, "insufficient"));

        TransactionApplicationException exception = assertThrows(TransactionApplicationException.class,
                () -> service.withdraw("withdraw-insufficient", new WithdrawalCommand(ACCOUNT_NUMBER,
                        new BigDecimal("3.00"), null)));

        assertEquals(TransactionError.INSUFFICIENT_FUNDS, exception.error());
        assertEquals(new BigDecimal("2.00"), account.getBalance());
        verify(idempotencySupport).save(eq("POST:/api/v1/transactions/withdrawals"), eq("withdraw-insufficient"),
                any(), eq(422), eq("insufficient"));
        verify(financialTransactionRepository, never()).saveAndFlush(any());
    }

    @Test
    void replaysWithdrawalForSameKeyAndRequest() {
        IdempotencyRecord record = new IdempotencyRecord("scope", "withdraw-replay", "hash", 201, "saved-body");
        when(idempotencySupport.hashCanonicalRequest(any())).thenReturn("hash");
        when(idempotencySupport.findByKey("withdraw-replay")).thenReturn(Optional.of(record));
        when(idempotencySupport.matchesRequest(record, "hash")).thenReturn(true);

        WithdrawalResult result = service.withdraw("withdraw-replay",
                new WithdrawalCommand(ACCOUNT_NUMBER, new BigDecimal("1.00"), null));

        assertEquals("saved-body", result.responseBody());
        assertEquals(201, result.replayedStatus());
        verify(accountRepository, never()).findAllByAccountNumberInOrderByAccountNumberForUpdate(any());
    }

    @Test
    void resolvesConcurrentWithdrawalKeyCollisionByReplayingSavedResponse() {
        IdempotencyRecord record = new IdempotencyRecord("scope", "withdraw-race", "hash", 201, "race-body");
        doAnswer(invocation -> { throw new DataIntegrityViolationException("duplicate idempotency key"); })
                .when(transactionTemplate).execute(any());
        when(idempotencySupport.hashCanonicalRequest(any())).thenReturn("hash");
        when(idempotencySupport.findByKey("withdraw-race")).thenReturn(Optional.of(record));
        when(idempotencySupport.matchesRequest(record, "hash")).thenReturn(true);

        WithdrawalResult result = service.withdraw("withdraw-race",
                new WithdrawalCommand(ACCOUNT_NUMBER, new BigDecimal("1.00"), null));

        assertEquals("race-body", result.responseBody());
        assertEquals(201, result.replayedStatus());
    }

    @Test
    void rejectsReuseOfWithdrawalKeyForDifferentRequest() {
        IdempotencyRecord record = new IdempotencyRecord("scope", "withdraw-conflict", "old-hash", 201, "saved-body");
        when(idempotencySupport.hashCanonicalRequest(any())).thenReturn("new-hash");
        when(idempotencySupport.findByKey("withdraw-conflict")).thenReturn(Optional.of(record));
        when(idempotencySupport.matchesRequest(record, "new-hash")).thenReturn(false);
        when(responseSerializer.rejected(TransactionError.IDEMPOTENCY_CONFLICT))
                .thenReturn(new TransactionResponseSnapshotSerializer.Snapshot(409, "key-conflict"));

        TransactionApplicationException exception = assertThrows(TransactionApplicationException.class,
                () -> service.withdraw("withdraw-conflict", new WithdrawalCommand(ACCOUNT_NUMBER,
                        new BigDecimal("2.00"), null)));

        assertEquals(TransactionError.IDEMPOTENCY_CONFLICT, exception.error());
        assertEquals("key-conflict", exception.responseBody());
    }

    @Test
    void replaysPreviouslyStoredInsufficientFundsResult() {
        IdempotencyRecord record = new IdempotencyRecord("scope", "withdraw-error-replay", "hash", 422,
                "saved-insufficient");
        when(idempotencySupport.hashCanonicalRequest(any())).thenReturn("hash");
        when(idempotencySupport.findByKey("withdraw-error-replay")).thenReturn(Optional.of(record));
        when(idempotencySupport.matchesRequest(record, "hash")).thenReturn(true);

        TransactionApplicationException exception = assertThrows(TransactionApplicationException.class,
                () -> service.withdraw("withdraw-error-replay",
                        new WithdrawalCommand(ACCOUNT_NUMBER, new BigDecimal("1.00"), null)));

        assertEquals(TransactionError.INSUFFICIENT_FUNDS, exception.error());
        assertEquals("saved-insufficient", exception.responseBody());
    }

    @Test
    void rejectsZeroAmountBeforeStartingTransaction() {
        assertThrows(IllegalArgumentException.class,
                () -> service.withdraw("withdraw-invalid", new WithdrawalCommand(ACCOUNT_NUMBER,
                        BigDecimal.ZERO, null)));

        verify(transactionTemplate, never()).execute(any());
    }
}
