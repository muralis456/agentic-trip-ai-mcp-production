package com.example.travel.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "graph_progress_event", indexes = @Index(name = "idx_graph_progress_thread_id", columnList = "thread_id,id"))
public class GraphProgressEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "thread_id", nullable = false, length = 160)
    private String threadId;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false, length = 40)
    private String eventType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    public Long getId() { return id; }
    public String getThreadId() { return threadId; }
    public Instant getCreatedAt() { return createdAt; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }

    public void setId(Long id) { this.id = id; }
    public void setThreadId(String threadId) { this.threadId = threadId; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public void setPayload(String payload) { this.payload = payload; }
}
