package io.github.ngreiter.accounttransaction.account.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.hibernate.exception.ConstraintViolationException;

import jakarta.persistence.EntityManager;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import io.github.ngreiter.accounttransaction.account.api.CreateAccountResponse;
import io.github.ngreiter.accounttransaction.domain.Account;
import io.github.ngreiter.accounttransaction.domain.IdempotencyRecord;
import io.github.ngreiter.accounttransaction.persistence.AccountRepository;
import io.github.ngreiter.accounttransaction.persistence.IdempotencyRecordRepository;
import io.github.ngreiter.accounttransaction.shared.api.ApiError;

@Service
public class AccountCreationService {

    private static final String SCOPE = "POST:/api/v1/accounts";
    private static final int MAX_ATTEMPTS = 3;
    private static final String ACCOUNT_NUMBER_CONSTRAINT = "account_pkey";
    private static final String DOCUMENT_CONSTRAINT = "uq_account_document";
    private static final String IDEMPOTENCY_KEY_CONSTRAINT = "uq_idempotency_record_key";

    private final AccountRepository accountRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final AccountNumberGenerator accountNumberGenerator;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final EntityManager entityManager;

    public AccountCreationService(AccountRepository accountRepository,
            IdempotencyRecordRepository idempotencyRecordRepository,
            AccountNumberGenerator accountNumberGenerator, ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate, EntityManager entityManager) {
        this.accountRepository = accountRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.accountNumberGenerator = accountNumberGenerator;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.entityManager = entityManager;
    }

    public AccountCreationResult create(String idempotencyKey, String document) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 255) {
            throw new IllegalArgumentException("Idempotency-Key inválida.");
        }
        String normalizedDocument = DocumentNormalizer.normalizeAndValidate(document);
        String requestHash = sha256(normalizedDocument);

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String accountNumber = accountNumberGenerator.next();
            try {
                return transactionTemplate.execute(status -> createInTransaction(
                        idempotencyKey, normalizedDocument, requestHash, accountNumber));
            } catch (DataIntegrityViolationException exception) {
                String constraintName = constraintName(exception);
                if (IDEMPOTENCY_KEY_CONSTRAINT.equals(constraintName)) {
                    var existing = idempotencyRecordRepository.findByIdempotencyKey(idempotencyKey);
                    if (existing.isPresent()) {
                        return replayOrConflict(existing.get(), requestHash);
                    }
                    throw exception;
                }
                if (DOCUMENT_CONSTRAINT.equals(constraintName)) {
                    return transactionTemplate.execute(status -> createInTransaction(
                            idempotencyKey, normalizedDocument, requestHash, accountNumber));
                }
                if (!ACCOUNT_NUMBER_CONSTRAINT.equals(constraintName) || attempt == MAX_ATTEMPTS - 1) {
                    throw exception;
                }
            }
        }
        throw new IllegalStateException("Não foi possível concluir a abertura da conta.");
    }

    private AccountCreationResult createInTransaction(String idempotencyKey, String document, String requestHash,
            String accountNumber) {
        var existingRequest = idempotencyRecordRepository.findByIdempotencyKey(idempotencyKey);
        if (existingRequest.isPresent()) {
            IdempotencyRecord record = existingRequest.get();
            if (!record.getRequestHash().equals(requestHash)) {
                return result(HttpStatus.CONFLICT.value(),
                        new ApiError("IDEMPOTENCY_CONFLICT",
                                "A chave de idempotência não pode ser reutilizada com dados diferentes."));
            }
            return new AccountCreationResult(record.getResponseStatus(), record.getResponseBody());
        }

        if (accountRepository.findByDocument(document).isPresent()) {
            return persistResult(idempotencyKey, requestHash, HttpStatus.CONFLICT.value(),
                    new ApiError("ACCOUNT_CONFLICT", "Não foi possível abrir a conta com os dados informados."));
        }

        Account account = new Account(accountNumber, document);
        accountRepository.saveAndFlush(account);
        entityManager.refresh(account);
        AccountCreationResult response = result(HttpStatus.CREATED.value(),
                new CreateAccountResponse(account.getAccountNumber(), BigDecimal.ZERO.setScale(2), account.getCreatedAt()));
        idempotencyRecordRepository.save(new IdempotencyRecord(SCOPE, idempotencyKey, requestHash,
                response.status(), response.responseBody()));
        return response;
    }

    private AccountCreationResult replayOrConflict(IdempotencyRecord record, String requestHash) {
        if (!record.getRequestHash().equals(requestHash)) {
            return result(HttpStatus.CONFLICT.value(),
                    new ApiError("IDEMPOTENCY_CONFLICT",
                            "A chave de idempot\u00eancia n\u00e3o pode ser reutilizada com dados diferentes."));
        }
        return new AccountCreationResult(record.getResponseStatus(), record.getResponseBody());
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

    private AccountCreationResult persistResult(String idempotencyKey, String requestHash, int status, Object body) {
        AccountCreationResult result = result(status, body);
        idempotencyRecordRepository.save(new IdempotencyRecord(SCOPE, idempotencyKey, requestHash,
                result.status(), result.responseBody()));
        return result;
    }

    private AccountCreationResult result(int status, Object body) {
        try {
            return new AccountCreationResult(status, objectMapper.writeValueAsString(body));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Não foi possível serializar a resposta.", exception);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponível.", exception);
        }
    }
}
