package io.github.ngreiter.accounttransaction.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AccountTransactionTest {

    @Test
    void canBeInstantiatedByJpaUsingProtectedNoArgumentConstructor() throws ReflectiveOperationException {
        var constructor = AccountTransaction.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        AccountTransaction transaction = constructor.newInstance();

        assertNull(transaction.getPostedAt());
        assertNull(ReflectionTestUtils.getField(transaction, "account"));
        assertNull(ReflectionTestUtils.getField(transaction, "financialTransaction"));
        assertNull(ReflectionTestUtils.getField(transaction, "movementType"));
    }

    @Test
    void storesMovementSnapshotAndExposesTimestampAssignedByPersistence() {
        Account account = new Account("12345678-9", "52998224725");
        FinancialTransaction financialTransaction = new FinancialTransaction(TransactionType.WITHDRAWAL,
                new BigDecimal("4.25"));
        BigDecimal amount = new BigDecimal("4.25");
        BigDecimal balanceBefore = new BigDecimal("10.00");
        BigDecimal balanceAfter = new BigDecimal("5.75");
        AccountTransaction transaction = new AccountTransaction(account, financialTransaction, MovementType.DEBIT,
                amount, balanceBefore, balanceAfter, "cash out");
        Instant postedAt = Instant.parse("2026-10-29T12:00:00Z");
        ReflectionTestUtils.setField(transaction, "postedAt", postedAt);

        assertSame(account, ReflectionTestUtils.getField(transaction, "account"));
        assertSame(financialTransaction, ReflectionTestUtils.getField(transaction, "financialTransaction"));
        assertEquals(MovementType.DEBIT, ReflectionTestUtils.getField(transaction, "movementType"));
        assertEquals(amount, ReflectionTestUtils.getField(transaction, "amount"));
        assertEquals(balanceBefore, ReflectionTestUtils.getField(transaction, "balanceBefore"));
        assertEquals(balanceAfter, ReflectionTestUtils.getField(transaction, "balanceAfter"));
        assertEquals("cash out", ReflectionTestUtils.getField(transaction, "description"));
        assertEquals(postedAt, transaction.getPostedAt());
    }
}
