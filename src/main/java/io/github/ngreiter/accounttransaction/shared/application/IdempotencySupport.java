package io.github.ngreiter.accounttransaction.shared.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.stereotype.Component;

import io.github.ngreiter.accounttransaction.domain.FinancialTransaction;
import io.github.ngreiter.accounttransaction.domain.IdempotencyRecord;
import io.github.ngreiter.accounttransaction.persistence.IdempotencyRecordRepository;

@Component
public class IdempotencySupport {

    private final IdempotencyRecordRepository repository;

    public IdempotencySupport(IdempotencyRecordRepository repository) {
        this.repository = repository;
    }

    public void validateKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 255) {
            throw new IllegalArgumentException("Idempotency-Key inv\u00e1lida.");
        }
    }

    public String hashCanonicalRequest(String canonicalRequest) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalRequest.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indispon\u00edvel.", exception);
        }
    }

    public Optional<IdempotencyRecord> findByKey(String idempotencyKey) {
        return repository.findByIdempotencyKey(idempotencyKey);
    }

    public boolean matchesRequest(IdempotencyRecord record, String requestHash) {
        return record.getRequestHash().equals(requestHash);
    }

    public void save(String scope, String idempotencyKey, String requestHash,
            int responseStatus, String responseBody) {
        repository.save(new IdempotencyRecord(scope, idempotencyKey, requestHash, responseStatus, responseBody));
    }

    public void save(String scope, String idempotencyKey, String requestHash,
            FinancialTransaction financialTransaction, int responseStatus, String responseBody) {
        repository.save(new IdempotencyRecord(scope, idempotencyKey, requestHash,
                financialTransaction, responseStatus, responseBody));
    }
}
