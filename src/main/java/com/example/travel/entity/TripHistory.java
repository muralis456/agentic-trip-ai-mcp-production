package com.example.travel.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "trip_history", indexes = {
        @Index(name = "idx_trip_history_user_updated", columnList = "userId,updatedAt"),
        @Index(name = "idx_trip_history_thread", columnList = "threadId", unique = true)
})
public class TripHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false, unique = true)
    private String threadId;

    @Column(nullable = false)
    private String title;

    private String origin;
    private String destination;
    private String departureDate;
    private String returnDate;
    private int travelers;
    private String budgetLabel;
    private String status;
    private boolean awaitingApproval;
    private int qualityScore;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String planJson;

    public Long getId() { return id; }
    public String getUserId() { return userId; }
    public String getThreadId() { return threadId; }
    public String getTitle() { return title; }
    public String getOrigin() { return origin; }
    public String getDestination() { return destination; }
    public String getDepartureDate() { return departureDate; }
    public String getReturnDate() { return returnDate; }
    public int getTravelers() { return travelers; }
    public String getBudgetLabel() { return budgetLabel; }
    public String getStatus() { return status; }
    public boolean isAwaitingApproval() { return awaitingApproval; }
    public int getQualityScore() { return qualityScore; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getPlanJson() { return planJson; }

    public void setId(Long id) { this.id = id; }
    public void setUserId(String userId) { this.userId = userId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }
    public void setTitle(String title) { this.title = title; }
    public void setOrigin(String origin) { this.origin = origin; }
    public void setDestination(String destination) { this.destination = destination; }
    public void setDepartureDate(String departureDate) { this.departureDate = departureDate; }
    public void setReturnDate(String returnDate) { this.returnDate = returnDate; }
    public void setTravelers(int travelers) { this.travelers = travelers; }
    public void setBudgetLabel(String budgetLabel) { this.budgetLabel = budgetLabel; }
    public void setStatus(String status) { this.status = status; }
    public void setAwaitingApproval(boolean awaitingApproval) { this.awaitingApproval = awaitingApproval; }
    public void setQualityScore(int qualityScore) { this.qualityScore = qualityScore; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public void setPlanJson(String planJson) { this.planJson = planJson; }
}
