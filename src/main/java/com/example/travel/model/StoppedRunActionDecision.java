package com.example.travel.model;

import java.io.Serializable;

/** Semantic routing decision for user input received while a run is stopped. */
public class StoppedRunActionDecision implements Serializable {
    private String action = "NEW_REQUEST";
    private double confidence;
    private String reason = "";
    /** Full requirement after merging the stopped request with the current user turn. */
    private String effectiveRequest = "";

    public String getAction() { return action == null ? "NEW_REQUEST" : action; }
    public void setAction(String action) { this.action = action == null ? "NEW_REQUEST" : action.trim().toUpperCase(); }
    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }
    public String getReason() { return reason == null ? "" : reason; }
    public void setReason(String reason) { this.reason = reason == null ? "" : reason; }

    public String getEffectiveRequest() { return effectiveRequest == null ? "" : effectiveRequest; }
    public void setEffectiveRequest(String effectiveRequest) { this.effectiveRequest = effectiveRequest == null ? "" : effectiveRequest; }
}
