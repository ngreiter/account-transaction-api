package io.github.ngreiter.accounttransaction.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class FinancialTransactionTest {

    @Test
    void canBeInstantiatedByJpaUsingProtectedNoArgumentConstructor() throws ReflectiveOperationException {
        var constructor = FinancialTransaction.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        FinancialTransaction transaction = constructor.newInstance();

        assertNull(transaction.getId());
        assertNull(ReflectionTestUtils.getField(transaction, "transactionType"));
        assertNull(ReflectionTestUtils.getField(transaction, "amount"));
        assertNotNull(ReflectionTestUtils.getField(transaction, "accountTransactions"));
        assertEquals(0, ((java.util.List<?>) ReflectionTestUtils.getField(transaction,
                "accountTransactions")).size());
    }

    @Test
    void storesTypeAndAmountAndExposesIdAssignedByPersistence() {
        BigDecimal amount = new BigDecimal("12.50");
        FinancialTransaction transaction = new FinancialTransaction(TransactionType.DEPOSIT, amount);
        UUID generatedId = UUID.fromString("e4728a74-272b-4a45-8805-d314824a8453");
        ReflectionTestUtils.setField(transaction, "id", generatedId);

        assertEquals(TransactionType.DEPOSIT,
                ReflectionTestUtils.getField(transaction, "transactionType"));
        assertEquals(amount, ReflectionTestUtils.getField(transaction, "amount"));
        assertEquals(generatedId, transaction.getId());
    }
}
