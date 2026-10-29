package io.github.ngreiter.accounttransaction.transaction.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

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
public class DepositService {

    private static final String SCOPE = "POST:/api/v1/transactions/deposits";
    private static final int MAX_ATTEMPTS = 3;

    private final AccountRepository accountRepository;
    private final FinancialTransactionRepository financialTransactionRepository;
    private final AccountTransactionRepository accountTransactionRepository;
    private final IdempotencySupport idempotencySupport;
    private final TransactionTemplate transactionTemplate;
    private final EntityManager entityManager;
    private final TransactionResponseSnapshotSerializer responseSerializer;

    public DepositService(AccountRepository accountRepository,
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

    public DepositResult deposit(String idempotencyKey, DepositCommand command) {
        validate(idempotencyKey, command);
        BigDecimal amount = command.amount().setScale(2, RoundingMode.UNNECESSARY);
        String requestHash = hash(command.accountNumber(), amount, command.description());
        DepositResult result = null;

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                result = transactionTemplate.execute(status -> depositInTransaction(
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
            throw new IllegalStateException("N\u00e3o foi poss\u00edvel concluir o dep\u00f3sito.");
        }
        if (result.error() != null) {
            throw new TransactionApplicationException(result.error(), result.responseBody());
        }
        return result;
    }

    private DepositResult depositInTransaction(String idempotencyKey, DepositCommand command,
            BigDecimal amount, String requestHash) {
        var existing = idempotencySupport.findByKey(idempotencyKey);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), requestHash);
        }

        List<Account> lockedAccounts = accountRepository
                .findAllByAccountNumberInOrderByAccountNumberForUpdate(List.of(command.accountNumber()));
        if (lockedAccounts.isEmpty()) {
            return persistError(idempotencyKey, requestHash, TransactionError.RESOURCE_NOT_FOUND);
        }

        Account account = lockedAccounts.getFirst();
        BigDecimal balanceBefore = account.getBalance();
        account.credit(amount);
        BigDecimal balanceAfter = account.getBalance();

        FinancialTransaction financialTransaction = financialTransactionRepository
                .saveAndFlush(new FinancialTransaction(TransactionType.DEPOSIT, amount));
        AccountTransaction accountTransaction = accountTransactionRepository.saveAndFlush(
                new AccountTransaction(account, financialTransaction, MovementType.CREDIT,
                        amount, balanceBefore, balanceAfter, command.description()));
        entityManager.refresh(accountTransaction);

        TransactionResponseSnapshotSerializer.Snapshot snapshot = responseSerializer.created(
                financialTransaction.getId(), TransactionType.DEPOSIT.name(), amount,
                "SUCCEEDED", accountTransaction.getPostedAt());
        idempotencySupport.save(SCOPE, idempotencyKey, requestHash,
                financialTransaction, snapshot.status(), snapshot.body());
        return DepositResult.succeeded(snapshot.body());
    }

    private DepositResult replayOrConflict(IdempotencyRecord record, String requestHash) {
        if (!idempotencySupport.matchesRequest(record, requestHash)) {
            return rejected(TransactionError.IDEMPOTENCY_CONFLICT);
        }
        TransactionError storedError = TransactionError.fromStoredStatus(record.getResponseStatus());
        if (storedError != null) {
            return DepositResult.rejected(storedError, record.getResponseBody());
        }
        return DepositResult.replayed(record.getResponseStatus(), record.getResponseBody());
    }

    private DepositResult rejected(TransactionError error) {
        TransactionResponseSnapshotSerializer.Snapshot snapshot = responseSerializer.rejected(error);
        return DepositResult.rejected(error, snapshot.body());
    }

    private DepositResult persistError(String idempotencyKey, String requestHash, TransactionError error) {
        TransactionResponseSnapshotSerializer.Snapshot snapshot = responseSerializer.rejected(error);
        idempotencySupport.save(SCOPE, idempotencyKey, requestHash, snapshot.status(), snapshot.body());
        return DepositResult.rejected(error, snapshot.body());
    }

    private void validate(String idempotencyKey, DepositCommand command) {
        idempotencySupport.validateKey(idempotencyKey);
        if (command == null) {
            throw new IllegalArgumentException("Dados do depósito inválidos.");
        }
        if (!AccountNumber.isValid(command.accountNumber())) {
            throw new IllegalArgumentException("Número de conta inválido.");
        }
        if (command.amount() == null || command.amount().signum() <= 0 || command.amount().scale() > 2
                || command.amount().precision() - command.amount().scale() > 17) {
            throw new IllegalArgumentException("Valor do depósito inválido.");
        }
    }

    private String hash(String accountNumber, BigDecimal amount, String description) {
        String canonical = accountNumber + "\n" + amount.toPlainString() + "\n"
                + (description == null ? "<null>" : "<value>" + description);
        return idempotencySupport.hashCanonicalRequest(canonical);
    }
}
