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

class DepositServiceTest {

    private static final String ACCOUNT_NUMBER = "12345678-9";
    private AccountRepository accountRepository;
    private FinancialTransactionRepository financialTransactionRepository;
    private AccountTransactionRepository accountTransactionRepository;
    private IdempotencySupport idempotencySupport;
    private TransactionTemplate transactionTemplate;
    private EntityManager entityManager;
    private TransactionResponseSnapshotSerializer responseSerializer;
    private DepositService service;

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
        service = new DepositService(accountRepository, financialTransactionRepository,
                accountTransactionRepository, idempotencySupport, transactionTemplate, entityManager,
                responseSerializer);
    }

    @Test
    void depositsAmountAndPersistsSuccessfulIdempotencyResponse() {
        Account account = new Account(ACCOUNT_NUMBER, "52998224725");
        when(idempotencySupport.findByKey("deposit-1")).thenReturn(Optional.empty());
        when(accountRepository.findAllByAccountNumberInOrderByAccountNumberForUpdate(List.of(ACCOUNT_NUMBER)))
                .thenReturn(List.of(account));
        FinancialTransaction savedTransaction = mock(FinancialTransaction.class);
        when(savedTransaction.getId()).thenReturn(UUID.randomUUID());
        when(financialTransactionRepository.saveAndFlush(any())).thenReturn(savedTransaction);
        when(accountTransactionRepository.saveAndFlush(any()))
                .thenAnswer(invocation -> mock(io.github.ngreiter.accounttransaction.domain.AccountTransaction.class));
        when(responseSerializer.created(any(), eq(TransactionType.DEPOSIT.name()), eq(new BigDecimal("10.00")),
                eq("SUCCEEDED"), any()))
                .thenReturn(new TransactionResponseSnapshotSerializer.Snapshot(201, "deposit-body"));

        DepositResult result = service.deposit("deposit-1",
                new DepositCommand(ACCOUNT_NUMBER, new BigDecimal("10.00"), "cash in"));

        assertEquals("deposit-body", result.responseBody());
        assertEquals(new BigDecimal("10.00"), account.getBalance());
        verify(financialTransactionRepository).saveAndFlush(any());
        verify(accountTransactionRepository).saveAndFlush(any());
        verify(idempotencySupport).save(eq("POST:/api/v1/transactions/deposits"), eq("deposit-1"), any(),
                any(), eq(201), eq("deposit-body"));
    }

    @Test
    void rejectsDepositWhenAccountDoesNotExistAndPersistsErrorForReplay() {
        when(idempotencySupport.findByKey("deposit-missing")).thenReturn(Optional.empty());
        when(accountRepository.findAllByAccountNumberInOrderByAccountNumberForUpdate(List.of(ACCOUNT_NUMBER)))
                .thenReturn(List.of());
        when(responseSerializer.rejected(TransactionError.RESOURCE_NOT_FOUND))
                .thenReturn(new TransactionResponseSnapshotSerializer.Snapshot(404, "not-found"));

        TransactionApplicationException exception = assertThrows(TransactionApplicationException.class,
                () -> service.deposit("deposit-missing", new DepositCommand(ACCOUNT_NUMBER,
                        new BigDecimal("1.00"), null)));

        assertEquals(TransactionError.RESOURCE_NOT_FOUND, exception.error());
        verify(idempotencySupport).save(eq("POST:/api/v1/transactions/deposits"), eq("deposit-missing"), any(),
                eq(404), eq("not-found"));
        verify(financialTransactionRepository, never()).saveAndFlush(any());
    }

    @Test
    void replaysDepositForSameKeyAndRequest() {
        IdempotencyRecord record = new IdempotencyRecord("scope", "deposit-replay", "hash", 201, "saved-body");
        when(idempotencySupport.hashCanonicalRequest(any())).thenReturn("hash");
        when(idempotencySupport.findByKey("deposit-replay")).thenReturn(Optional.of(record));
        when(idempotencySupport.matchesRequest(record, "hash")).thenReturn(true);

        DepositResult result = service.deposit("deposit-replay",
                new DepositCommand(ACCOUNT_NUMBER, new BigDecimal("1.00"), null));

        assertEquals("saved-body", result.responseBody());
        assertEquals(201, result.replayedStatus());
        verify(accountRepository, never()).findAllByAccountNumberInOrderByAccountNumberForUpdate(any());
    }

    @Test
    void resolvesConcurrentDepositKeyCollisionByReplayingSavedResponse() {
        IdempotencyRecord record = new IdempotencyRecord("scope", "deposit-race", "hash", 201, "race-body");
        doAnswer(invocation -> { throw new DataIntegrityViolationException("duplicate idempotency key"); })
                .when(transactionTemplate).execute(any());
        when(idempotencySupport.hashCanonicalRequest(any())).thenReturn("hash");
        when(idempotencySupport.findByKey("deposit-race")).thenReturn(Optional.of(record));
        when(idempotencySupport.matchesRequest(record, "hash")).thenReturn(true);

        DepositResult result = service.deposit("deposit-race",
                new DepositCommand(ACCOUNT_NUMBER, new BigDecimal("1.00"), null));

        assertEquals("race-body", result.responseBody());
        assertEquals(201, result.replayedStatus());
    }

    @Test
    void rejectsReuseOfDepositKeyForDifferentRequest() {
        IdempotencyRecord record = new IdempotencyRecord("scope", "deposit-conflict", "old-hash", 201, "saved-body");
        when(idempotencySupport.hashCanonicalRequest(any())).thenReturn("new-hash");
        when(idempotencySupport.findByKey("deposit-conflict")).thenReturn(Optional.of(record));
        when(idempotencySupport.matchesRequest(record, "new-hash")).thenReturn(false);
        when(responseSerializer.rejected(TransactionError.IDEMPOTENCY_CONFLICT))
                .thenReturn(new TransactionResponseSnapshotSerializer.Snapshot(409, "key-conflict"));

        TransactionApplicationException exception = assertThrows(TransactionApplicationException.class,
                () -> service.deposit("deposit-conflict", new DepositCommand(ACCOUNT_NUMBER,
                        new BigDecimal("2.00"), null)));

        assertEquals(TransactionError.IDEMPOTENCY_CONFLICT, exception.error());
        assertEquals("key-conflict", exception.responseBody());
    }

    @Test
    void replaysPreviouslyStoredNotFoundResultAsDomainException() {
        IdempotencyRecord record = new IdempotencyRecord("scope", "deposit-not-found-replay", "hash", 404,
                "saved-not-found");
        when(idempotencySupport.hashCanonicalRequest(any())).thenReturn("hash");
        when(idempotencySupport.findByKey("deposit-not-found-replay")).thenReturn(Optional.of(record));
        when(idempotencySupport.matchesRequest(record, "hash")).thenReturn(true);

        TransactionApplicationException exception = assertThrows(TransactionApplicationException.class,
                () -> service.deposit("deposit-not-found-replay",
                        new DepositCommand(ACCOUNT_NUMBER, new BigDecimal("1.00"), null)));

        assertEquals(TransactionError.RESOURCE_NOT_FOUND, exception.error());
        assertEquals("saved-not-found", exception.responseBody());
    }

    @Test
    void rejectsInvalidDepositAmountBeforeStartingTransaction() {
        assertThrows(IllegalArgumentException.class,
                () -> service.deposit("deposit-invalid", new DepositCommand(ACCOUNT_NUMBER,
                        new BigDecimal("0.00"), null)));

        verify(transactionTemplate, never()).execute(any());
    }
}
