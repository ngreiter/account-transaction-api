package io.github.ngreiter.accounttransaction.transaction.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import tools.jackson.databind.ObjectMapper;

class TransactionResponseSnapshotSerializerTest {

    private ObjectMapper objectMapper;
    private TransactionResponseSnapshotSerializer serializer;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = mock(ObjectMapper.class);
        when(objectMapper.writeValueAsString(any())).thenReturn("serialized-body");
        serializer = new TransactionResponseSnapshotSerializer(objectMapper);
    }

    @Test
    void serializesSuccessfulOperationWithCreatedStatusAndOperationStatus() throws Exception {
        UUID transactionId = UUID.fromString("e4728a74-272b-4a45-8805-d314824a8453");
        Instant postedAt = Instant.parse("2026-10-28T12:00:00Z");

        TransactionResponseSnapshotSerializer.Snapshot snapshot = serializer.created(transactionId, "TRANSFER",
                new BigDecimal("7.50"), "SUCCEEDED", postedAt);

        assertEquals(201, snapshot.status());
        assertEquals("serialized-body", snapshot.body());
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(objectMapper).writeValueAsString(bodyCaptor.capture());
        TransactionResponseSnapshotSerializer.CreatedTransactionBody body =
                (TransactionResponseSnapshotSerializer.CreatedTransactionBody) bodyCaptor.getValue();
        assertEquals(transactionId, body.financialTransactionId());
        assertEquals("TRANSFER", body.type());
        assertEquals(new BigDecimal("7.50"), body.amount());
        assertEquals("SUCCEEDED", body.status());
        assertEquals(postedAt, body.postedAt());
    }

    @Test
    void mapsEachRejectionToItsHttpStatusAndErrorCode() throws Exception {
        assertRejected(TransactionError.RESOURCE_NOT_FOUND, 404);
        assertRejected(TransactionError.IDEMPOTENCY_CONFLICT, 409);
        assertRejected(TransactionError.INSUFFICIENT_FUNDS, 422);
    }

    private void assertRejected(TransactionError error, int expectedStatus) throws Exception {
        TransactionResponseSnapshotSerializer.Snapshot snapshot = serializer.rejected(error);

        assertEquals(expectedStatus, snapshot.status());
        assertEquals("serialized-body", snapshot.body());
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(objectMapper).writeValueAsString(bodyCaptor.capture());
        TransactionResponseSnapshotSerializer.ErrorBody body =
                (TransactionResponseSnapshotSerializer.ErrorBody) bodyCaptor.getValue();
        assertEquals(error.code(), body.code());
        assertEquals(error.message(), body.message());
        clearInvocations(objectMapper);
    }
}
