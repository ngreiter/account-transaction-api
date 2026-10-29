package io.github.ngreiter.accounttransaction.account.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.hibernate.exception.ConstraintViolationException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.ngreiter.accounttransaction.domain.Account;
import io.github.ngreiter.accounttransaction.domain.IdempotencyRecord;
import io.github.ngreiter.accounttransaction.persistence.AccountRepository;
import io.github.ngreiter.accounttransaction.shared.application.IdempotencySupport;

class AccountCreationServiceTest {

    private AccountRepository accountRepository;
    private IdempotencySupport idempotencySupport;
    private AccountNumberGenerator accountNumberGenerator;
    private AccountCreationResponseSnapshotSerializer responseSerializer;
    private TransactionTemplate transactionTemplate;
    private EntityManager entityManager;
    private AccountCreationService service;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        idempotencySupport = mock(IdempotencySupport.class);
        accountNumberGenerator = mock(AccountNumberGenerator.class);
        responseSerializer = mock(AccountCreationResponseSnapshotSerializer.class);
        transactionTemplate = mock(TransactionTemplate.class);
        entityManager = mock(EntityManager.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactionTemplate).execute(any());
        service = new AccountCreationService(accountRepository, idempotencySupport, accountNumberGenerator,
                responseSerializer, transactionTemplate, entityManager);
    }

    @Test
    void createsAccountForValidCpfAndPersistsCreatedResponse() {
        when(accountNumberGenerator.next()).thenReturn("1234567890");
        when(idempotencySupport.findByKey("key-1")).thenReturn(Optional.empty());
        when(accountRepository.findByDocument("52998224725")).thenReturn(Optional.empty());
        when(responseSerializer.created(eq("1234567890"), any(), any()))
                .thenReturn(new AccountCreationResponseSnapshotSerializer.Snapshot(201, "created-body"));

        AccountCreationResult result = service.create("key-1", "529.982.247-25");

        assertEquals("created-body", result.responseBody());
        assertNull(result.error());
        verify(accountRepository).saveAndFlush(any(Account.class));
        verify(idempotencySupport).save(eq("POST:/api/v1/accounts"), eq("key-1"), any(), eq(201),
                eq("created-body"));
    }

    @Test
    void rejectsCpfAlreadyRegistered() {
        when(accountNumberGenerator.next()).thenReturn("1234567890");
        when(idempotencySupport.findByKey("key-cpf")).thenReturn(Optional.empty());
        when(accountRepository.findByDocument("52998224725")).thenReturn(Optional.of(new Account("0000000001", "52998224725")));
        when(responseSerializer.rejected(AccountCreationError.ACCOUNT_CONFLICT))
                .thenReturn(new AccountCreationResponseSnapshotSerializer.Snapshot(409, "conflict-body"));

        AccountCreationException exception = assertThrows(AccountCreationException.class,
                () -> service.create("key-cpf", "52998224725"));

        assertEquals(AccountCreationError.ACCOUNT_CONFLICT, exception.error());
        assertEquals("conflict-body", exception.responseBody());
        verify(accountRepository, never()).saveAndFlush(any(Account.class));
    }

    @Test
    void rejectsCnpjAlreadyRegistered() {
        when(accountNumberGenerator.next()).thenReturn("1234567890");
        when(idempotencySupport.findByKey("key-cnpj")).thenReturn(Optional.empty());
        when(accountRepository.findByDocument("11222333000181"))
                .thenReturn(Optional.of(new Account("0000000002", "11222333000181")));
        when(responseSerializer.rejected(AccountCreationError.ACCOUNT_CONFLICT))
                .thenReturn(new AccountCreationResponseSnapshotSerializer.Snapshot(409, "conflict-body"));

        AccountCreationException exception = assertThrows(AccountCreationException.class,
                () -> service.create("key-cnpj", "11.222.333/0001-81"));

        assertEquals(AccountCreationError.ACCOUNT_CONFLICT, exception.error());
        assertEquals("conflict-body", exception.responseBody());
        verify(accountRepository, never()).saveAndFlush(any(Account.class));
    }

    @Test
    void rejectsInvalidDocumentBeforeStartingTransaction() {
        assertThrows(IllegalArgumentException.class, () -> service.create("key-invalid", "111.111.111-11"));

        verify(transactionTemplate, never()).execute(any());
        verify(accountRepository, never()).findByDocument(any());
    }

    @Test
    void rejectsNullDocumentBeforeStartingTransaction() {
        assertThrows(IllegalArgumentException.class, () -> service.create("key-null-document", null));

        verify(transactionTemplate, never()).execute(any());
    }

    @Test
    void createsAccountForValidCnpj() {
        when(accountNumberGenerator.next()).thenReturn("1234567890");
        when(idempotencySupport.findByKey("key-valid-cnpj")).thenReturn(Optional.empty());
        when(accountRepository.findByDocument("11222333000181")).thenReturn(Optional.empty());
        when(responseSerializer.created(eq("1234567890"), any(), any()))
                .thenReturn(new AccountCreationResponseSnapshotSerializer.Snapshot(201, "created-cnpj"));

        AccountCreationResult result = service.create("key-valid-cnpj", "11.222.333/0001-81");

        assertEquals("created-cnpj", result.responseBody());
        verify(accountRepository).findByDocument("11222333000181");
    }

    @Test
    void replaysSuccessfulRequestWhenIdempotencyKeyAndPayloadMatch() {
        IdempotencyRecord record = new IdempotencyRecord("POST:/api/v1/accounts", "key-replay", "hash", 201,
                "original-body");
        when(idempotencySupport.hashCanonicalRequest("52998224725")).thenReturn("hash");
        when(idempotencySupport.findByKey("key-replay")).thenReturn(Optional.of(record));
        when(idempotencySupport.matchesRequest(record, "hash")).thenReturn(true);

        AccountCreationResult result = service.create("key-replay", "52998224725");

        assertEquals("original-body", result.responseBody());
        assertEquals(201, result.replayedStatus());
        verify(accountRepository, never()).findByDocument(any());
    }

    @Test
    void rejectsReusedIdempotencyKeyWithDifferentPayload() {
        IdempotencyRecord record = new IdempotencyRecord("POST:/api/v1/accounts", "key-conflict", "other-hash", 201,
                "original-body");
        when(idempotencySupport.hashCanonicalRequest("52998224725")).thenReturn("hash");
        when(idempotencySupport.findByKey("key-conflict")).thenReturn(Optional.of(record));
        when(idempotencySupport.matchesRequest(record, "hash")).thenReturn(false);
        when(responseSerializer.rejected(AccountCreationError.IDEMPOTENCY_CONFLICT))
                .thenReturn(new AccountCreationResponseSnapshotSerializer.Snapshot(409, "idempotency-conflict"));

        AccountCreationException exception = assertThrows(AccountCreationException.class,
                () -> service.create("key-conflict", "52998224725"));

        assertEquals(AccountCreationError.IDEMPOTENCY_CONFLICT, exception.error());
        assertEquals("idempotency-conflict", exception.responseBody());
        verify(accountRepository, never()).findByDocument(any());
    }

    @Test
    void replaysStoredAccountConflictAsAccountCreationException() {
        IdempotencyRecord record = new IdempotencyRecord("POST:/api/v1/accounts", "key-stored-conflict", "hash", 409,
                "stored-conflict");
        when(idempotencySupport.hashCanonicalRequest("52998224725")).thenReturn("hash");
        when(idempotencySupport.findByKey("key-stored-conflict")).thenReturn(Optional.of(record));
        when(idempotencySupport.matchesRequest(record, "hash")).thenReturn(true);

        AccountCreationException exception = assertThrows(AccountCreationException.class,
                () -> service.create("key-stored-conflict", "52998224725"));

        assertEquals(AccountCreationError.ACCOUNT_CONFLICT, exception.error());
        assertEquals("stored-conflict", exception.responseBody());
    }

    @Test
    void rejectsBlankIdempotencyKeyBeforeStartingTransaction() {
        org.mockito.Mockito.doThrow(new IllegalArgumentException())
                .when(idempotencySupport).validateKey(" ");

        assertThrows(IllegalArgumentException.class, () -> service.create(" ", "52998224725"));

        verify(transactionTemplate, never()).execute(any());
    }

    @Test
    void retriesAccountNumberCollisionAndSucceedsOnNextAttempt() {
        when(accountNumberGenerator.next()).thenReturn("collision", "1234567890");
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            if (attempts.getAndIncrement() == 0) {
                throw integrityViolation("account_pkey");
            }
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactionTemplate).execute(any());
        when(idempotencySupport.findByKey("key-retry")).thenReturn(Optional.empty());
        when(accountRepository.findByDocument("52998224725")).thenReturn(Optional.empty());
        when(responseSerializer.created(eq("1234567890"), any(), any()))
                .thenReturn(new AccountCreationResponseSnapshotSerializer.Snapshot(201, "created"));

        AccountCreationResult result = service.create("key-retry", "52998224725");

        assertEquals("created", result.responseBody());
        verify(transactionTemplate, times(2)).execute(any());
    }

    @Test
    void propagatesAccountNumberCollisionAfterMaximumAttempts() {
        when(accountNumberGenerator.next()).thenReturn("collision-1", "collision-2", "collision-3");
        doAnswer(invocation -> { throw integrityViolation("account_pkey"); })
                .when(transactionTemplate).execute(any());

        assertThrows(DataIntegrityViolationException.class, () -> service.create("key-exhausted", "52998224725"));

        verify(transactionTemplate, times(3)).execute(any());
    }

    @Test
    void propagatesUnexpectedIntegrityConstraint() {
        doAnswer(invocation -> { throw integrityViolation("some_other_constraint"); })
                .when(transactionTemplate).execute(any());

        assertThrows(DataIntegrityViolationException.class, () -> service.create("key-unexpected", "52998224725"));

        verify(transactionTemplate).execute(any());
    }

    @Test
    void resolvesConcurrentIdempotencyKeyCollisionByReplayingExistingResponse() {
        IdempotencyRecord record = new IdempotencyRecord("POST:/api/v1/accounts", "key-race", "hash", 201,
                "concurrent-body");
        doAnswer(invocation -> { throw integrityViolation("uq_idempotency_record_key"); })
                .when(transactionTemplate).execute(any());
        when(idempotencySupport.findByKey("key-race")).thenReturn(Optional.of(record));
        when(idempotencySupport.hashCanonicalRequest("52998224725")).thenReturn("hash");
        when(idempotencySupport.matchesRequest(record, "hash")).thenReturn(true);

        AccountCreationResult result = service.create("key-race", "52998224725");

        assertEquals("concurrent-body", result.responseBody());
        assertEquals(201, result.replayedStatus());
    }

    @Test
    void rejectsConcurrentIdempotencyKeyCollisionWhenPayloadDiffers() {
        IdempotencyRecord record = new IdempotencyRecord("POST:/api/v1/accounts", "key-race-conflict", "other", 201,
                "concurrent-body");
        doAnswer(invocation -> { throw integrityViolation("uq_idempotency_record_key"); })
                .when(transactionTemplate).execute(any());
        when(idempotencySupport.findByKey("key-race-conflict")).thenReturn(Optional.of(record));
        when(idempotencySupport.hashCanonicalRequest("52998224725")).thenReturn("hash");
        when(idempotencySupport.matchesRequest(record, "hash")).thenReturn(false);
        when(responseSerializer.rejected(AccountCreationError.IDEMPOTENCY_CONFLICT))
                .thenReturn(new AccountCreationResponseSnapshotSerializer.Snapshot(409, "idempotency-conflict"));

        AccountCreationException exception = assertThrows(AccountCreationException.class,
                () -> service.create("key-race-conflict", "52998224725"));

        assertEquals(AccountCreationError.IDEMPOTENCY_CONFLICT, exception.error());
        assertEquals("idempotency-conflict", exception.responseBody());
    }

    @Test
    void propagatesIdempotencyKeyCollisionWhenNoExistingRecordCanBeFound() {
        doAnswer(invocation -> { throw integrityViolation("uq_idempotency_record_key"); })
                .when(transactionTemplate).execute(any());
        when(idempotencySupport.findByKey("key-race-missing")).thenReturn(Optional.empty());

        assertThrows(DataIntegrityViolationException.class,
                () -> service.create("key-race-missing", "52998224725"));
    }

    @Test
    void retriesAfterDocumentConstraintViolation() {
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            if (attempts.getAndIncrement() == 0) {
                throw integrityViolation("uq_account_document");
            }
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactionTemplate).execute(any());
        when(idempotencySupport.findByKey("key-document-race")).thenReturn(Optional.empty());
        when(accountRepository.findByDocument("52998224725")).thenReturn(Optional.empty());
        when(responseSerializer.created(any(), any(), any()))
                .thenReturn(new AccountCreationResponseSnapshotSerializer.Snapshot(201, "created"));

        AccountCreationResult result = service.create("key-document-race", "52998224725");

        assertEquals("created", result.responseBody());
        verify(transactionTemplate, times(2)).execute(any());
    }

    @Test
    void propagatesSecondDocumentConstraintViolation() {
        doAnswer(invocation -> { throw integrityViolation("uq_account_document"); })
                .when(transactionTemplate).execute(any());

        assertThrows(DataIntegrityViolationException.class,
                () -> service.create("key-document-race-fails", "52998224725"));

        verify(transactionTemplate, times(2)).execute(any());
    }

    @Test
    void rejectsNullTransactionResult() {
        doReturn(null).when(transactionTemplate).execute(any());

        assertThrows(IllegalStateException.class, () -> service.create("key-null-result", "52998224725"));

        verify(transactionTemplate).execute(any());
    }

    @Test
    void propagatesIntegrityViolationWithoutHibernateConstraintCause() {
        doAnswer(invocation -> {
            throw new DataIntegrityViolationException("integrity violation", new SQLException("database error"));
        }).when(transactionTemplate).execute(any());

        assertThrows(DataIntegrityViolationException.class,
                () -> service.create("key-no-constraint-name", "52998224725"));
    }

    private DataIntegrityViolationException integrityViolation(String constraintName) {
        ConstraintViolationException cause = new ConstraintViolationException(
                "constraint violation", new SQLException("integrity violation"), constraintName);
        return new DataIntegrityViolationException("integrity violation", cause);
    }
}
