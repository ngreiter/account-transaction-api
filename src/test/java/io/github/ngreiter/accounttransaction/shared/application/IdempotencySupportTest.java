package io.github.ngreiter.accounttransaction.shared.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import io.github.ngreiter.accounttransaction.domain.FinancialTransaction;
import io.github.ngreiter.accounttransaction.domain.IdempotencyRecord;
import io.github.ngreiter.accounttransaction.persistence.IdempotencyRecordRepository;

class IdempotencySupportTest {

    private IdempotencyRecordRepository repository;
    private IdempotencySupport support;

    @BeforeEach
    void setUp() {
        repository = mock(IdempotencyRecordRepository.class);
        support = new IdempotencySupport(repository);
    }

    @Test
    void acceptsNonBlankIdempotencyKeysUpToMaximumLength() {
        support.validateKey("key");
        support.validateKey("k".repeat(255));
    }

    @Test
    void rejectsNullBlankAndOverlongIdempotencyKeys() {
        assertThrows(IllegalArgumentException.class, () -> support.validateKey(null));
        assertThrows(IllegalArgumentException.class, () -> support.validateKey("  "));
        assertThrows(IllegalArgumentException.class, () -> support.validateKey("k".repeat(256)));
    }

    @Test
    void hashesCanonicalRequestWithSha256() {
        assertEquals("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
                support.hashCanonicalRequest("test"));
    }

    @Test
    void findsIdempotencyRecordByKey() {
        IdempotencyRecord record = new IdempotencyRecord("scope", "key", "hash", 201, "body");
        when(repository.findByIdempotencyKey("key")).thenReturn(Optional.of(record));

        assertEquals(Optional.of(record), support.findByKey("key"));
        verify(repository).findByIdempotencyKey("key");
    }

    @Test
    void comparesRequestHashAgainstStoredRecord() {
        IdempotencyRecord record = new IdempotencyRecord("scope", "key", "same-hash", 201, "body");

        assertTrue(support.matchesRequest(record, "same-hash"));
        assertFalse(support.matchesRequest(record, "different-hash"));
    }

    @Test
    void savesResponseSnapshotWithoutFinancialTransaction() {
        support.save("scope", "key", "hash", 409, "conflict-body");

        ArgumentCaptor<IdempotencyRecord> recordCaptor = ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(repository).save(recordCaptor.capture());
        assertEquals("hash", recordCaptor.getValue().getRequestHash());
        assertEquals(409, recordCaptor.getValue().getResponseStatus());
        assertEquals("conflict-body", recordCaptor.getValue().getResponseBody());
    }

    @Test
    void savesResponseSnapshotLinkedToFinancialTransaction() {
        FinancialTransaction financialTransaction = mock(FinancialTransaction.class);

        support.save("scope", "key", "hash", financialTransaction, 201, "created-body");

        ArgumentCaptor<IdempotencyRecord> recordCaptor = ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(repository).save(recordCaptor.capture());
        assertEquals("hash", recordCaptor.getValue().getRequestHash());
        assertEquals(201, recordCaptor.getValue().getResponseStatus());
        assertEquals("created-body", recordCaptor.getValue().getResponseBody());
        assertSame(financialTransaction, ReflectionTestUtils.getField(recordCaptor.getValue(), "financialTransaction"));
    }
}
