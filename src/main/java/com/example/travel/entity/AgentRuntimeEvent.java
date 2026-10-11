package com.example.travel.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "agent_runtime_event", indexes = {
        @Index(name = "idx_agent_runtime_user_time", columnList = "user_id,created_at"),
        @Index(name = "idx_agent_runtime_user_thread_time", columnList = "user_id,thread_id,created_at"),
        @Index(name = "idx_agent_runtime_user_type_time", columnList = "user_id,event_type,created_at")
})
public class AgentRuntimeEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false, length = 120)
    private String userId;
    @Column(name = "thread_id", nullable = false, length = 160)
    private String threadId;
    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;
    @Column(nullable = false, length = 120)
    private String component;
    @Column(nullable = false, length = 16)
    private String status;
    @Column(name = "duration_ms", nullable = false)
    private long durationMs;
    @Column(nullable = false)
    private int attempt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AgentRuntimeEvent() {}

    public AgentRuntimeEvent(String userId, String threadId, String eventType, String component,
                             String status, long durationMs, int attempt, Instant createdAt) {
        this.userId = safe(userId, 120);
        this.threadId = safe(threadId, 160);
        this.eventType = safe(eventType, 40);
        this.component = safe(component, 120);
        this.status = safe(status, 16);
        this.durationMs = Math.max(0, durationMs);
        this.attempt = Math.max(1, attempt);
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    private static String safe(String value, int max) {
        if (value == null) return "unknown";
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (normalized.isBlank()) return "unknown";
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    public Long getId() { return id; }
    public String getUserId() { return userId; }
    public String getThreadId() { return threadId; }
    public String getEventType() { return eventType; }
    public String getComponent() { return component; }
    public String getStatus() { return status; }
    public long getDurationMs() { return durationMs; }
    public int getAttempt() { return attempt; }
    public Instant getCreatedAt() { return createdAt; }
}
