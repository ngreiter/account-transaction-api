package io.github.ngreiter.accounttransaction.transaction.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

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
import io.github.ngreiter.accounttransaction.transaction.api.TransferRequest;
import io.github.ngreiter.accounttransaction.transaction.api.TransferResponse;
import jakarta.persistence.EntityManager;

@Service
public class TransferService {

    private static final String SCOPE = "POST:/api/v1/transactions/transfers";
    private static final int MAX_ATTEMPTS = 3;

    private final AccountRepository accountRepository;
    private final FinancialTransactionRepository financialTransactionRepository;
    private final AccountTransactionRepository accountTransactionRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final TransactionTemplate transactionTemplate;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    public TransferService(AccountRepository accountRepository,
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

    public TransferResult transfer(String idempotencyKey, TransferRequest request) {
        validate(idempotencyKey, request);
        BigDecimal amount = request.amount().setScale(2, RoundingMode.UNNECESSARY);
        String requestHash = hash(request, amount);

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                return transactionTemplate.execute(status -> transferInTransaction(
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
        throw new IllegalStateException("Não foi possível concluir a transferência.");
    }

    private TransferResult transferInTransaction(String idempotencyKey, TransferRequest request,
            BigDecimal amount, String requestHash) {
        var existing = idempotencyRecordRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), requestHash);
        }

        List<Account> lockedAccounts = accountRepository
                .findAllByAccountNumberInOrderByAccountNumberForUpdate(
                        List.of(request.sourceAccountNumber(), request.destinationAccountNumber()));
        Map<String, Account> accountsByNumber = lockedAccounts.stream()
                .collect(Collectors.toMap(Account::getAccountNumber, Function.identity()));
        Account source = accountsByNumber.get(request.sourceAccountNumber());
        Account destination = accountsByNumber.get(request.destinationAccountNumber());
        if (source == null || destination == null) {
            return persistResult(idempotencyKey, requestHash, HttpStatus.NOT_FOUND.value(),
                    new ApiError("RESOURCE_NOT_FOUND", "Recurso não encontrado."), null);
        }

        BigDecimal sourceBalanceBefore = source.getBalance();
        if (sourceBalanceBefore.compareTo(amount) < 0) {
            return persistResult(idempotencyKey, requestHash, HttpStatus.UNPROCESSABLE_CONTENT.value(),
                    new ApiError("INSUFFICIENT_FUNDS", "Saldo insuficiente para concluir a operação."), null);
        }

        BigDecimal destinationBalanceBefore = destination.getBalance();
        BigDecimal sourceBalanceAfter = sourceBalanceBefore.subtract(amount);
        BigDecimal destinationBalanceAfter = destinationBalanceBefore.add(amount);
        source.setBalance(sourceBalanceAfter);
        destination.setBalance(destinationBalanceAfter);

        FinancialTransaction financialTransaction = financialTransactionRepository
                .saveAndFlush(new FinancialTransaction(TransactionType.TRANSFER, amount));
        AccountTransaction debit = accountTransactionRepository.saveAndFlush(new AccountTransaction(source,
                financialTransaction, MovementType.DEBIT, amount, sourceBalanceBefore, sourceBalanceAfter,
                request.description()));
        AccountTransaction credit = accountTransactionRepository.saveAndFlush(new AccountTransaction(destination,
                financialTransaction, MovementType.CREDIT, amount, destinationBalanceBefore, destinationBalanceAfter,
                request.description()));
        entityManager.refresh(debit);
        entityManager.refresh(credit);

        TransferResult response = result(HttpStatus.CREATED.value(),
                new TransferResponse(financialTransaction.getId(), TransactionType.TRANSFER.name(), amount,
                        "SUCCEEDED", debit.getPostedAt()));
        idempotencyRecordRepository.save(new IdempotencyRecord(SCOPE, idempotencyKey, requestHash,
                financialTransaction, response.status(), response.responseBody()));
        return response;
    }

    private TransferResult replayOrConflict(IdempotencyRecord record, String requestHash) {
        if (!record.getRequestHash().equals(requestHash)) {
            return result(HttpStatus.CONFLICT.value(), new ApiError("IDEMPOTENCY_CONFLICT",
                    "A chave de idempotência não pode ser reutilizada com dados diferentes."));
        }
        return new TransferResult(record.getResponseStatus(), record.getResponseBody());
    }

    private TransferResult persistResult(String idempotencyKey, String requestHash, int status, Object body,
            FinancialTransaction financialTransaction) {
        TransferResult result = result(status, body);
        idempotencyRecordRepository.save(new IdempotencyRecord(SCOPE, idempotencyKey, requestHash,
                financialTransaction, result.status(), result.responseBody()));
        return result;
    }

    private TransferResult result(int status, Object body) {
        try {
            return new TransferResult(status, objectMapper.writeValueAsString(body));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Não foi possível serializar a resposta.", exception);
        }
    }

    private void validate(String idempotencyKey, TransferRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 255) {
            throw new IllegalArgumentException("Idempotency-Key inválida.");
        }
        if (request == null) {
            throw new IllegalArgumentException("Dados da transferência inválidos.");
        }
        if (request.sourceAccountNumber() == null || request.destinationAccountNumber() == null
                || request.sourceAccountNumber().equals(request.destinationAccountNumber())) {
            throw new IllegalArgumentException("As contas de origem e destino devem ser diferentes.");
        }
        if (!AccountNumber.isValid(request.sourceAccountNumber())
                || !AccountNumber.isValid(request.destinationAccountNumber())) {
            throw new IllegalArgumentException("Número de conta inválido.");
        }
        if (request.amount() == null || request.amount().signum() <= 0 || request.amount().scale() > 2
                || request.amount().precision() - request.amount().scale() > 17) {
            throw new IllegalArgumentException("Valor da transferência inválido.");
        }
    }

    private String hash(TransferRequest request, BigDecimal amount) {
        String canonical = SCOPE + "\n" + request.sourceAccountNumber() + "\n"
                + request.destinationAccountNumber() + "\n" + amount.toPlainString() + "\n"
                + (request.description() == null ? "<null>" : "<value>" + request.description());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponível.", exception);
        }
    }
}
