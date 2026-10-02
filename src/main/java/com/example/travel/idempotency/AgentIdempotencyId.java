package com.example.travel.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class AgentIdempotencyId implements Serializable {
    @Column(name = "user_id", length = 255, nullable = false)
    private String userId;

    @Column(name = "idempotency_key", length = 255, nullable = false)
    private String idempotencyKey;

    protected AgentIdempotencyId() {}

    public AgentIdempotencyId(String userId, String idempotencyKey) {
        this.userId = userId;
        this.idempotencyKey = idempotencyKey;
    }

    public String getUserId() { return userId; }
    public String getIdempotencyKey() { return idempotencyKey; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AgentIdempotencyId that)) return false;
        return Objects.equals(userId, that.userId)
                && Objects.equals(idempotencyKey, that.idempotencyKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, idempotencyKey);
    }
}
