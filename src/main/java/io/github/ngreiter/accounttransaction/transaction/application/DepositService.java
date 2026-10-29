package io.github.ngreiter.accounttransaction.transaction.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import io.github.ngreiter.accounttransaction.domain.Account;
import io.github.ngreiter.accounttransaction.domain.AccountNumber;
import io.github.ngreiter.accounttransaction.domain.AccountTransaction;
import io.github.ngreiter.accounttransaction.domain.FinancialTransaction;
import io.github.ngreiter.accounttransaction.domain.IdempotencyRecord;
import io.github.ngreiter.accounttransaction.domain.MovementType;
import io.github.ngreiter.accounttransaction.domain.TransactionType;
import io.github.ngreiter.accounttransaction.persistence.AccountRepository;
import io.github.ngreiter.accounttransaction.persistence.AccountTransactionRepository;
import io.github.ngreiter.accounttransaction.persistence.FinancialTransactionRepository;
import io.github.ngreiter.accounttransaction.persistence.IdempotencyRecordRepository;
import io.github.ngreiter.accounttransaction.shared.api.ApiError;
import io.github.ngreiter.accounttransaction.transaction.api.DepositRequest;
import io.github.ngreiter.accounttransaction.transaction.api.DepositResponse;
import jakarta.persistence.EntityManager;

@Service
public class DepositService {

    private static final String SCOPE = "POST:/api/v1/transactions/deposits";
    private static final int MAX_ATTEMPTS = 3;

    private final AccountRepository accountRepository;
    private final FinancialTransactionRepository financialTransactionRepository;
    private final AccountTransactionRepository accountTransactionRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final TransactionTemplate transactionTemplate;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    public DepositService(AccountRepository accountRepository,
            FinancialTransactionRepository financialTransactionRepository,
            AccountTransactionRepository accountTransactionRepository,
            IdempotencyRecordRepository idempotencyRecordRepository,
            TransactionTemplate transactionTemplate, EntityManager entityManager, ObjectMapper objectMapper) {
        this.accountRepository = accountRepository;
        this.financialTransactionRepository = financialTransactionRepository;
        this.accountTransactionRepository = accountTransactionRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.transactionTemplate = transactionTemplate;
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
    }

    public DepositResult deposit(String idempotencyKey, DepositRequest request) {
        validate(idempotencyKey, request);
        BigDecimal amount = request.amount().setScale(2, RoundingMode.UNNECESSARY);
        String requestHash = hash(request.accountNumber(), amount, request.description());

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                return transactionTemplate.execute(status -> depositInTransaction(
                        idempotencyKey, request, amount, requestHash));
            } catch (DataIntegrityViolationException exception) {
                var saved = idempotencyRecordRepository.findByIdempotencyKey(idempotencyKey);
                if (saved.isPresent()) {
                    return replayOrConflict(saved.get(), requestHash);
                }
                if (attempt == MAX_ATTEMPTS - 1) {
                    throw exception;
                }
            }
        }
        throw new IllegalStateException("Não foi possível concluir o depósito.");
    }

    private DepositResult depositInTransaction(String idempotencyKey, DepositRequest request,
            BigDecimal amount, String requestHash) {
        var existing = idempotencyRecordRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), requestHash);
        }

        List<Account> lockedAccounts = accountRepository
                .findAllByAccountNumberInOrderByAccountNumberForUpdate(List.of(request.accountNumber()));
        if (lockedAccounts.isEmpty()) {
            return persistResult(idempotencyKey, requestHash, HttpStatus.NOT_FOUND.value(),
                    new ApiError("RESOURCE_NOT_FOUND", "Recurso não encontrado."), null);
        }

        Account account = lockedAccounts.getFirst();
        BigDecimal balanceBefore = account.getBalance();
        BigDecimal balanceAfter = balanceBefore.add(amount);
        account.setBalance(balanceAfter);

        FinancialTransaction financialTransaction = financialTransactionRepository
                .saveAndFlush(new FinancialTransaction(TransactionType.DEPOSIT, amount));
        AccountTransaction accountTransaction = accountTransactionRepository.saveAndFlush(
                new AccountTransaction(account, financialTransaction, MovementType.CREDIT,
                        amount, balanceBefore, balanceAfter, request.description()));
        entityManager.refresh(accountTransaction);

        DepositResult response = result(HttpStatus.CREATED.value(),
                new DepositResponse(financialTransaction.getId(), TransactionType.DEPOSIT.name(), amount,
                        "SUCCEEDED", accountTransaction.getPostedAt()));
        idempotencyRecordRepository.save(new IdempotencyRecord(SCOPE, idempotencyKey, requestHash,
                financialTransaction, response.status(), response.responseBody()));
        return response;
    }

    private DepositResult replayOrConflict(IdempotencyRecord record, String requestHash) {
        if (!record.getRequestHash().equals(requestHash)) {
            return result(HttpStatus.CONFLICT.value(), new ApiError("IDEMPOTENCY_CONFLICT",
                    "A chave de idempotência não pode ser reutilizada com dados diferentes."));
        }
        return new DepositResult(record.getResponseStatus(), record.getResponseBody());
    }

    private DepositResult persistResult(String idempotencyKey, String requestHash, int status, Object body,
            FinancialTransaction financialTransaction) {
        DepositResult result = result(status, body);
        idempotencyRecordRepository.save(new IdempotencyRecord(SCOPE, idempotencyKey, requestHash,
                financialTransaction, result.status(), result.responseBody()));
        return result;
    }

    private DepositResult result(int status, Object body) {
        try {
            return new DepositResult(status, objectMapper.writeValueAsString(body));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Não foi possível serializar a resposta.", exception);
        }
    }

    private void validate(String idempotencyKey, DepositRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 255) {
            throw new IllegalArgumentException("Idempotency-Key inválida.");
        }
        if (request == null) {
            throw new IllegalArgumentException("Dados do depósito inválidos.");
        }
        if (!AccountNumber.isValid(request.accountNumber())) {
            throw new IllegalArgumentException("Número de conta inválido.");
        }
        if (request.amount() == null || request.amount().signum() <= 0 || request.amount().scale() > 2
                || request.amount().precision() - request.amount().scale() > 17) {
            throw new IllegalArgumentException("Valor do depósito inválido.");
        }
    }

    private String hash(String accountNumber, BigDecimal amount, String description) {
        String canonical = accountNumber + "\n" + amount.toPlainString() + "\n"
                + (description == null ? "<null>" : "<value>" + description);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponível.", exception);
        }
    }
}
