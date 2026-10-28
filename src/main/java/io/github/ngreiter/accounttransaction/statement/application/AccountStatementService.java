package io.github.ngreiter.accounttransaction.statement.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import io.github.ngreiter.accounttransaction.account.application.AccountNotFoundException;
import io.github.ngreiter.accounttransaction.domain.MovementType;
import io.github.ngreiter.accounttransaction.persistence.AccountRepository;
import io.github.ngreiter.accounttransaction.persistence.AccountTransactionRepository;
import io.github.ngreiter.accounttransaction.persistence.AccountTransactionStatementRow;
import io.github.ngreiter.accounttransaction.statement.api.StatementEntryResponse;
import io.github.ngreiter.accounttransaction.statement.api.StatementResponse;

@Service
public class AccountStatementService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Sao_Paulo");
    private static final Pattern ACCOUNT_NUMBER_PATTERN = Pattern.compile("[0-9]{8}-[0-9]");
    private static final Pattern DATE_PATTERN = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");

    private final AccountRepository accountRepository;
    private final AccountTransactionRepository accountTransactionRepository;

    public AccountStatementService(AccountRepository accountRepository,
            AccountTransactionRepository accountTransactionRepository) {
        this.accountRepository = accountRepository;
        this.accountTransactionRepository = accountTransactionRepository;
    }

    public StatementResponse getStatement(String accountNumber, String fromText, String toText,
            String pageText, String sizeText) {
        validateAccountNumber(accountNumber);
        LocalDate from = parseDate(fromText, "from");
        LocalDate to = parseDate(toText, "to");
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("O início do período deve ser anterior ou igual ao fim.");
        }
        int pageNumber = parsePage(pageText);
        int pageSize = parseSize(sizeText);

        if (!accountRepository.existsById(accountNumber)) {
            throw new AccountNotFoundException();
        }

        Instant fromInclusive = from.atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant toExclusive;
        try {
            toExclusive = to.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("Data final inválida.");
        }

        BigDecimal openingBalance = money(accountTransactionRepository.calculateNetAmountBefore(
                accountNumber, fromInclusive, MovementType.CREDIT));
        BigDecimal periodNet = money(accountTransactionRepository.calculateNetAmountBetween(
                accountNumber, fromInclusive, toExclusive, MovementType.CREDIT));
        BigDecimal closingBalance = openingBalance.add(periodNet).setScale(2, RoundingMode.UNNECESSARY);

        Page<AccountTransactionStatementRow> entries = accountTransactionRepository.findStatementEntries(
                accountNumber, fromInclusive, toExclusive, PageRequest.of(pageNumber - 1, pageSize));
        List<StatementEntryResponse> entryResponses = entries.getContent().stream()
                .map(this::toResponse)
                .toList();

        return new StatementResponse(accountNumber, from, to, openingBalance, entryResponses, closingBalance,
                pageNumber, pageSize, entries.getNumberOfElements(), entries.getTotalElements());
    }

    private StatementEntryResponse toResponse(AccountTransactionStatementRow entry) {
        return new StatementEntryResponse(entry.getFinancialTransactionId(), entry.getTransactionType(),
                entry.getMovementType(), money(entry.getAmount()), entry.getDescription(), entry.getPostedAt());
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.UNNECESSARY);
    }

    private LocalDate parseDate(String value, String parameterName) {
        if (value == null || !DATE_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("O parâmetro " + parameterName + " deve usar YYYY-MM-DD.");
        }
        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("O parâmetro " + parameterName + " deve usar YYYY-MM-DD.");
        }
    }

    private int parsePage(String value) {
        int page = parsePositiveInteger(value, 1, "page");
        return page;
    }

    private int parseSize(String value) {
        int size = parsePositiveInteger(value, 20, "size");
        if (size > 100) {
            throw new IllegalArgumentException("O parâmetro size deve estar entre 1 e 100.");
        }
        return size;
    }

    private int parsePositiveInteger(String value, int defaultValue, String parameterName) {
        if (value == null) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1) {
                throw new IllegalArgumentException("O parâmetro " + parameterName + " deve ser maior que zero.");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("O parâmetro " + parameterName + " deve ser um inteiro positivo.");
        }
    }

    private void validateAccountNumber(String accountNumber) {
        if (accountNumber == null || !ACCOUNT_NUMBER_PATTERN.matcher(accountNumber).matches()) {
            throw new IllegalArgumentException("Número de conta inválido.");
        }
        String base = accountNumber.substring(0, 8);
        int sum = 0;
        int weight = 2;
        for (int i = base.length() - 1; i >= 0; i--) {
            sum += (base.charAt(i) - '0') * weight;
            weight = weight == 9 ? 2 : weight + 1;
        }
        int expectedDigit = 11 - sum % 11;
        if (expectedDigit >= 10) {
            expectedDigit = 0;
        }
        if (accountNumber.charAt(9) - '0' != expectedDigit) {
            throw new IllegalArgumentException("Número de conta inválido.");
        }
    }
}
