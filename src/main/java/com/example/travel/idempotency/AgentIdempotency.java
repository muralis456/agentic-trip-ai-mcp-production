package com.example.travel.idempotency;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "agent_idempotency")
public class AgentIdempotency {
    @EmbeddedId
    private AgentIdempotencyId id;

    @Column(name = "request_hash", length = 128, nullable = false)
    private String requestHash;

    @Column(name = "status", length = 32, nullable = false)
    private String status;

    @Column(name = "response_body", columnDefinition = "text")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected AgentIdempotency() {}

    public AgentIdempotency(AgentIdempotencyId id, String requestHash, String status,
                            String responseBody, OffsetDateTime createdAt) {
        this.id = id;
        this.requestHash = requestHash;
        this.status = status;
        this.responseBody = responseBody;
        this.createdAt = createdAt;
    }

    public AgentIdempotencyId getId() { return id; }
    public String getRequestHash() { return requestHash; }
    public String getStatus() { return status; }
    public String getResponseBody() { return responseBody; }
    public void setStatus(String status) { this.status = status; }
    public void setResponseBody(String responseBody) { this.responseBody = responseBody; }
}
