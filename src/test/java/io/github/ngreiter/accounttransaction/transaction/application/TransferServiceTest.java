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

class TransferServiceTest {

    private static final String SOURCE = "12345678-9";
    private static final String DESTINATION = "87654321-2";
    private AccountRepository accountRepository;
    private FinancialTransactionRepository financialTransactionRepository;
    private AccountTransactionRepository accountTransactionRepository;
    private IdempotencySupport idempotencySupport;
    private TransactionTemplate transactionTemplate;
    private EntityManager entityManager;
    private TransactionResponseSnapshotSerializer responseSerializer;
    private TransferService service;

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
        service = new TransferService(accountRepository, financialTransactionRepository,
                accountTransactionRepository, idempotencySupport, transactionTemplate, entityManager,
                responseSerializer);
    }

    @Test
    void transfersFundsBetweenAccountsAndPersistsSuccessfulIdempotencyResponse() {
        Account source = new Account(SOURCE, "52998224725");
        source.credit(new BigDecimal("20.00"));
        Account destination = new Account(DESTINATION, "11222333000181");
        when(idempotencySupport.findByKey("transfer-1")).thenReturn(Optional.empty());
        when(accountRepository.findAllByAccountNumberInOrderByAccountNumberForUpdate(List.of(SOURCE, DESTINATION)))
                .thenReturn(List.of(source, destination));
        FinancialTransaction savedTransaction = mock(FinancialTransaction.class);
        when(savedTransaction.getId()).thenReturn(UUID.randomUUID());
        when(financialTransactionRepository.saveAndFlush(any())).thenReturn(savedTransaction);
        when(accountTransactionRepository.saveAndFlush(any()))
                .thenAnswer(invocation -> mock(io.github.ngreiter.accounttransaction.domain.AccountTransaction.class));
        when(responseSerializer.created(any(), eq(TransactionType.TRANSFER.name()), eq(new BigDecimal("5.00")),
                eq("SUCCEEDED"), any()))
                .thenReturn(new TransactionResponseSnapshotSerializer.Snapshot(201, "transfer-body"));

        TransferResult result = service.transfer("transfer-1",
                new TransferCommand(SOURCE, DESTINATION, new BigDecimal("5.00"), "move funds"));

        assertEquals("transfer-body", result.responseBody());
        assertEquals(new BigDecimal("15.00"), source.getBalance());
        assertEquals(new BigDecimal("5.00"), destination.getBalance());
        verify(financialTransactionRepository).saveAndFlush(any());
        verify(accountTransactionRepository, org.mockito.Mockito.times(2)).saveAndFlush(any());
        verify(idempotencySupport).save(eq("POST:/api/v1/transactions/transfers"), eq("transfer-1"), any(),
                any(), eq(201), eq("transfer-body"));
    }

    @Test
    void rejectsTransferWhenEitherAccountIsMissing() {
        Account source = new Account(SOURCE, "52998224725");
        when(idempotencySupport.findByKey("transfer-missing")).thenReturn(Optional.empty());
        when(accountRepository.findAllByAccountNumberInOrderByAccountNumberForUpdate(List.of(SOURCE, DESTINATION)))
                .thenReturn(List.of(source));
        when(responseSerializer.rejected(TransactionError.RESOURCE_NOT_FOUND))
                .thenReturn(new TransactionResponseSnapshotSerializer.Snapshot(404, "not-found"));

        TransactionApplicationException exception = assertThrows(TransactionApplicationException.class,
                () -> service.transfer("transfer-missing", new TransferCommand(SOURCE, DESTINATION,
                        new BigDecimal("1.00"), null)));

        assertEquals(TransactionError.RESOURCE_NOT_FOUND, exception.error());
        verify(financialTransactionRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsTransferWhenSourceBalanceIsInsufficient() {
        Account source = new Account(SOURCE, "52998224725");
        source.credit(new BigDecimal("2.00"));
        Account destination = new Account(DESTINATION, "11222333000181");
        when(idempotencySupport.findByKey("transfer-insufficient")).thenReturn(Optional.empty());
        when(accountRepository.findAllByAccountNumberInOrderByAccountNumberForUpdate(List.of(SOURCE, DESTINATION)))
                .thenReturn(List.of(source, destination));
        when(responseSerializer.rejected(TransactionError.INSUFFICIENT_FUNDS))
                .thenReturn(new TransactionResponseSnapshotSerializer.Snapshot(422, "insufficient"));

        TransactionApplicationException exception = assertThrows(TransactionApplicationException.class,
                () -> service.transfer("transfer-insufficient", new TransferCommand(SOURCE, DESTINATION,
                        new BigDecimal("3.00"), null)));

        assertEquals(TransactionError.INSUFFICIENT_FUNDS, exception.error());
        assertEquals(new BigDecimal("2.00"), source.getBalance());
        assertEquals(BigDecimal.ZERO.setScale(2), destination.getBalance());
        verify(financialTransactionRepository, never()).saveAndFlush(any());
    }

    @Test
    void replaysTransferForSameKeyAndRequest() {
        IdempotencyRecord record = new IdempotencyRecord("scope", "transfer-replay", "hash", 201, "saved-body");
        when(idempotencySupport.hashCanonicalRequest(any())).thenReturn("hash");
        when(idempotencySupport.findByKey("transfer-replay")).thenReturn(Optional.of(record));
        when(idempotencySupport.matchesRequest(record, "hash")).thenReturn(true);

        TransferResult result = service.transfer("transfer-replay",
                new TransferCommand(SOURCE, DESTINATION, new BigDecimal("1.00"), null));

        assertEquals("saved-body", result.responseBody());
        assertEquals(201, result.replayedStatus());
        verify(accountRepository, never()).findAllByAccountNumberInOrderByAccountNumberForUpdate(any());
    }

    @Test
    void resolvesConcurrentTransferKeyCollisionByReplayingSavedResponse() {
        IdempotencyRecord record = new IdempotencyRecord("scope", "transfer-race", "hash", 201, "race-body");
        doAnswer(invocation -> { throw new DataIntegrityViolationException("duplicate idempotency key"); })
                .when(transactionTemplate).execute(any());
        when(idempotencySupport.hashCanonicalRequest(any())).thenReturn("hash");
        when(idempotencySupport.findByKey("transfer-race")).thenReturn(Optional.of(record));
        when(idempotencySupport.matchesRequest(record, "hash")).thenReturn(true);

        TransferResult result = service.transfer("transfer-race",
                new TransferCommand(SOURCE, DESTINATION, new BigDecimal("1.00"), null));

        assertEquals("race-body", result.responseBody());
        assertEquals(201, result.replayedStatus());
    }

    @Test
    void rejectsReuseOfTransferKeyForDifferentRequest() {
        IdempotencyRecord record = new IdempotencyRecord("scope", "transfer-conflict", "old-hash", 201, "saved-body");
        when(idempotencySupport.hashCanonicalRequest(any())).thenReturn("new-hash");
        when(idempotencySupport.findByKey("transfer-conflict")).thenReturn(Optional.of(record));
        when(idempotencySupport.matchesRequest(record, "new-hash")).thenReturn(false);
        when(responseSerializer.rejected(TransactionError.IDEMPOTENCY_CONFLICT))
                .thenReturn(new TransactionResponseSnapshotSerializer.Snapshot(409, "key-conflict"));

        TransactionApplicationException exception = assertThrows(TransactionApplicationException.class,
                () -> service.transfer("transfer-conflict", new TransferCommand(SOURCE, DESTINATION,
                        new BigDecimal("2.00"), null)));

        assertEquals(TransactionError.IDEMPOTENCY_CONFLICT, exception.error());
        assertEquals("key-conflict", exception.responseBody());
    }

    @Test
    void replaysPreviouslyStoredInsufficientFundsResult() {
        IdempotencyRecord record = new IdempotencyRecord("scope", "transfer-error-replay", "hash", 422,
                "saved-insufficient");
        when(idempotencySupport.hashCanonicalRequest(any())).thenReturn("hash");
        when(idempotencySupport.findByKey("transfer-error-replay")).thenReturn(Optional.of(record));
        when(idempotencySupport.matchesRequest(record, "hash")).thenReturn(true);

        TransactionApplicationException exception = assertThrows(TransactionApplicationException.class,
                () -> service.transfer("transfer-error-replay",
                        new TransferCommand(SOURCE, DESTINATION, new BigDecimal("1.00"), null)));

        assertEquals(TransactionError.INSUFFICIENT_FUNDS, exception.error());
        assertEquals("saved-insufficient", exception.responseBody());
    }

    @Test
    void rejectsTransferBetweenSameAccountBeforeStartingTransaction() {
        assertThrows(IllegalArgumentException.class,
                () -> service.transfer("transfer-same-account", new TransferCommand(SOURCE, SOURCE,
                        new BigDecimal("1.00"), null)));

        verify(transactionTemplate, never()).execute(any());
    }

    @Test
    void rejectsTransferWithInvalidAmountBeforeStartingTransaction() {
        assertThrows(IllegalArgumentException.class,
                () -> service.transfer("transfer-invalid-amount", new TransferCommand(SOURCE, DESTINATION,
                        BigDecimal.ZERO, null)));

        verify(transactionTemplate, never()).execute(any());
    }
}
