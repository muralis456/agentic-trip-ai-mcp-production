package com.example.travel.dto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;


/**

 * API response: metadata + structured plan + execution observability.

 */

public class TravelPlanResponse {



    private String threadId;

    /** Server-side lifecycle timestamps for the current plan snapshot. */
    private Instant generatedAt;
    private Instant updatedAt;

    private String status;

    /** Semantic request type for dynamic UI rendering, e.g. WEATHER or TRIP_PLANNING. */
    private String requestType = "TRIP_PLANNING";

    /** True only when the request represents a persisted trip plan workflow. */
    private boolean tripPlanning;

    private boolean awaitingApproval;

    /** Explicit human-decision lifecycle: NOT_REQUIRED, PENDING, APPROVED, or REJECTED. */
    private String approvalState = "NOT_REQUIRED";
    private String goalStatus = "PARTIAL";
    private List<String> unmetCriteria = new ArrayList<>();
    private List<String> blockingIssues = new ArrayList<>();
    private List<String> retryableTasks = new ArrayList<>();

    /** True when execution is paused because a required user detail is missing. */
    private boolean clarificationRequired;
    private String clarificationQuestion = "";

    private String userId;

    private String model;

    private TripPlanResult plan = new TripPlanResult();

    private AgentExecutionDetails execution = new AgentExecutionDetails();



    public TravelPlanResponse() {

    }



    public String getThreadId() {

        return threadId;

    }

    public Instant getGeneratedAt() { return generatedAt; }

    public void setGeneratedAt(Instant generatedAt) { this.generatedAt = generatedAt; }

    public Instant getUpdatedAt() { return updatedAt; }

    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }



    public void setThreadId(String threadId) {

        this.threadId = threadId;

    }



    public String getStatus() {

        return status;

    }



    public void setStatus(String status) {

        this.status = status;

    }



    public String getRequestType() {
        return requestType;
    }

    public void setRequestType(String requestType) {
        this.requestType = requestType == null ? "GENERAL" : requestType;
    }

    public boolean isTripPlanning() {
        return tripPlanning;
    }

    public void setTripPlanning(boolean tripPlanning) {
        this.tripPlanning = tripPlanning;
    }

    public boolean isAwaitingApproval() {

        return awaitingApproval;

    }



    public void setAwaitingApproval(boolean awaitingApproval) {

        this.awaitingApproval = awaitingApproval;

    }

    public String getApprovalState() { return approvalState; }
    public void setApprovalState(String approvalState) {
        this.approvalState = approvalState == null || approvalState.isBlank() ? "NOT_REQUIRED" : approvalState;
    }

    public String getGoalStatus() { return goalStatus; }
    public void setGoalStatus(String goalStatus) { this.goalStatus = goalStatus == null || goalStatus.isBlank() ? "PARTIAL" : goalStatus; }
    public List<String> getUnmetCriteria() { return unmetCriteria; }
    public void setUnmetCriteria(List<String> v) { unmetCriteria = v == null ? new ArrayList<>() : new ArrayList<>(v); }
    public List<String> getBlockingIssues() { return blockingIssues; }
    public void setBlockingIssues(List<String> v) { blockingIssues = v == null ? new ArrayList<>() : new ArrayList<>(v); }
    public List<String> getRetryableTasks() { return retryableTasks; }
    public void setRetryableTasks(List<String> v) { retryableTasks = v == null ? new ArrayList<>() : new ArrayList<>(v); }

    public boolean isClarificationRequired() { return clarificationRequired; }
    public void setClarificationRequired(boolean clarificationRequired) { this.clarificationRequired = clarificationRequired; }
    public String getClarificationQuestion() { return clarificationQuestion; }
    public void setClarificationQuestion(String clarificationQuestion) { this.clarificationQuestion = clarificationQuestion == null ? "" : clarificationQuestion; }


    public String getUserId() {

        return userId;

    }



    public void setUserId(String userId) {

        this.userId = userId;

    }



    public String getModel() {

        return model;

    }



    public void setModel(String model) {

        this.model = model;

    }



    public TripPlanResult getPlan() {

        return plan;

    }



    public void setPlan(TripPlanResult plan) {

        this.plan = plan == null ? new TripPlanResult() : plan;

    }



    public AgentExecutionDetails getExecution() {

        return execution;

    }



    public void setExecution(AgentExecutionDetails execution) {

        this.execution = execution == null ? new AgentExecutionDetails() : execution;

    }

}


