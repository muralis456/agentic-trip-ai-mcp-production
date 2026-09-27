package com.example.travel.idempotency;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@Service
public class IdempotencyService {
    private final JdbcTemplate jdbc;

    public IdempotencyService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<String> find(String userId, String key, String requestHash) {
        if (key == null || key.isBlank()) return Optional.empty();
        return jdbc.query("select response_body, request_hash, status from agent_idempotency where user_id=? and idempotency_key=?",
                rs -> {
                    if (!rs.next()) return Optional.empty();
                    String storedHash = rs.getString("request_hash");
                    if (!requestHash.equals(storedHash)) {
                        throw new KeyReuseException("Idempotency-Key was already used for a different request");
                    }
                    return Optional.ofNullable(rs.getString("response_body"));
                }, userId, key);
    }

    public boolean claim(String userId, String key, String requestHash) {
        if (key == null || key.isBlank()) return true;
        int inserted = jdbc.update("insert into agent_idempotency(user_id,idempotency_key,request_hash,status,created_at) values (?,?,?,?,?) on conflict (user_id,idempotency_key) do nothing",
                userId, key, requestHash, "IN_PROGRESS", OffsetDateTime.now(ZoneOffset.UTC));
        if (inserted == 1) return true;
        Integer same = jdbc.queryForObject("select count(*) from agent_idempotency where user_id=? and idempotency_key=? and request_hash=?",
                Integer.class, userId, key, requestHash);
        if (same != null && same > 0) throw new DuplicateRequestException("Request is already in progress");
        throw new KeyReuseException("Idempotency-Key was already used for a different request");
    }

    public void complete(String userId, String key, String requestHash, String responseBody) {
        if (key == null || key.isBlank()) return;
        jdbc.update("update agent_idempotency set status='COMPLETED', response_body=? where user_id=? and idempotency_key=? and request_hash=?",
                responseBody, userId, key, requestHash);
    }

    public static class KeyReuseException extends RuntimeException {
        public KeyReuseException(String message) { super(message); }
    }

    public static class DuplicateRequestException extends RuntimeException {
        public DuplicateRequestException(String message) { super(message); }
    }
}
