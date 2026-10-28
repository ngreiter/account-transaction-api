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
            try {
                return transactionTemplate.execute(status -> createInTransaction(
                        idempotencyKey, normalizedDocument, requestHash));
            } catch (DataIntegrityViolationException exception) {
                if (attempt == MAX_ATTEMPTS - 1) {
                    throw new AccountCreationException(HttpStatus.CONFLICT, "ACCOUNT_CONFLICT",
                            "Não foi possível abrir a conta com os dados informados.");
                }
            }
        }
        throw new IllegalStateException("Não foi possível concluir a abertura da conta.");
    }

    private AccountCreationResult createInTransaction(String idempotencyKey, String document, String requestHash) {
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

        Account account = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String accountNumber = accountNumberGenerator.next();
            if (!accountRepository.existsById(accountNumber)) {
                account = new Account(accountNumber, document);
                break;
            }
        }
        if (account == null) {
            throw new AccountCreationException(HttpStatus.CONFLICT, "ACCOUNT_CONFLICT",
                    "Não foi possível abrir a conta com os dados informados.");
        }

        accountRepository.saveAndFlush(account);
        entityManager.refresh(account);
        AccountCreationResult response = result(HttpStatus.CREATED.value(),
                new CreateAccountResponse(account.getAccountNumber(), BigDecimal.ZERO.setScale(2), account.getCreatedAt()));
        idempotencyRecordRepository.save(new IdempotencyRecord(SCOPE, idempotencyKey, requestHash,
                response.status(), response.responseBody()));
        return response;
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
