package com.example.travel.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "agent_model_usage_event", indexes = {
        @Index(name = "idx_agent_model_usage_user_time", columnList = "user_id,created_at"),
        @Index(name = "idx_agent_model_usage_user_thread_time", columnList = "user_id,thread_id,created_at"),
        @Index(name = "idx_agent_model_usage_user_model_time", columnList = "user_id,model,created_at")
})
public class AgentModelUsageEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 120)
    private String userId;

    @Column(name = "thread_id", nullable = false, length = 160)
    private String threadId;

    @Column(nullable = false, length = 120)
    private String model;

    @Column(nullable = false, length = 40)
    private String operation;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "input_tokens", nullable = false)
    private long inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private long outputTokens;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AgentModelUsageEvent() {}

    public AgentModelUsageEvent(String userId, String threadId, String model, String operation,
                                String status, long inputTokens, long outputTokens,
                                long durationMs, Instant createdAt) {
        this.userId = userId;
        this.threadId = threadId;
        this.model = model;
        this.operation = operation;
        this.status = status;
        this.inputTokens = Math.max(0, inputTokens);
        this.outputTokens = Math.max(0, outputTokens);
        this.durationMs = Math.max(0, durationMs);
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public Long getId() { return id; }
    public String getUserId() { return userId; }
    public String getThreadId() { return threadId; }
    public String getModel() { return model; }
    public String getOperation() { return operation; }
    public String getStatus() { return status; }
    public long getInputTokens() { return inputTokens; }
    public long getOutputTokens() { return outputTokens; }
    public long getDurationMs() { return durationMs; }
    public Instant getCreatedAt() { return createdAt; }
}