package io.github.ngreiter.accounttransaction.statement.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import io.github.ngreiter.accounttransaction.account.application.AccountNotFoundException;
import io.github.ngreiter.accounttransaction.domain.MovementType;
import io.github.ngreiter.accounttransaction.domain.TransactionType;
import io.github.ngreiter.accounttransaction.persistence.AccountRepository;
import io.github.ngreiter.accounttransaction.persistence.AccountTransactionRepository;
import io.github.ngreiter.accounttransaction.persistence.AccountTransactionStatementRow;
import io.github.ngreiter.accounttransaction.statement.api.StatementResponse;

class AccountStatementServiceTest {

    private static final String ACCOUNT_NUMBER = "12345678-9";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Sao_Paulo");
    private AccountRepository accountRepository;
    private AccountTransactionRepository accountTransactionRepository;
    private AccountStatementService service;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        accountTransactionRepository = mock(AccountTransactionRepository.class);
        service = new AccountStatementService(accountRepository, accountTransactionRepository);
        when(accountRepository.existsById(ACCOUNT_NUMBER)).thenReturn(true);
    }

    @Test
    void queriesInclusiveDatePeriodAndReturnsEntriesInChronologicalOrder() {
        Instant fromInclusive = LocalDate.parse("2026-06-10").atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant toExclusive = LocalDate.parse("2026-06-13").atStartOfDay(BUSINESS_ZONE).toInstant();
        AccountTransactionStatementRow first = entry("2026-06-10T14:00:00Z", "25.50", MovementType.CREDIT);
        AccountTransactionStatementRow second = entry("2026-06-11T10:00:00Z", "5.00", MovementType.DEBIT);
        AccountTransactionStatementRow third = entry("2026-06-12T19:30:00Z", "10.00", MovementType.CREDIT);
        when(accountTransactionRepository.calculateNetAmountBefore(ACCOUNT_NUMBER, fromInclusive, MovementType.CREDIT))
                .thenReturn(new BigDecimal("100.00"));
        when(accountTransactionRepository.calculateNetAmountBetween(ACCOUNT_NUMBER, fromInclusive, toExclusive,
                MovementType.CREDIT)).thenReturn(new BigDecimal("30.50"));
        when(accountTransactionRepository.findStatementEntries(ACCOUNT_NUMBER, fromInclusive, toExclusive,
                PageRequest.of(0, 10))).thenReturn(new PageImpl<>(List.of(first, second, third),
                        PageRequest.of(0, 10), 3));

        StatementResponse response = service.getStatement(ACCOUNT_NUMBER, "2026-06-10", "2026-06-12", "1", "10");

        assertEquals(LocalDate.parse("2026-06-10"), response.from());
        assertEquals(LocalDate.parse("2026-06-12"), response.to());
        assertEquals(new BigDecimal("100.00"), response.openingBalance());
        assertEquals(new BigDecimal("130.50"), response.closingBalance());
        assertEquals(3, response.entries().size());
        assertEquals(List.of(first.getPostedAt(), second.getPostedAt(), third.getPostedAt()),
                response.entries().stream().map(entry -> entry.postedAt()).toList());
        assertEquals(List.of(MovementType.CREDIT, MovementType.DEBIT, MovementType.CREDIT),
                response.entries().stream().map(entry -> entry.movementType()).toList());
        verify(accountTransactionRepository).findStatementEntries(ACCOUNT_NUMBER, fromInclusive, toExclusive,
                PageRequest.of(0, 10));
    }

    @Test
    void returnsLastPageWithFewerEntriesThanRequestedPageSize() {
        Instant fromInclusive = LocalDate.parse("2026-07-01").atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant toExclusive = LocalDate.parse("2026-07-02").atStartOfDay(BUSINESS_ZONE).toInstant();
        AccountTransactionStatementRow fourth = entry("2026-07-01T15:00:00Z", "4.00", MovementType.CREDIT);
        AccountTransactionStatementRow fifth = entry("2026-07-01T16:00:00Z", "2.00", MovementType.DEBIT);
        PageRequest lastPage = PageRequest.of(1, 3);
        when(accountTransactionRepository.findStatementEntries(ACCOUNT_NUMBER, fromInclusive, toExclusive, lastPage))
                .thenReturn(new PageImpl<>(List.of(fourth, fifth), lastPage, 5));

        StatementResponse response = service.getStatement(ACCOUNT_NUMBER, "2026-07-01", "2026-07-01", "2", "3");

        assertEquals(2, response.page());
        assertEquals(3, response.size());
        assertEquals(2, response.numberOfElements());
        assertEquals(5, response.totalEntries());
        assertEquals(List.of(fourth.getPostedAt(), fifth.getPostedAt()),
                response.entries().stream().map(entry -> entry.postedAt()).toList());
        verify(accountTransactionRepository).findStatementEntries(ACCOUNT_NUMBER, fromInclusive, toExclusive,
                PageRequest.of(1, 3));
    }

    @Test
    void rejectsMissingMalformedAndImpossibleDates() {
        assertThrows(IllegalArgumentException.class,
                () -> service.getStatement(ACCOUNT_NUMBER, null, "2026-06-12", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.getStatement(ACCOUNT_NUMBER, "2026/06/10", "2026-06-12", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.getStatement(ACCOUNT_NUMBER, "2026-02-30", "2026-06-12", null, null));

        verify(accountRepository, never()).existsById(ACCOUNT_NUMBER);
    }

    @Test
    void rejectsPeriodStartingAfterItsEnd() {
        assertThrows(IllegalArgumentException.class,
                () -> service.getStatement(ACCOUNT_NUMBER, "2026-06-13", "2026-06-12", null, null));

        verify(accountRepository, never()).existsById(ACCOUNT_NUMBER);
    }

    @Test
    void rejectsMalformedNonPositiveAndOversizedPagination() {
        assertThrows(IllegalArgumentException.class,
                () -> service.getStatement(ACCOUNT_NUMBER, "2026-06-10", "2026-06-12", "x", "10"));
        assertThrows(IllegalArgumentException.class,
                () -> service.getStatement(ACCOUNT_NUMBER, "2026-06-10", "2026-06-12", "0", "10"));
        assertThrows(IllegalArgumentException.class,
                () -> service.getStatement(ACCOUNT_NUMBER, "2026-06-10", "2026-06-12", "1", "x"));
        assertThrows(IllegalArgumentException.class,
                () -> service.getStatement(ACCOUNT_NUMBER, "2026-06-10", "2026-06-12", "1", "0"));
        assertThrows(IllegalArgumentException.class,
                () -> service.getStatement(ACCOUNT_NUMBER, "2026-06-10", "2026-06-12", "1", "101"));

        verify(accountRepository, never()).existsById(ACCOUNT_NUMBER);
    }

    @Test
    void usesDefaultPaginationAndTreatsNullBalancesAndEntryAmountsAsZero() {
        Instant fromInclusive = LocalDate.parse("2026-08-01").atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant toExclusive = LocalDate.parse("2026-08-02").atStartOfDay(BUSINESS_ZONE).toInstant();
        AccountTransactionStatementRow row = entry("2026-08-01T12:00:00Z", null, MovementType.CREDIT);
        PageRequest defaultPage = PageRequest.of(0, 20);
        when(accountTransactionRepository.findStatementEntries(ACCOUNT_NUMBER, fromInclusive, toExclusive,
                defaultPage)).thenReturn(new PageImpl<>(List.of(row), defaultPage, 1));

        StatementResponse response = service.getStatement(ACCOUNT_NUMBER, "2026-08-01", "2026-08-01", null, null);

        assertEquals(1, response.page());
        assertEquals(20, response.size());
        assertEquals(BigDecimal.ZERO.setScale(2), response.openingBalance());
        assertEquals(BigDecimal.ZERO.setScale(2), response.closingBalance());
        assertEquals(BigDecimal.ZERO.setScale(2), response.entries().getFirst().amount());
        verify(accountTransactionRepository).findStatementEntries(ACCOUNT_NUMBER, fromInclusive, toExclusive,
                defaultPage);
    }

    @Test
    void throwsWhenAccountDoesNotExist() {
        when(accountRepository.existsById("87654321-2")).thenReturn(false);

        assertThrows(AccountNotFoundException.class,
                () -> service.getStatement("87654321-2", "2026-06-10", "2026-06-12", null, null));

        verifyNoInteractions(accountTransactionRepository);
    }

    private AccountTransactionStatementRow entry(String postedAt, String amount, MovementType movementType) {
        AccountTransactionStatementRow row = mock(AccountTransactionStatementRow.class);
        when(row.getFinancialTransactionId()).thenReturn(UUID.randomUUID());
        when(row.getTransactionType()).thenReturn(TransactionType.DEPOSIT);
        when(row.getMovementType()).thenReturn(movementType);
        if (amount != null) {
            when(row.getAmount()).thenReturn(new BigDecimal(amount));
        }
        when(row.getDescription()).thenReturn("movement");
        when(row.getPostedAt()).thenReturn(Instant.parse(postedAt));
        return row;
    }
}
