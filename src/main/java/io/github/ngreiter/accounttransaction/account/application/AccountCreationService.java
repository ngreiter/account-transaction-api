package io.github.ngreiter.accounttransaction.account.application;

import java.math.BigDecimal;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.hibernate.exception.ConstraintViolationException;

import jakarta.persistence.EntityManager;


import io.github.ngreiter.accounttransaction.domain.Account;
import io.github.ngreiter.accounttransaction.domain.IdempotencyRecord;
import io.github.ngreiter.accounttransaction.persistence.AccountRepository;
import io.github.ngreiter.accounttransaction.shared.application.IdempotencySupport;

@Service
public class AccountCreationService {

    private static final String SCOPE = "POST:/api/v1/accounts";
    private static final int MAX_ATTEMPTS = 3;
    private static final String ACCOUNT_NUMBER_CONSTRAINT = "account_pkey";
    private static final String DOCUMENT_CONSTRAINT = "uq_account_document";
    private static final String IDEMPOTENCY_KEY_CONSTRAINT = "uq_idempotency_record_key";

    private final AccountRepository accountRepository;
    private final IdempotencySupport idempotencySupport;
    private final AccountNumberGenerator accountNumberGenerator;
    private final AccountCreationResponseSnapshotSerializer responseSerializer;
    private final TransactionTemplate transactionTemplate;
    private final EntityManager entityManager;

    public AccountCreationService(AccountRepository accountRepository,
            IdempotencySupport idempotencySupport,
            AccountNumberGenerator accountNumberGenerator, AccountCreationResponseSnapshotSerializer responseSerializer,
            TransactionTemplate transactionTemplate, EntityManager entityManager) {
        this.accountRepository = accountRepository;
        this.idempotencySupport = idempotencySupport;
        this.accountNumberGenerator = accountNumberGenerator;
        this.responseSerializer = responseSerializer;
        this.transactionTemplate = transactionTemplate;
        this.entityManager = entityManager;
    }

    public AccountCreationResult create(String idempotencyKey, String document) {
        idempotencySupport.validateKey(idempotencyKey);
        String normalizedDocument = DocumentNormalizer.normalizeAndValidate(document);
        String requestHash = idempotencySupport.hashCanonicalRequest(normalizedDocument);
        AccountCreationResult result = null;

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String accountNumber = accountNumberGenerator.next();
            try {
                result = transactionTemplate.execute(status -> createInTransaction(
                        idempotencyKey, normalizedDocument, requestHash, accountNumber));
                break;
            } catch (DataIntegrityViolationException exception) {
                String constraintName = constraintName(exception);
                if (IDEMPOTENCY_KEY_CONSTRAINT.equals(constraintName)) {
                    var existing = idempotencySupport.findByKey(idempotencyKey);
                    if (existing.isPresent()) {
                        result = replayOrConflict(existing.get(), requestHash);
                        break;
                    }
                    throw exception;
                }
                if (DOCUMENT_CONSTRAINT.equals(constraintName)) {
                    result = transactionTemplate.execute(status -> createInTransaction(
                            idempotencyKey, normalizedDocument, requestHash, accountNumber));
                    break;
                }
                if (!ACCOUNT_NUMBER_CONSTRAINT.equals(constraintName) || attempt == MAX_ATTEMPTS - 1) {
                    throw exception;
                }
            }
        }

        if (result == null) {
            throw new IllegalStateException("N\u00e3o foi poss\u00edvel concluir a abertura da conta.");
        }
        if (result.error() != null) {
            throw new AccountCreationException(result.error(), result.responseBody());
        }
        return result;
    }

    private AccountCreationResult createInTransaction(String idempotencyKey, String document, String requestHash,
            String accountNumber) {
        var existingRequest = idempotencySupport.findByKey(idempotencyKey);
        if (existingRequest.isPresent()) {
            return replayOrConflict(existingRequest.get(), requestHash);
        }

        if (accountRepository.findByDocument(document).isPresent()) {
            return persistError(idempotencyKey, requestHash, AccountCreationError.ACCOUNT_CONFLICT);
        }

        Account account = new Account(accountNumber, document);
        Account persistedAccount = accountRepository.saveAndFlush(account);
        entityManager.refresh(persistedAccount);
        AccountCreationResponseSnapshotSerializer.Snapshot snapshot = responseSerializer.created(
                persistedAccount.getAccountNumber(), BigDecimal.ZERO.setScale(2), persistedAccount.getCreatedAt());
        idempotencySupport.save(SCOPE, idempotencyKey, requestHash, snapshot.status(), snapshot.body());
        return AccountCreationResult.succeeded(snapshot.body());
    }

    private AccountCreationResult replayOrConflict(IdempotencyRecord record, String requestHash) {
        if (!idempotencySupport.matchesRequest(record, requestHash)) {
            return rejected(AccountCreationError.IDEMPOTENCY_CONFLICT);
        }
        if (record.getResponseStatus() == 409) {
            return AccountCreationResult.rejected(AccountCreationError.ACCOUNT_CONFLICT, record.getResponseBody());
        }
        return AccountCreationResult.replayed(record.getResponseStatus(), record.getResponseBody());
    }

    private AccountCreationResult rejected(AccountCreationError error) {
        AccountCreationResponseSnapshotSerializer.Snapshot snapshot = responseSerializer.rejected(error);
        return AccountCreationResult.rejected(error, snapshot.body());
    }

    private AccountCreationResult persistError(String idempotencyKey, String requestHash,
            AccountCreationError error) {
        AccountCreationResponseSnapshotSerializer.Snapshot snapshot = responseSerializer.rejected(error);
        idempotencySupport.save(SCOPE, idempotencyKey, requestHash, snapshot.status(), snapshot.body());
        return AccountCreationResult.rejected(error, snapshot.body());
    }

    private String constraintName(DataIntegrityViolationException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException violation) {
                return violation.getConstraintName();
            }
            cause = cause.getCause();
        }
        return null;
    }

}
