package com.example.travel.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "agent_goal_evaluation", indexes = {
        @Index(name = "idx_agent_goal_eval_user_created", columnList = "user_id,created_at"),
        @Index(name = "idx_agent_goal_eval_user_status", columnList = "user_id,status,created_at"),
        @Index(name = "idx_agent_goal_eval_thread", columnList = "user_id,thread_id,created_at")
})
public class AgentGoalEvaluation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 120)
    private String userId;

    @Column(name = "thread_id", nullable = false, length = 120)
    private String threadId;

    @Column(length = 200)
    private String goal;

    @Column(length = 120)
    private String destination;

    @Column(length = 100)
    private String budget;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(nullable = false)
    private double score;

    @Column(length = 500)
    private String reason;

    @Column(name = "satisfied_criteria_json", nullable = false, columnDefinition = "TEXT")
    private String satisfiedCriteriaJson = "[]";

    @Column(name = "unmet_criteria_json", nullable = false, columnDefinition = "TEXT")
    private String unmetCriteriaJson = "[]";

    @Column(name = "blocking_issues_json", nullable = false, columnDefinition = "TEXT")
    private String blockingIssuesJson = "[]";

    @Column(nullable = false)
    private boolean recoverable;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Long getId() { return id; }
    public String getUserId() { return userId; }
    public String getThreadId() { return threadId; }
    public String getGoal() { return goal; }
    public String getDestination() { return destination; }
    public String getBudget() { return budget; }
    public String getStatus() { return status; }
    public double getScore() { return score; }
    public String getReason() { return reason; }
    public String getSatisfiedCriteriaJson() { return satisfiedCriteriaJson; }
    public String getUnmetCriteriaJson() { return unmetCriteriaJson; }
    public String getBlockingIssuesJson() { return blockingIssuesJson; }
    public boolean isRecoverable() { return recoverable; }
    public Instant getCreatedAt() { return createdAt; }

    public void setUserId(String userId) { this.userId = userId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }
    public void setGoal(String goal) { this.goal = goal; }
    public void setDestination(String destination) { this.destination = destination; }
    public void setBudget(String budget) { this.budget = budget; }
    public void setStatus(String status) { this.status = status; }
    public void setScore(double score) { this.score = Math.max(0, Math.min(1, score)); }
    public void setReason(String reason) { this.reason = reason; }
    public void setSatisfiedCriteriaJson(String value) { this.satisfiedCriteriaJson = value == null ? "[]" : value; }
    public void setUnmetCriteriaJson(String value) { this.unmetCriteriaJson = value == null ? "[]" : value; }
    public void setBlockingIssuesJson(String value) { this.blockingIssuesJson = value == null ? "[]" : value; }
    public void setRecoverable(boolean recoverable) { this.recoverable = recoverable; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
