package io.github.ngreiter.accounttransaction.account.application;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonFormat;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class AccountCreationResponseSnapshotSerializer {

    private static final int CREATED_STATUS = 201;
    private static final int CONFLICT_STATUS = 409;

    private final ObjectMapper objectMapper;

    public AccountCreationResponseSnapshotSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Snapshot created(String accountNumber, BigDecimal balance, Instant createdAt) {
        return snapshot(CREATED_STATUS, new CreatedAccountBody(accountNumber, balance, createdAt));
    }

    public Snapshot rejected(AccountCreationError error) {
        return snapshot(CONFLICT_STATUS, new ErrorBody(error.code(), error.message()));
    }

    private Snapshot snapshot(int status, Object body) {
        try {
            return new Snapshot(status, objectMapper.writeValueAsString(body));
        } catch (JacksonException exception) {
            throw new IllegalStateException("N\u00e3o foi poss\u00edvel serializar a resposta.", exception);
        }
    }

    public record Snapshot(int status, String body) {
    }

    public record CreatedAccountBody(String accountNumber,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal balance,
            Instant createdAt) {
    }

    public record ErrorBody(String code, String message) {
    }
}
