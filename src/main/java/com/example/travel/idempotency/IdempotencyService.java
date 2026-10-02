package com.example.travel.idempotency;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@Service
public class IdempotencyService {
    private final AgentIdempotencyRepository repository;

    public IdempotencyService(AgentIdempotencyRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<String> find(String userId, String key, String requestHash) {
        if (key == null || key.isBlank()) return Optional.empty();

        return repository.findById(new AgentIdempotencyId(userId, key))
                .map(record -> {
                    if (!requestHash.equals(record.getRequestHash())) {
                        throw new KeyReuseException("Idempotency-Key was already used for a different request");
                    }
                    return record.getResponseBody();
                });
    }

    @Transactional
    public boolean claim(String userId, String key, String requestHash) {
        if (key == null || key.isBlank()) return true;

        int inserted = repository.insertIfAbsent(
                userId, key, requestHash, OffsetDateTime.now(ZoneOffset.UTC));

        if (inserted == 1) return true;

        AgentIdempotency existing = repository.findById(new AgentIdempotencyId(userId, key))
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotency record disappeared during concurrent claim"));

        if (requestHash.equals(existing.getRequestHash())) {
            throw new DuplicateRequestException("Request is already in progress");
        }
        throw new KeyReuseException("Idempotency-Key was already used for a different request");
    }

    @Transactional
    public void complete(String userId, String key, String requestHash, String responseBody) {
        if (key == null || key.isBlank()) return;

        AgentIdempotency existing = repository.findById(new AgentIdempotencyId(userId, key))
                .orElseThrow(() -> new IllegalStateException("Idempotency record not found"));

        if (!requestHash.equals(existing.getRequestHash())) {
            throw new KeyReuseException("Idempotency-Key was already used for a different request");
        }

        existing.setStatus("COMPLETED");
        existing.setResponseBody(responseBody);
        repository.save(existing);
    }

    public static class KeyReuseException extends RuntimeException {
        public KeyReuseException(String message) { super(message); }
    }

    public static class DuplicateRequestException extends RuntimeException {
        public DuplicateRequestException(String message) { super(message); }
    }
}
