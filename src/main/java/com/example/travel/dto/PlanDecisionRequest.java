package com.example.travel.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class PlanDecisionRequest {

    @JsonIgnore
    private String userId;
    private String threadId;
    private String notes;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
