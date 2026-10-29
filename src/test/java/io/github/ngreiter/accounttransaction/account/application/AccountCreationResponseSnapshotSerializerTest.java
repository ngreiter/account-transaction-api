package io.github.ngreiter.accounttransaction.account.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

class AccountCreationResponseSnapshotSerializerTest {

    private ObjectMapper objectMapper;
    private AccountCreationResponseSnapshotSerializer serializer;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = mock(ObjectMapper.class);
        when(objectMapper.writeValueAsString(any())).thenReturn("serialized-body");
        serializer = new AccountCreationResponseSnapshotSerializer(objectMapper);
    }

    @Test
    void serializesCreatedAccountSnapshotWithCreatedStatus() throws Exception {
        Instant createdAt = Instant.parse("2026-10-28T12:00:00Z");

        AccountCreationResponseSnapshotSerializer.Snapshot snapshot = serializer.created("12345678-9",
                new BigDecimal("0.00"), createdAt);

        assertEquals(201, snapshot.status());
        assertEquals("serialized-body", snapshot.body());
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(objectMapper).writeValueAsString(bodyCaptor.capture());
        AccountCreationResponseSnapshotSerializer.CreatedAccountBody body =
                (AccountCreationResponseSnapshotSerializer.CreatedAccountBody) bodyCaptor.getValue();
        assertEquals("12345678-9", body.accountNumber());
        assertEquals(new BigDecimal("0.00"), body.balance());
        assertEquals(createdAt, body.createdAt());
    }

    @Test
    void serializesRejectedAccountSnapshotAsConflict() throws Exception {
        AccountCreationResponseSnapshotSerializer.Snapshot snapshot =
                serializer.rejected(AccountCreationError.ACCOUNT_CONFLICT);

        assertEquals(409, snapshot.status());
        assertEquals("serialized-body", snapshot.body());
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(objectMapper).writeValueAsString(bodyCaptor.capture());
        AccountCreationResponseSnapshotSerializer.ErrorBody body =
                (AccountCreationResponseSnapshotSerializer.ErrorBody) bodyCaptor.getValue();
        assertEquals("ACCOUNT_CONFLICT", body.code());
        assertEquals(AccountCreationError.ACCOUNT_CONFLICT.message(), body.message());
    }

    @Test
    void wrapsJacksonFailureWhenSerializingSnapshot() {
        when(objectMapper.writeValueAsString(any())).thenThrow(new JacksonException("serialization failed") {
        });

        assertThrows(IllegalStateException.class,
                () -> serializer.created("12345678-9", new BigDecimal("0.00"), Instant.EPOCH));
    }
}
