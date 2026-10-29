package io.github.ngreiter.accounttransaction.transaction.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonFormat;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class TransactionResponseSnapshotSerializer {

    private final ObjectMapper objectMapper;

    public TransactionResponseSnapshotSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Snapshot created(UUID financialTransactionId, String type, BigDecimal amount,
            String status, Instant postedAt) {
        return snapshot(201, new CreatedTransactionBody(financialTransactionId, type, amount, status, postedAt));
    }

    public Snapshot rejected(TransactionError error) {
        return snapshot(statusFor(error), new ErrorBody(error.code(), error.message()));
    }

    private int statusFor(TransactionError error) {
        return switch (error) {
            case RESOURCE_NOT_FOUND -> 404;
            case IDEMPOTENCY_CONFLICT -> 409;
            case INSUFFICIENT_FUNDS -> 422;
        };
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

    public record CreatedTransactionBody(UUID financialTransactionId, String type,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
            String status, Instant postedAt) {
    }

    public record ErrorBody(String code, String message) {
    }
}
