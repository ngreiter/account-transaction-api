package io.github.ngreiter.accounttransaction.account.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.mockito.ArgumentCaptor;
import org.springframework.test.json.JsonAssert;
import org.springframework.test.json.JsonCompareMode;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@JsonTest
class AccountCreationResponseSnapshotSerializerTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void serializesCreatedAccountSnapshotWithCreatedStatus() throws Exception {
        Instant createdAt = Instant.parse("2026-10-28T12:00:00Z");

        AccountCreationResponseSnapshotSerializer.Snapshot snapshot =
                new AccountCreationResponseSnapshotSerializer(objectMapper)
                        .created("12345678-9", new BigDecimal("0.00"), createdAt);

        assertEquals(201, snapshot.status());
        JsonAssert.comparator(JsonCompareMode.STRICT).assertIsMatch(
                expectedJson("/json/account/account-created.json"), snapshot.body());
    }

    @Test
    void serializesRejectedAccountSnapshotAsConflict() throws Exception {
        ObjectMapper mockedMapper = mock(ObjectMapper.class);
        when(mockedMapper.writeValueAsString(any())).thenReturn("serialized-body");
        AccountCreationResponseSnapshotSerializer.Snapshot snapshot =
                new AccountCreationResponseSnapshotSerializer(mockedMapper)
                        .rejected(AccountCreationError.ACCOUNT_CONFLICT);

        assertEquals(409, snapshot.status());
        assertEquals("serialized-body", snapshot.body());
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(mockedMapper).writeValueAsString(bodyCaptor.capture());
        AccountCreationResponseSnapshotSerializer.ErrorBody body =
                (AccountCreationResponseSnapshotSerializer.ErrorBody) bodyCaptor.getValue();
        assertEquals("ACCOUNT_CONFLICT", body.code());
        assertEquals(AccountCreationError.ACCOUNT_CONFLICT.message(), body.message());
    }

    @Test
    void wrapsJacksonFailureWhenSerializingSnapshot() {
        ObjectMapper mockedMapper = mock(ObjectMapper.class);
        when(mockedMapper.writeValueAsString(any())).thenThrow(new JacksonException("serialization failed") {
        });

        assertThrows(IllegalStateException.class,
                () -> new AccountCreationResponseSnapshotSerializer(mockedMapper)
                        .created("12345678-9", new BigDecimal("0.00"), Instant.EPOCH));
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
