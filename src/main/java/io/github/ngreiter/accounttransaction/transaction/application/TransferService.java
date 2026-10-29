package io.github.ngreiter.accounttransaction.transaction.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;


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
import io.github.ngreiter.accounttransaction.shared.application.IdempotencySupport;
import jakarta.persistence.EntityManager;

@Service
public class TransferService {

    private static final String SCOPE = "POST:/api/v1/transactions/transfers";
    private static final int MAX_ATTEMPTS = 3;

    private final AccountRepository accountRepository;
    private final FinancialTransactionRepository financialTransactionRepository;
    private final AccountTransactionRepository accountTransactionRepository;
    private final IdempotencySupport idempotencySupport;
    private final TransactionTemplate transactionTemplate;
    private final EntityManager entityManager;
    private final TransactionResponseSnapshotSerializer responseSerializer;

    public TransferService(AccountRepository accountRepository,
            FinancialTransactionRepository financialTransactionRepository,
            AccountTransactionRepository accountTransactionRepository,
            IdempotencySupport idempotencySupport,
            TransactionTemplate transactionTemplate, EntityManager entityManager,
            TransactionResponseSnapshotSerializer responseSerializer) {
        this.accountRepository = accountRepository;
        this.financialTransactionRepository = financialTransactionRepository;
        this.accountTransactionRepository = accountTransactionRepository;
        this.idempotencySupport = idempotencySupport;
        this.transactionTemplate = transactionTemplate;
        this.entityManager = entityManager;
        this.responseSerializer = responseSerializer;
    }

    public TransferResult transfer(String idempotencyKey, TransferCommand command) {
        validate(idempotencyKey, command);
        BigDecimal amount = command.amount().setScale(2, RoundingMode.UNNECESSARY);
        String requestHash = hash(command, amount);
        TransferResult result = null;

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                result = transactionTemplate.execute(status -> transferInTransaction(
                        idempotencyKey, command, amount, requestHash));
                break;
            } catch (DataIntegrityViolationException exception) {
                var saved = idempotencySupport.findByKey(idempotencyKey);
                if (saved.isPresent()) {
                    result = replayOrConflict(saved.get(), requestHash);
                    break;
                }
                if (attempt == MAX_ATTEMPTS - 1) {
                    throw exception;
                }
            }
        }
        if (result == null) {
            throw new IllegalStateException("N\u00e3o foi poss\u00edvel concluir a transfer\u00eancia.");
        }
        if (result.error() != null) {
            throw new TransactionApplicationException(result.error(), result.responseBody());
        }
        return result;
    }

    private TransferResult transferInTransaction(String idempotencyKey, TransferCommand command,
            BigDecimal amount, String requestHash) {
        var existing = idempotencySupport.findByKey(idempotencyKey);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), requestHash);
        }

        List<Account> lockedAccounts = accountRepository
                .findAllByAccountNumberInOrderByAccountNumberForUpdate(
                        List.of(command.sourceAccountNumber(), command.destinationAccountNumber()));
        Map<String, Account> accountsByNumber = lockedAccounts.stream()
                .collect(Collectors.toMap(Account::getAccountNumber, Function.identity()));
        Account source = accountsByNumber.get(command.sourceAccountNumber());
        Account destination = accountsByNumber.get(command.destinationAccountNumber());
        if (source == null || destination == null) {
            return persistError(idempotencyKey, requestHash, TransactionError.RESOURCE_NOT_FOUND);
        }

        BigDecimal sourceBalanceBefore = source.getBalance();
        if (sourceBalanceBefore.compareTo(amount) < 0) {
            return persistError(idempotencyKey, requestHash, TransactionError.INSUFFICIENT_FUNDS);
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
                command.description()));
        AccountTransaction credit = accountTransactionRepository.saveAndFlush(new AccountTransaction(destination,
                financialTransaction, MovementType.CREDIT, amount, destinationBalanceBefore, destinationBalanceAfter,
                command.description()));
        entityManager.refresh(debit);
        entityManager.refresh(credit);

        TransactionResponseSnapshotSerializer.Snapshot snapshot = responseSerializer.created(
                financialTransaction.getId(), TransactionType.TRANSFER.name(), amount,
                "SUCCEEDED", debit.getPostedAt());
        idempotencySupport.save(SCOPE, idempotencyKey, requestHash,
                financialTransaction, snapshot.status(), snapshot.body());
        return TransferResult.succeeded(snapshot.body());
    }

    private TransferResult replayOrConflict(IdempotencyRecord record, String requestHash) {
        if (!idempotencySupport.matchesRequest(record, requestHash)) {
            return rejected(TransactionError.IDEMPOTENCY_CONFLICT);
        }
        TransactionError storedError = TransactionError.fromStoredStatus(record.getResponseStatus());
        if (storedError != null) {
            return TransferResult.rejected(storedError, record.getResponseBody());
        }
        return TransferResult.replayed(record.getResponseStatus(), record.getResponseBody());
    }

    private TransferResult rejected(TransactionError error) {
        TransactionResponseSnapshotSerializer.Snapshot snapshot = responseSerializer.rejected(error);
        return TransferResult.rejected(error, snapshot.body());
    }

    private TransferResult persistError(String idempotencyKey, String requestHash, TransactionError error) {
        TransactionResponseSnapshotSerializer.Snapshot snapshot = responseSerializer.rejected(error);
        idempotencySupport.save(SCOPE, idempotencyKey, requestHash, snapshot.status(), snapshot.body());
        return TransferResult.rejected(error, snapshot.body());
    }

    private void validate(String idempotencyKey, TransferCommand command) {
        idempotencySupport.validateKey(idempotencyKey);
        if (command == null) {
            throw new IllegalArgumentException("Dados da transferência inválidos.");
        }
        if (command.sourceAccountNumber() == null || command.destinationAccountNumber() == null
                || command.sourceAccountNumber().equals(command.destinationAccountNumber())) {
            throw new IllegalArgumentException("As contas de origem e destino devem ser diferentes.");
        }
        if (!AccountNumber.isValid(command.sourceAccountNumber())
                || !AccountNumber.isValid(command.destinationAccountNumber())) {
            throw new IllegalArgumentException("Número de conta inválido.");
        }
        if (command.amount() == null || command.amount().signum() <= 0 || command.amount().scale() > 2
                || command.amount().precision() - command.amount().scale() > 17) {
            throw new IllegalArgumentException("Valor da transferência inválido.");
        }
    }

    private String hash(TransferCommand command, BigDecimal amount) {
        String canonical = SCOPE + "\n" + command.sourceAccountNumber() + "\n"
                + command.destinationAccountNumber() + "\n" + amount.toPlainString() + "\n"
                + (command.description() == null ? "<null>" : "<value>" + command.description());
        return idempotencySupport.hashCanonicalRequest(canonical);
    }
}
