package com.example.travel.model;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** Machine-readable evaluation of whether the user's actual goal was achieved. */
public class GoalEvaluation implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    public enum Status { ACHIEVED, PARTIAL, FAILED, NEEDS_USER }

    private Status status = Status.PARTIAL;
    private double score;
    private String reason = "";
    private List<String> satisfiedCriteria = new ArrayList<>();
    private List<String> unmetCriteria = new ArrayList<>();
    private List<String> blockingIssues = new ArrayList<>();
    private boolean recoverable;

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status == null ? Status.PARTIAL : status; }
    public double getScore() { return score; }
    public void setScore(double score) { this.score = Math.max(0, Math.min(1, score)); }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason == null ? "" : reason; }
    public List<String> getSatisfiedCriteria() { return satisfiedCriteria; }
    public void setSatisfiedCriteria(List<String> v) { satisfiedCriteria = v == null ? new ArrayList<>() : new ArrayList<>(v); }
    public List<String> getUnmetCriteria() { return unmetCriteria; }
    public void setUnmetCriteria(List<String> v) { unmetCriteria = v == null ? new ArrayList<>() : new ArrayList<>(v); }
    public List<String> getBlockingIssues() { return blockingIssues; }
    public void setBlockingIssues(List<String> v) { blockingIssues = v == null ? new ArrayList<>() : new ArrayList<>(v); }
    public boolean isRecoverable() { return recoverable; }
    public void setRecoverable(boolean recoverable) { this.recoverable = recoverable; }
}
