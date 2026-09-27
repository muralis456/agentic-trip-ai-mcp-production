package com.example.travel.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "api_rate_limit_bucket")
public class ApiRateLimitBucket {
    @Id
    @Column(name = "user_id", length = 80)
    private String userId;

    @Column(name = "window_started_at", nullable = false)
    private Instant windowStartedAt;

    @Column(name = "request_count", nullable = false)
    private int requestCount;

    public String getUserId() { return userId; }
    public Instant getWindowStartedAt() { return windowStartedAt; }
    public int getRequestCount() { return requestCount; }
    public void setUserId(String userId) { this.userId = userId; }
    public void setWindowStartedAt(Instant windowStartedAt) { this.windowStartedAt = windowStartedAt; }
    public void setRequestCount(int requestCount) { this.requestCount = requestCount; }
}
