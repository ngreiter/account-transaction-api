package io.github.ngreiter.accounttransaction.transaction.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.mockito.ArgumentCaptor;
import org.springframework.test.json.JsonAssert;
import org.springframework.test.json.JsonCompareMode;

import tools.jackson.databind.ObjectMapper;

@JsonTest
class TransactionResponseSnapshotSerializerTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void serializesSuccessfulOperationWithCreatedStatusAndOperationStatus() throws Exception {
        UUID transactionId = UUID.fromString("e4728a74-272b-4a45-8805-d314824a8453");
        Instant postedAt = Instant.parse("2026-10-28T12:00:00Z");

        TransactionResponseSnapshotSerializer.Snapshot snapshot =
                new TransactionResponseSnapshotSerializer(objectMapper).created(transactionId, "TRANSFER",
                        new BigDecimal("7.50"), "SUCCEEDED", postedAt);

        assertEquals(201, snapshot.status());
        JsonAssert.comparator(JsonCompareMode.STRICT).assertIsMatch(
                expectedJson("/json/transaction/transaction-transfer.json"), snapshot.body());
    }

    @Test
    void mapsEachRejectionToItsHttpStatusAndErrorCode() throws Exception {
        assertRejected(TransactionError.RESOURCE_NOT_FOUND, 404);
        assertRejected(TransactionError.IDEMPOTENCY_CONFLICT, 409);
        assertRejected(TransactionError.INSUFFICIENT_FUNDS, 422);
    }

    private void assertRejected(TransactionError error, int expectedStatus) throws Exception {
        ObjectMapper mockedMapper = mock(ObjectMapper.class);
        when(mockedMapper.writeValueAsString(any())).thenReturn("serialized-body");
        TransactionResponseSnapshotSerializer.Snapshot snapshot =
                new TransactionResponseSnapshotSerializer(mockedMapper).rejected(error);

        assertEquals(expectedStatus, snapshot.status());
        assertEquals("serialized-body", snapshot.body());
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(mockedMapper).writeValueAsString(bodyCaptor.capture());
        TransactionResponseSnapshotSerializer.ErrorBody body =
                (TransactionResponseSnapshotSerializer.ErrorBody) bodyCaptor.getValue();
        assertEquals(error.code(), body.code());
        assertEquals(error.message(), body.message());
        clearInvocations(mockedMapper);
    }

    private String expectedJson(String resourcePath) throws IOException {
        try (InputStream fixture = getClass().getResourceAsStream(resourcePath)) {
            if (fixture == null) {
                throw new IllegalStateException("JSON fixture not found: " + resourcePath);
            }
            return new String(fixture.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
